package tun.proxy.service

import android.util.Base64
import android.util.Log
import java.io.*
import java.net.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A lightweight local SOCKS5 proxy that sits between tun2socks and the real
 * upstream proxy.
 *
 * Purpose
 * -------
 * When Remote DNS is active, tun2socks is configured to use this proxy
 * (socks5://127.0.0.1:PORT) instead of the real upstream proxy.  This proxy:
 *
 * 1. CONNECT to a **fake IP** (10.255.x.x):
 *    Looks up the original hostname in [FakeDnsDatabase] and forwards a
 *    SOCKS5/CONNECT-DOMAIN or HTTP-CONNECT request to the real upstream proxy
 *    using the hostname.  This makes DNS resolution happen on the proxy server.
 *
 * 2. CONNECT to a **real IP** or hostname:
 *    Forwards the request as-is to the real upstream proxy.
 *
 * 3. **UDP ASSOCIATE**:
 *    Creates a UDP relay socket.  All UDP datagrams whose destination port is 53
 *    and whose destination IP matches [fakeDnsServerIp] are answered locally by
 *    [FakeDnsPacketHandler] (returns fake IPs from the configured subnet).
 *    All other UDP traffic is silently dropped (most SOCKS5 proxies don't
 *    support SOCKS5 UDP relay anyway).
 *
 * Architecture note
 * -----------------
 * The VPN service package is excluded from the VPN via addDisallowedApplication,
 * so all outbound connections made by this class go directly to the real network
 * and are NOT looped back through tun2socks.
 */
class RemoteDnsProxyServer(
    /** Full URL of the real upstream proxy, e.g. "socks5://user:pass@host:1080" */
    private val realProxyUrl: String,
    private val fakeDnsDb: FakeDnsDatabase,
    /** The IP address configured as the VPN DNS server (e.g. "10.1.10.2"). */
    private val fakeDnsServerIp: String,
    private val dnsHandler: FakeDnsPacketHandler
) {
    private val TAG = "RemoteDnsProxy"

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    /** Starts the server and returns the local TCP port it is listening on. */
    fun start(): Int {
        val ss = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        val port = ss.localPort
        running.set(true)

        Thread(
            Runnable {
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        executor.execute { handleSocks5Connection(client) }
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "accept(): ${e.message}")
                    }
                }
            },
            "RemoteDnsProxy-Acceptor"
        ).apply { isDaemon = true }.start()

        Log.i(TAG, "Started on port $port  (upstream: ${maskUrl(realProxyUrl)})")
        return port
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        Log.i(TAG, "Stopped")
    }

    // =========================================================================
    // SOCKS5 connection dispatcher
    // =========================================================================

    private fun handleSocks5Connection(client: Socket) {
        try {
            client.use { sock ->
                sock.soTimeout = 15_000
                val inp = DataInputStream(sock.getInputStream())
                val out = DataOutputStream(sock.getOutputStream())

                // ---- greeting ----
                val ver = inp.read()
                if (ver != 5) return
                val nMethods = inp.read()
                if (nMethods < 1) return
                val methods = ByteArray(nMethods)
                inp.readFully(methods)
                // Always negotiate NO AUTH (0x00)
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()

                // ---- request ----
                val reqVer = inp.read(); if (reqVer != 5) return
                val cmd    = inp.read()
                inp.read()                  // RSV
                val atyp   = inp.read()

                val destHost = when (atyp) {
                    0x01 -> {                // IPv4
                        val b = ByteArray(4); inp.readFully(b)
                        "${b[0].ui()}.${b[1].ui()}.${b[2].ui()}.${b[3].ui()}"
                    }
                    0x03 -> {                // Domain
                        val len = inp.read()
                        val b = ByteArray(len); inp.readFully(b)
                        String(b, Charsets.UTF_8)
                    }
                    0x04 -> {                // IPv6
                        val b = ByteArray(16); inp.readFully(b)
                        InetAddress.getByAddress(b).hostAddress ?: return
                    }
                    else -> return
                }
                val destPort = inp.readUnsignedShort()

                when (cmd) {
                    0x01 -> handleConnect(sock, inp, out, destHost, destPort)
                    0x03 -> handleUdpAssociate(sock, inp, out)
                    else -> {
                        sendReply(out, 0x07)  // Command not supported
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connection closed: ${e.message}")
        }
    }

    // =========================================================================
    // CONNECT handler
    // =========================================================================

    private fun handleConnect(
        client: Socket,
        inp: DataInputStream,
        out: DataOutputStream,
        destHost: String,
        destPort: Int
    ) {
        // If destHost is a fake IP, resolve the original hostname
        val actualHost = if (fakeDnsDb.isFakeIp(destHost)) {
            val h = fakeDnsDb.getHostname(destHost)
            Log.d(TAG, "CONNECT $destHost:$destPort  →  $h:$destPort (fake→hostname)")
            h ?: destHost
        } else {
            destHost
        }

        val upstream: Socket
        try {
            upstream = connectToUpstream(actualHost, destPort)
        } catch (e: Exception) {
            Log.w(TAG, "upstream connect to $actualHost:$destPort failed: ${e.message}")
            sendReply(out, 0x04)   // Host unreachable
            return
        }

        // Success reply: BND.ADDR = 127.0.0.1, BND.PORT = 0
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1))
        out.writeShort(0)
        out.flush()

        // Bidirectional relay until one side closes
        relay(client, upstream)
    }

    // =========================================================================
    // UDP ASSOCIATE handler
    // =========================================================================

    private fun handleUdpAssociate(
        controlSock: Socket,
        inp: DataInputStream,
        out: DataOutputStream
    ) {
        val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val relayPort = relay.localPort

        // Tell the client where to send UDP datagrams
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1))
        out.writeShort(relayPort)
        out.flush()

        Log.d(TAG, "UDP ASSOCIATE relay on 127.0.0.1:$relayPort")

        // Relay thread: handles incoming SOCKS5-encapsulated UDP datagrams
        var clientUdpAddr: InetAddress? = null
        var clientUdpPort = 0

        val udpThread = Thread(
            Runnable {
                val buf = ByteArray(65_536)
                while (!controlSock.isClosed) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        relay.soTimeout = 1_000
                        relay.receive(pkt)

                        clientUdpAddr = pkt.address
                        clientUdpPort = pkt.port
                        val data = pkt.data
                        val len  = pkt.length

                        if (len < 10) continue
                        // SOCKS5 UDP header: RSV(2) FRAG(1) ATYP(1) ADDR PORT DATA
                        if (data[2].toInt() and 0xFF != 0) continue  // fragments not supported

                        val atyp = data[3].toInt() and 0xFF
                        var hdrEnd = 4
                        val udpDst: String = when (atyp) {
                            0x01 -> {
                                val s = "${data[4].ui()}.${data[5].ui()}.${data[6].ui()}.${data[7].ui()}"
                                hdrEnd += 4; s
                            }
                            0x03 -> {
                                val dLen = data[4].toInt() and 0xFF
                                val s = String(data, 5, dLen, Charsets.UTF_8)
                                hdrEnd += 1 + dLen; s
                            }
                            0x04 -> {
                                val s = InetAddress.getByAddress(data.copyOfRange(4, 20)).hostAddress ?: continue
                                hdrEnd += 16; s
                            }
                            else -> continue
                        }
                        val udpDstPort = (data[hdrEnd].toInt() and 0xFF shl 8) or
                                        (data[hdrEnd + 1].toInt() and 0xFF)
                        hdrEnd += 2
                        val payload = data.copyOfRange(hdrEnd, len)

                        // Intercept DNS queries to the fake DNS server IP
                        if (udpDstPort == 53 && udpDst == fakeDnsServerIp) {
                            val dnsResp = dnsHandler.handleQuery(payload)
                            if (dnsResp != null) {
                                val wrapped = wrapSocks5Udp(fakeDnsServerIp, 53, dnsResp)
                                relay.send(DatagramPacket(
                                    wrapped, wrapped.size,
                                    clientUdpAddr!!, clientUdpPort
                                ))
                            }
                        }
                        // Other UDP (non-DNS): silently drop.
                        // Most SOCKS5 proxies don't relay UDP anyway.

                    } catch (_: SocketTimeoutException) {
                        // Check if control connection is still alive
                    } catch (e: Exception) {
                        if (!controlSock.isClosed) Log.d(TAG, "UDP relay error: ${e.message}")
                        break
                    }
                }
                relay.close()
            },
            "RemoteDnsProxy-UDP-$relayPort"
        ).apply { isDaemon = true }
        udpThread.start()

        // Block until the SOCKS5 TCP control connection is closed by the client
        try {
            controlSock.soTimeout = 0
            val dummy = ByteArray(1)
            while (true) {
                if (inp.read(dummy) == -1) break
            }
        } catch (_: Exception) {}

        udpThread.interrupt()
        relay.close()
    }

    // =========================================================================
    // Wraps a raw UDP payload in a SOCKS5 UDP header
    // =========================================================================

    private fun wrapSocks5Udp(dstIp: String, dstPort: Int, payload: ByteArray): ByteArray {
        val ipBytes = dstIp.split(".").map { it.toInt().and(0xFF).toByte() }.toByteArray()
        val out = ByteArray(10 + payload.size)
        // RSV=0,0  FRAG=0  ATYP=1(IPv4)
        out[3] = 0x01
        ipBytes.copyInto(out, 4)
        out[8] = (dstPort shr 8).toByte()
        out[9] = dstPort.toByte()
        payload.copyInto(out, 10)
        return out
    }

    // =========================================================================
    // Bidirectional data relay
    // =========================================================================

    private fun relay(a: Socket, b: Socket) {
        val copyAtoB = Thread {
            try { a.getInputStream().copyTo(b.getOutputStream()) } catch (_: Exception) {}
            try { b.close() } catch (_: Exception) {}
        }.apply { isDaemon = true }
        val copyBtoA = Thread {
            try { b.getInputStream().copyTo(a.getOutputStream()) } catch (_: Exception) {}
            try { a.close() } catch (_: Exception) {}
        }.apply { isDaemon = true }
        copyAtoB.start()
        copyBtoA.start()
        copyAtoB.join()
        copyBtoA.join()
    }

    // =========================================================================
    // Upstream proxy connection
    // =========================================================================

    /**
     * Opens a TCP connection through [realProxyUrl] to [host]:[port].
     * Always uses a DOMAIN-type CONNECT so DNS resolution happens on the proxy.
     */
    private fun connectToUpstream(host: String, port: Int): Socket {
        val uri  = URI(realProxyUrl)
        val scheme = uri.scheme.lowercase()
        val proxyHost = uri.host
        val proxyPort = uri.port

        val userInfo = uri.rawUserInfo
        val user: String?
        val pass: String?
        if (!userInfo.isNullOrEmpty() && ':' in userInfo) {
            val idx = userInfo.indexOf(':')
            user = URLDecoder.decode(userInfo.substring(0, idx), "UTF-8")
            pass = URLDecoder.decode(userInfo.substring(idx + 1), "UTF-8")
        } else {
            user = null
            pass = null
        }

        val sock = Socket()
        sock.connect(InetSocketAddress(proxyHost, proxyPort), 10_000)
        sock.soTimeout = 30_000

        when (scheme) {
            "socks5", "socks5h" -> socks5Handshake(sock, host, port, user, pass)
            "http"              -> httpConnectHandshake(sock, host, port, user, pass)
            else -> throw IOException("Unsupported proxy scheme: $scheme")
        }
        sock.soTimeout = 0   // back to blocking after handshake
        return sock
    }

    // SOCKS5 handshake to upstream
    private fun socks5Handshake(
        sock: Socket, host: String, port: Int,
        user: String?, pass: String?
    ) {
        val out = DataOutputStream(sock.getOutputStream())
        val inp = DataInputStream(sock.getInputStream())

        // Method negotiation
        if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02)); out.flush()
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        }
        val sVer    = inp.read()
        val method  = inp.read()
        if (sVer != 5) throw IOException("Upstream SOCKS5 bad ver=$sVer")

        if (method == 0x02) {   // username/password auth
            val ub = (user ?: "").toByteArray()
            val pb = (pass ?: "").toByteArray()
            val req = ByteArray(3 + ub.size + pb.size)
            req[0] = 0x01
            req[1] = ub.size.toByte(); ub.copyInto(req, 2)
            req[2 + ub.size] = pb.size.toByte(); pb.copyInto(req, 3 + ub.size)
            out.write(req); out.flush()
            inp.read()                     // sub-negotiation version
            if (inp.read() != 0x00) throw IOException("SOCKS5 auth failed")
        } else if (method != 0x00) {
            throw IOException("Upstream SOCKS5: no acceptable method (got $method)")
        }

        // CONNECT with DOMAIN address type (always, for remote DNS)
        val hb = host.toByteArray(Charsets.UTF_8)
        val req = ByteArray(7 + hb.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00   // VER CMD RSV
        req[3] = 0x03                                   // ATYP=DOMAIN
        req[4] = hb.size.toByte()
        hb.copyInto(req, 5)
        req[5 + hb.size] = (port shr 8).toByte()
        req[6 + hb.size] = port.toByte()
        out.write(req); out.flush()

        // Response
        if (inp.read() != 5) throw IOException("Bad SOCKS5 response")
        val rep = inp.read()
        inp.read()                         // RSV
        skipSocks5Addr(inp)                // BND.ADDR + BND.PORT
        if (rep != 0x00) throw IOException("SOCKS5 CONNECT failed: rep=$rep")
    }

    private fun skipSocks5Addr(inp: DataInputStream) {
        when (val atyp = inp.read()) {
            0x01 -> inp.skipBytes(4 + 2)
            0x03 -> { val l = inp.read(); inp.skipBytes(l + 2) }
            0x04 -> inp.skipBytes(16 + 2)
            else -> throw IOException("Bad BND.ATYP=$atyp")
        }
    }

    // HTTP CONNECT handshake to upstream
    private fun httpConnectHandshake(
        sock: Socket, host: String, port: Int,
        user: String?, pass: String?
    ) {
        val bw = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
        val br = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

        bw.write("CONNECT $host:$port HTTP/1.1\r\n")
        bw.write("Host: $host:$port\r\n")
        if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            val cred = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
            bw.write("Proxy-Authorization: Basic $cred\r\n")
        }
        bw.write("\r\n")
        bw.flush()

        val status = br.readLine() ?: throw IOException("No response from HTTP proxy")
        if (!status.contains(" 200 ") && !status.contains(" 200\r")) {
            throw IOException("HTTP CONNECT failed: $status")
        }
        // Drain remaining headers
        while (true) {
            val line = br.readLine() ?: break
            if (line.isEmpty() || line == "\r") break
        }
    }

    // =========================================================================
    // Misc helpers
    // =========================================================================

    private fun sendReply(out: DataOutputStream, rep: Int) {
        out.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun Byte.ui(): Int = this.toInt() and 0xFF

    private fun maskUrl(url: String): String =
        url.replace(Regex("(:)[^@]+(@)"), "$1***$2")
}
