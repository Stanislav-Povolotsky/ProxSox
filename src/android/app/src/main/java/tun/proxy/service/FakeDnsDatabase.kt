package tun.proxy.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe bidirectional mapping between hostnames and fake IPv4 addresses.
 *
 * Allocates IPs sequentially from the configured subnet (default 10.255.0.0/16).
 * The first host address (.1) is reserved as the fake DNS server IP, so allocation
 * starts from .2. Wraps around when the subnet is exhausted.
 */
class FakeDnsDatabase(subnetCidr: String = "10.255.0.0/16") {

    private val baseIp: Int
    private val maxHosts: Int
    private val counter = AtomicInteger(1)

    // Thread-safe maps
    private val hostnameToIp = ConcurrentHashMap<String, String>()
    private val ipToHostname = ConcurrentHashMap<String, String>()

    init {
        val parts = subnetCidr.split("/")
        val ipStr = parts[0]
        val prefix = if (parts.size > 1) parts[1].toInt() else 16
        val ipBytes = ipStr.split(".").map { it.toInt() }
        baseIp = (ipBytes[0] shl 24) or (ipBytes[1] shl 16) or (ipBytes[2] shl 8) or ipBytes[3]
        maxHosts = (1 shl (32 - prefix)) - 2   // exclude network and broadcast
    }

    /** Returns true if [ip] is a fake IP allocated by this database. */
    fun isFakeIp(ip: String): Boolean = ipToHostname.containsKey(ip)

    /** Returns the original hostname for a fake IP, or null if unknown. */
    fun getHostname(fakeIp: String): String? = ipToHostname[fakeIp]

    /**
     * Returns the fake IP already allocated for [hostname], or allocates a new
     * one if none exists yet.
     */
    fun getOrAllocate(hostname: String): String {
        // Fast path: already allocated
        hostnameToIp[hostname]?.let { return it }

        // Slow path: allocate, handling the concurrent case
        synchronized(this) {
            hostnameToIp[hostname]?.let { return it }
            val hostNum = (counter.getAndIncrement() % maxHosts) + 1
            val ip = intToIp(baseIp + hostNum)
            // Update both maps atomically inside the lock
            hostnameToIp[hostname] = ip
            ipToHostname[ip] = hostname
            return ip
        }
    }

    /** Clears all mappings and resets the counter. */
    fun clear() {
        hostnameToIp.clear()
        ipToHostname.clear()
        counter.set(1)
    }

    private fun intToIp(n: Int): String =
        "${(n shr 24) and 0xFF}.${(n shr 16) and 0xFF}.${(n shr 8) and 0xFF}.${n and 0xFF}"

    /** Returns a copy of all current hostname→fakeIp mappings (for debugging). */
    fun snapshot(): Map<String, String> = HashMap(hostnameToIp)
}
