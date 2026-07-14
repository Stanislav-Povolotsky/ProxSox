package tun.proxy.service

enum class VpnState {
    CONNECTING,
    CONNECTED,
    /** Tunnel is up, but the configured proxy server didn't respond to a reachability probe. */
    CONNECTED_UNVERIFIED,
    FAILED,
    DISCONNECTED
}

const val ACTION_VPN_STATE_CHANGED = "tun.proxy.VPN_STATE_CHANGED"
const val EXTRA_VPN_STATE = "vpn_state"
const val EXTRA_ERROR_MESSAGE = "error_message"
