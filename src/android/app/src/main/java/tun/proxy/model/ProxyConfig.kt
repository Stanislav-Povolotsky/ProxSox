package tun.proxy.model

/**
 * All protocols tun2socks can dial out through -- exactly the schemes
 * registered via proxy.RegisterProtocol in the Go engine (direct/reject
 * excluded, they're not "a remote proxy server"). There is no "socks5h":
 * that was never a real tun2socks scheme, only "socks5" is registered --
 * see ProxyConfig.normalized(). Order here drives the protocol picker in
 * the add/edit sheet.
 */
enum class ProxyProtocol(val scheme: String) {
    HTTP("http"),
    SOCKS4("socks4"),
    SOCKS5("socks5"),
    SHADOWSOCKS("ss"),
    SSH("ssh"),
    RELAY("relay");

    companion object {
        fun fromScheme(scheme: String): ProxyProtocol =
            values().find { it.scheme == scheme } ?: HTTP
    }
}

/**
 * Protocols whose upstream can resolve hostnames itself, matching
 * engine/engine.go's remoteDNSProtocols map exactly -- ssh and relay are
 * not in that map, so Remote DNS must not be offered/sent for them.
 */
private val REMOTE_DNS_PROTOCOLS = setOf("http", "socks4", "socks5", "ss")

/** Whether tun2socks supports Remote DNS for this protocol scheme (see REMOTE_DNS_PROTOCOLS). */
fun protocolSupportsRemoteDns(scheme: String): Boolean = scheme in REMOTE_DNS_PROTOCOLS

/** Shadowsocks AEAD ciphers tun2socks supports (transport/shadowsocks/core.ListCipher). */
val SHADOWSOCKS_CIPHERS = listOf(
    "aes-128-gcm",
    "aes-192-gcm",
    "aes-256-gcm",
    "chacha20-ietf-poly1305",
    "xchacha20-ietf-poly1305"
)

data class ProxyConfig(
    val id: String,
    val name: String,
    val protocol: String = "socks5",
    val host: String,
    val port: Int,
    val authEnabled: Boolean = false,
    val username: String? = null,
    val password: String? = null,
    // Shadowsocks: cipher method; reuses `password` above for the ss password.
    val ssCipher: String? = null,
    // SSH: reuses `username`/`password` above for password auth; a key can be
    // used instead of, or in addition to, the password.
    val sshUseKey: Boolean = false,
    val sshPrivateKeyPem: String? = null,
    val sshPassphrase: String? = null,
    // Relay: send the relay handshake immediately (?nodelay=true) instead of
    // batching it with the first data write. Relay-protocol option, NOT TCP
    // Nagle -- Nagle is already disabled on every hop in tun2socks.
    val relayNoDelay: Boolean = false,
    // Per-config Remote DNS override: null = use global default, true = force
    // on, false = force off. Gson deserializes a missing field to null, so
    // configs saved before this field existed transparently mean "Default".
    val remoteDnsOverride: Boolean? = null,
    val createdAt: Long
) {
    /** Whether this is an ephemeral (not-yet-saved) config, e.g. pasted/imported. */
    val isSaved: Boolean
        get() = id.isNotEmpty()

    /** Whether tun2socks can hand this proxy a hostname to resolve itself (see REMOTE_DNS_PROTOCOLS). */
    val supportsRemoteDns: Boolean
        get() = protocolSupportsRemoteDns(protocol)

    /**
     * Resolves the effective Remote DNS state given the global default.
     * Always false for protocols tun2socks doesn't support it for
     * (ssh, relay), regardless of any stored override.
     */
    fun effectiveRemoteDns(globalDefault: Boolean): Boolean =
        if (!supportsRemoteDns) false else (remoteDnsOverride ?: globalDefault)

    val isSocks5: Boolean
        get() = protocol == "socks5"

    val isShadowsocks: Boolean
        get() = protocol == "ss"

    val isSsh: Boolean
        get() = protocol == "ssh"

    val isSocks4: Boolean
        get() = protocol == "socks4"

    val isRelay: Boolean
        get() = protocol == "relay"

    /** True if this config needs a private-key file materialized on disk to connect. */
    val requiresKeyFile: Boolean
        get() = isSsh && sshUseKey && !sshPrivateKeyPem.isNullOrEmpty()

    /**
     * Best-effort connection string for display and for protocols that don't
     * need any extra materialization (i.e. everything except SSH-with-key,
     * which must go through ProxyUrlBuilder so the key file exists on disk).
     */
    val proxyAddress: String
        get() = buildString {
            append("$protocol://")
            when (protocol) {
                "socks4" -> {
                    if (authEnabled && !username.isNullOrEmpty()) append("$username@")
                }
                "ss" -> {
                    val method = ssCipher ?: SHADOWSOCKS_CIPHERS.first()
                    append("$method:${password.orEmpty()}@")
                }
                "ssh" -> {
                    if (!username.isNullOrEmpty()) {
                        append(username)
                        if (authEnabled && !password.isNullOrEmpty()) append(":$password")
                        append("@")
                    }
                }
                else -> {
                    if (authEnabled && !username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                        append("$username:$password@")
                    }
                }
            }
            append("$host:$port")
            if (protocol == "relay" && relayNoDelay) append("?nodelay=true")
            // Note: SSH-with-key configs need a real file on disk for
            // privateKeyFile, so their connection string is only ever built
            // by ProxyUrlBuilder, never here -- see requiresKeyFile.
        }

    val displayAddress: String
        get() = buildString {
            append("$protocol://")
            when (protocol) {
                "socks4" -> if (authEnabled && !username.isNullOrEmpty()) append("***@")
                "ss" -> append("${ssCipher ?: SHADOWSOCKS_CIPHERS.first()}:***@")
                "ssh" -> if (!username.isNullOrEmpty()) append("$username:***@")
                else -> if (authEnabled && !username.isNullOrEmpty()) append("***@")
            }
            append("$host:$port")
            if (requiresKeyFile) append(" (key)")
        }
}

/**
 * Migrates a config loaded from storage/import/QR that predates this fix:
 * "socks5h" was never a real tun2socks scheme (only "socks5" is registered
 * in the Go engine -- connecting would fail with "unknown protocol"). Its
 * only real purpose here was "resolve hostnames via the proxy", which is
 * exactly what Remote DNS does, so it becomes plain socks5 with Remote DNS
 * forced on. A no-op for every other protocol.
 */
fun ProxyConfig.normalized(): ProxyConfig =
    if (protocol == "socks5h") copy(protocol = "socks5", remoteDnsOverride = true) else this
