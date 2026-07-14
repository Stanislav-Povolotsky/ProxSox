package tun.proxy.model

/**
 * All protocols tun2socks can dial out through. Order here drives the
 * protocol picker in the add/edit sheet.
 */
enum class ProxyProtocol(val scheme: String) {
    HTTP("http"),
    SOCKS4("socks4"),
    SOCKS5("socks5"),
    SOCKS5H("socks5h"),
    SHADOWSOCKS("ss"),
    SSH("ssh"),
    RELAY("relay");

    companion object {
        fun fromScheme(scheme: String): ProxyProtocol =
            values().find { it.scheme == scheme } ?: HTTP
    }
}

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
    // Relay: disable Nagle's algorithm.
    val relayNoDelay: Boolean = false,
    val createdAt: Long
) {
    /** True for any SOCKS5 variant (socks5 or socks5h). */
    val isSocks5: Boolean
        get() = protocol == "socks5" || protocol == "socks5h"

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
