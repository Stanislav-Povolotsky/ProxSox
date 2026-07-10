package tun.proxy.model

data class ProxyConfig(
    val id: String,
    val name: String,
    val protocol: String = "socks5",
    val host: String,
    val port: Int,
    val authEnabled: Boolean = false,
    val username: String? = null,
    val password: String? = null,
    val createdAt: Long
) {
    val proxyAddress: String
        get() = buildString {
            append("$protocol://")
            if (authEnabled && !username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                append("$username:$password@")
            }
            append("$host:$port")
        }

    val displayAddress: String
        get() = buildString {
            append("$protocol://")
            if (authEnabled && !username.isNullOrEmpty()) {
                append("***@")
            }
            append("$host:$port")
        }

    /** True for any SOCKS5 variant (socks5 or socks5h). */
    val isSocks5: Boolean
        get() = protocol == "socks5" || protocol == "socks5h"
}
