package tun.proxy.util

import android.content.Context
import android.util.Log
import tun.proxy.model.ProxyConfig
import java.io.File
import java.net.URLEncoder

/**
 * Builds the final tun2socks proxy:// connection string for a saved config,
 * right before connecting.
 *
 * This is a separate step from [ProxyConfig.proxyAddress] because SSH
 * with a private key needs a real file on disk: tun2socks's ssh proxy
 * reads the key via `?privateKeyFile=<path>` (a plain os.ReadFile on the Go
 * side) -- it has no way to take key material embedded in the URL itself.
 */
object ProxyUrlBuilder {
    private const val TAG = "ProxyUrlBuilder"
    private const val KEY_FILE_NAME = "ssh_key_tmp.pem"

    /**
     * Returns the connection string to hand to Engine.key.proxy. For
     * SSH-with-key configs this (re-)writes the private key to a file in
     * the app's private cache dir. tun2socks reads it once, synchronously,
     * during Engine.start() (proxy parsing happens before the netstack
     * comes up), so it only needs to exist for that instant -- call
     * [clearKeyFile] once Engine.start() returns.
     */
    fun build(context: Context, config: ProxyConfig): String {
        if (!config.requiresKeyFile) {
            return config.proxyAddress
        }

        val keyFile = keyFile(context)
        keyFile.writeText(config.sshPrivateKeyPem.orEmpty())
        // Belt and suspenders: it's already in app-private storage, but
        // make sure nothing else on the (possibly rooted) device can read it.
        keyFile.setReadable(false, false)
        keyFile.setReadable(true, true)
        keyFile.setWritable(false, false)
        keyFile.setWritable(true, true)

        return buildString {
            append("ssh://")
            if (!config.username.isNullOrEmpty()) {
                append(config.username)
                if (config.authEnabled && !config.password.isNullOrEmpty()) {
                    append(":${config.password}")
                }
                append("@")
            }
            append("${config.host}:${config.port}")
            append("?privateKeyFile=${URLEncoder.encode(keyFile.absolutePath, "UTF-8")}")
            if (!config.sshPassphrase.isNullOrEmpty()) {
                append("&passphrase=${URLEncoder.encode(config.sshPassphrase, "UTF-8")}")
            }
        }
    }

    /** Deletes the transient private-key file, if any. Safe to call unconditionally. */
    fun clearKeyFile(context: Context) {
        val f = keyFile(context)
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "failed to delete transient SSH key file")
        }
    }

    private fun keyFile(context: Context) = File(context.cacheDir, KEY_FILE_NAME)
}
