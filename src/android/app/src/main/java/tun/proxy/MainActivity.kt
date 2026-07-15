package tun.proxy

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import tun.proxy.adapter.ConnectionLogAdapter
import tun.proxy.adapter.SavedConfigsAdapter
import tun.proxy.model.ProxyConfig
import tun.proxy.model.ProxyProtocol
import tun.proxy.model.SHADOWSOCKS_CIPHERS
import tun.proxy.model.normalized
import tun.proxy.model.protocolSupportsRemoteDns
import tun.proxy.model.ConnectionEvent
import tun.proxy.repository.ConfigRepository
import tun.proxy.repository.ConnectionLogRepository
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import tun.proxy.adapter.RotationConfigAdapter
import tun.proxy.service.ProxyRotationManager
import tun.proxy.util.ClipboardProxyDetector
import tun.proxy.util.ConfigImporter
import tun.proxy.util.ProxyHealthCheck
import tun.proxy.util.ProxyUrlBuilder
import tun.proxy.util.QrGenerator
import tun.proxy.service.ACTION_VPN_STATE_CHANGED
import tun.proxy.service.EXTRA_ERROR_MESSAGE
import tun.proxy.service.EXTRA_VPN_STATE
import tun.proxy.service.Tun2SocksVpnService
import tun.proxy.service.Tun2SocksVpnService.Companion.ACTION_STOP_SERVICE
import tun.proxy.service.VpnState
import tun.utils.Utils
import java.util.UUID

class MainActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusProgress: CircularProgressIndicator
    private lateinit var btnRecheckProxy: MaterialButton
    private lateinit var btnNew: MaterialButton
    private lateinit var btnLoad: MaterialButton
    private lateinit var containerView: View

    // Active-proxy tile
    private lateinit var activeCard: View
    private lateinit var activeFilled: View
    private lateinit var activeEmpty: View
    private lateinit var activeName: TextView
    private lateinit var activeBadge: TextView
    private lateinit var activeAddress: TextView
    private lateinit var activeMeta: TextView
    private lateinit var btnActiveMore: View

    private lateinit var utils: Utils
    private lateinit var configRepository: ConfigRepository
    private lateinit var logRepository: ConnectionLogRepository
    private var connectStartTime: Long = 0
    private val TAG = "MainActivity"
    private val VPN_REQUEST_CODE = 100
    private val REQUEST_NOTIFICATION_PERMISSION = 1231
    private val REQUEST_IMPORT_SSH_KEY = 1300
    private var intentVPNService: Intent? = null
    private val PREF_FORMATTED_CONFIG = "pref_formatted_config"
    private val PREF_ACTIVE_CONFIG = "pref_active_config_json"
    private val PREF_REMOTE_DNS = tun.proxy.service.Tun2SocksVpnService.PREF_REMOTE_DNS_ENABLED

    private var currentVpnState = VpnState.DISCONNECTED
    private var pendingProxy: String? = null
    // The proxy shown in the active tile and used when Connect is tapped.
    // Either a saved ProxyConfig or an ephemeral one (id == "") from
    // clipboard/import. Persisted as JSON so it survives restarts.
    private var activeConfig: ProxyConfig? = null
    private val gson by lazy { com.google.gson.Gson() }
    private var pendingKeyImportCallback: ((String) -> Unit)? = null
    private lateinit var clipboardDetector: ClipboardProxyDetector
    private lateinit var rotationManager: ProxyRotationManager
    private var lastDeletedConfig: ProxyConfig? = null

    // Saved configs bottom sheet reference (to dismiss when opening add/edit)
    private var savedConfigsSheet: BottomSheetDialog? = null

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stateName = intent.getStringExtra(EXTRA_VPN_STATE) ?: return
            val state = try { VpnState.valueOf(stateName) } catch (e: Exception) { return }
            val errorMsg = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
            onVpnStateChanged(state, errorMsg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        containerView = findViewById(R.id.container)
        startButton = findViewById(R.id.start)
        stopButton = findViewById(R.id.stop)
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        statusProgress = findViewById(R.id.status_progress)
        btnRecheckProxy = findViewById(R.id.btn_recheck_proxy)
        btnRecheckProxy.setOnClickListener {
            startService(Intent(this, Tun2SocksVpnService::class.java).apply {
                action = Tun2SocksVpnService.ACTION_RECHECK_PROXY
            })
        }
        btnNew = findViewById(R.id.btn_new)
        btnLoad = findViewById(R.id.btn_load)

        activeCard = findViewById(R.id.active_proxy_card)
        activeFilled = findViewById(R.id.active_filled)
        activeEmpty = findViewById(R.id.active_empty)
        activeName = findViewById(R.id.active_name)
        activeBadge = findViewById(R.id.active_badge)
        activeAddress = findViewById(R.id.active_address)
        activeMeta = findViewById(R.id.active_meta)
        btnActiveMore = findViewById(R.id.btn_active_more)

        configRepository = ConfigRepository(this)
        logRepository = ConnectionLogRepository(this)
        clipboardDetector = ClipboardProxyDetector(this)
        rotationManager = ProxyRotationManager(this)
        utils = Utils(this)
        intentVPNService = Intent(this, Tun2SocksVpnService::class.java)

        handleDeepLink(intent)

        startButton.setOnClickListener { connectActive() }
        stopButton.setOnClickListener { confirmDisconnect() }
        btnNew.setOnClickListener { showAddEditConfigSheet() }
        btnLoad.setOnClickListener { showSavedConfigsBottomSheet() }

        // Active tile: tap = edit, long-press = menu, overflow = menu.
        activeCard.setOnClickListener {
            activeConfig?.let { showAddEditConfigSheet(config = it) } ?: showAddEditConfigSheet()
        }
        activeCard.setOnLongClickListener {
            activeConfig?.let { showActiveProxyMenu(activeCard) }
            activeConfig != null
        }
        btnActiveMore.setOnClickListener {
            activeConfig?.let { showActiveProxyMenu(btnActiveMore) }
        }

        loadActiveConfig()

        if (savedInstanceState != null) {
            val savedState = savedInstanceState.getString("vpn_state", VpnState.DISCONNECTED.name)
            currentVpnState = try { VpnState.valueOf(savedState) } catch (e: Exception) { VpnState.DISCONNECTED }
            applyVpnStateToUI(currentVpnState)
        } else {
            applyVpnStateToUI(VpnState.DISCONNECTED)
            showOnboardingIfFirstRun()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("vpn_state", currentVpnState.name)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ACTION_VPN_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, filter)
        }
        // Check clipboard for proxy URLs
        checkClipboardForProxy()

        // Sync UI with actual VPN state
        val isVpnActive = Tun2SocksVpnService.isActive
        if (isVpnActive && currentVpnState == VpnState.DISCONNECTED) {
            applyVpnStateToUI(VpnState.CONNECTED)
        } else if (!isVpnActive && currentVpnState != VpnState.DISCONNECTED) {
            applyVpnStateToUI(VpnState.DISCONNECTED)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(vpnStateReceiver)
    }

    private fun onVpnStateChanged(state: VpnState, errorMsg: String?) {
        currentVpnState = state
        applyVpnStateToUI(state)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rawConfig = prefs.getString(PREF_FORMATTED_CONFIG, "") ?: ""
        val protocol = detectProtocolFromUrl(rawConfig)

        when (state) {
            VpnState.CONNECTED -> {
                connectStartTime = System.currentTimeMillis()
                logRepository.log(ConnectionEvent(System.currentTimeMillis(), "Connected", protocol))
            }
            VpnState.CONNECTED_UNVERIFIED -> {
                if (connectStartTime == 0L) connectStartTime = System.currentTimeMillis()
                logRepository.log(ConnectionEvent(System.currentTimeMillis(), "Connected (proxy unreachable)", protocol))
                Snackbar.make(containerView, R.string.status_proxy_unreachable_detail, Snackbar.LENGTH_LONG)
                    .setAction(R.string.btn_recheck) {
                        startService(Intent(this, Tun2SocksVpnService::class.java).apply {
                            action = Tun2SocksVpnService.ACTION_RECHECK_PROXY
                        })
                    }
                    .show()
            }
            VpnState.DISCONNECTED -> {
                val duration = if (connectStartTime > 0) System.currentTimeMillis() - connectStartTime else null
                connectStartTime = 0
                logRepository.log(ConnectionEvent(System.currentTimeMillis(), "Disconnected", protocol, duration = duration))
            }
            VpnState.FAILED -> {
                logRepository.log(ConnectionEvent(System.currentTimeMillis(), "Failed", protocol, error = errorMsg))
                Snackbar.make(containerView, getString(R.string.vpn_connection_failed, errorMsg ?: "Unknown error"), Snackbar.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    /** Enables/disables changing the active proxy while the tunnel is up. */
    private fun setConfigControlsEnabled(enabled: Boolean) {
        btnNew.isEnabled = enabled
        btnLoad.isEnabled = enabled
        activeCard.isClickable = enabled
        activeCard.isLongClickable = enabled
        btnActiveMore.isEnabled = enabled
        activeCard.alpha = if (enabled) 1f else 0.6f
    }

    private fun applyVpnStateToUI(state: VpnState) {
        currentVpnState = state
        val dotDrawable = statusDot.background
        btnRecheckProxy.visibility = if (state == VpnState.CONNECTED_UNVERIFIED) View.VISIBLE else View.GONE
        when (state) {
            VpnState.DISCONNECTED, VpnState.FAILED -> {
                startButton.visibility = View.VISIBLE
                stopButton.visibility = View.GONE
                setConfigControlsEnabled(true)
                statusDot.visibility = View.VISIBLE
                statusProgress.visibility = View.GONE
                if (dotDrawable is GradientDrawable) {
                    dotDrawable.setColor(ContextCompat.getColor(this, R.color.colorError))
                }
                statusText.text = getString(R.string.status_disconnected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.colorOnSurface))
            }
            VpnState.CONNECTING -> {
                startButton.visibility = View.GONE
                stopButton.visibility = View.GONE
                setConfigControlsEnabled(false)
                statusDot.visibility = View.GONE
                statusProgress.visibility = View.VISIBLE
                statusText.text = getString(R.string.status_connecting)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.colorWarning))
            }
            VpnState.CONNECTED -> {
                startButton.visibility = View.GONE
                stopButton.visibility = View.VISIBLE
                setConfigControlsEnabled(false)
                statusDot.visibility = View.VISIBLE
                statusProgress.visibility = View.GONE
                if (dotDrawable is GradientDrawable) {
                    dotDrawable.setColor(ContextCompat.getColor(this, R.color.colorSuccess))
                }
                statusText.text = getString(R.string.status_connected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess))
            }
            VpnState.CONNECTED_UNVERIFIED -> {
                // Tunnel is up (Stop still works), but the proxy didn't answer --
                // don't pretend everything is fine, but don't tear the tunnel
                // down either; let the user retry or disconnect manually.
                startButton.visibility = View.GONE
                stopButton.visibility = View.VISIBLE
                setConfigControlsEnabled(false)
                statusDot.visibility = View.VISIBLE
                statusProgress.visibility = View.GONE
                if (dotDrawable is GradientDrawable) {
                    dotDrawable.setColor(ContextCompat.getColor(this, R.color.colorWarning))
                }
                statusText.text = getString(R.string.status_connected_unverified)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.colorWarning))
            }
        }
    }

    // --- Active proxy tile ---

    private fun globalRemoteDnsDefault(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_REMOTE_DNS, true)

    // The active config JSON can carry secrets (passwords, SSH keys), so it is
    // stored encrypted, same as ConfigRepository's saved configs.
    private val activeStore by lazy {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "active_config_encrypted",
            masterKey,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun loadActiveConfig() {
        val json = activeStore.getString(PREF_ACTIVE_CONFIG, null)
        activeConfig = json?.let {
            try { gson.fromJson(it, ProxyConfig::class.java)?.normalized() } catch (e: Exception) { null }
        }
        renderActiveTile()
    }

    private fun setActiveConfig(config: ProxyConfig?) {
        activeConfig = config
        val editor = activeStore.edit()
        if (config == null) editor.remove(PREF_ACTIVE_CONFIG)
        else editor.putString(PREF_ACTIVE_CONFIG, gson.toJson(config))
        editor.apply()
        renderActiveTile()
    }

    private fun renderActiveTile() {
        val config = activeConfig
        if (config == null) {
            activeFilled.visibility = View.GONE
            activeEmpty.visibility = View.VISIBLE
            return
        }
        activeFilled.visibility = View.VISIBLE
        activeEmpty.visibility = View.GONE
        activeName.text = config.name.ifBlank { getString(R.string.active_proxy_custom_name) }
        // Short badge (SS/RELAY/SOCKS5...), matching the saved-configs list.
        // The full names (Shadowsocks/Relay) are only used in the editor dropdown.
        activeBadge.text = config.protocol.uppercase()
        activeAddress.text = config.displayAddress
        val dnsOn = config.effectiveRemoteDns(globalRemoteDnsDefault())
        activeMeta.text = getString(if (dnsOn) R.string.remote_dns_meta_on else R.string.remote_dns_meta_off)
    }

    private fun showActiveProxyMenu(anchor: View) {
        val config = activeConfig ?: return
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_active_proxy, popup.menu)
        // Delete label differs for ephemeral (just "remove from screen") vs saved.
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_connect -> { connectActive(); true }
                R.id.menu_edit -> { showAddEditConfigSheet(config = config); true }
                R.id.menu_test -> { testProxyConnection(config); true }
                R.id.menu_share -> { showQrSheet(config); true }
                R.id.menu_delete -> { deleteActiveConfig(config); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun deleteActiveConfig(config: ProxyConfig) {
        if (!config.isSaved) {
            // Ephemeral (pasted/imported) -- just clear it from the screen.
            setActiveConfig(null)
            Snackbar.make(containerView, R.string.active_cleared, Snackbar.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this, R.style.Widget_ProxSox_Dialog)
            .setTitle(R.string.delete_config_confirm_title)
            .setMessage(getString(R.string.delete_config_confirm_message, config.name))
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                configRepository.delete(config.id)
                setActiveConfig(null)
                Snackbar.make(containerView, R.string.config_deleted, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun connectActive() {
        val config = activeConfig
        if (config == null) {
            Snackbar.make(containerView, R.string.active_proxy_none_subtitle, Snackbar.LENGTH_SHORT).show()
            return
        }
        val url = ProxyUrlBuilder.build(this, config)
        startVpn(url, config.effectiveRemoteDns(globalRemoteDnsDefault()))
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(this, R.style.Widget_ProxSox_Dialog)
            .setTitle(R.string.disconnect_confirm_title)
            .setMessage(R.string.disconnect_confirm_message)
            .setPositiveButton(R.string.btn_disconnect) { _, _ -> stopVpn() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // --- Add/Edit Configuration Sheet ---

    private fun protocolDisplayName(protocol: ProxyProtocol): String = when (protocol) {
        ProxyProtocol.HTTP -> getString(R.string.badge_http)
        ProxyProtocol.SOCKS4 -> getString(R.string.badge_socks4)
        ProxyProtocol.SOCKS5 -> getString(R.string.badge_socks5)
        ProxyProtocol.SHADOWSOCKS -> getString(R.string.badge_ss)
        ProxyProtocol.SSH -> getString(R.string.badge_ssh)
        ProxyProtocol.RELAY -> getString(R.string.badge_relay)
    }

    private fun looksLikePrivateKey(text: String): Boolean =
        text.contains("-----BEGIN") && text.contains("PRIVATE KEY-----")

    private val PROTOCOLS_WITH_GENERIC_AUTH = setOf(
        ProxyProtocol.HTTP, ProxyProtocol.SOCKS5, ProxyProtocol.RELAY
    )

    fun showAddEditConfigSheet(config: ProxyConfig? = null) {
        savedConfigsSheet?.dismiss()

        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_add_config, null)

        val title = view.findViewById<TextView>(R.id.sheet_title)
        val protocolDropdown = view.findViewById<AutoCompleteTextView>(R.id.protocol_dropdown)
        val hostInput = view.findViewById<TextInputEditText>(R.id.input_host)
        val hostLayout = view.findViewById<TextInputLayout>(R.id.input_host_layout)
        val portInput = view.findViewById<TextInputEditText>(R.id.input_port)
        val portLayout = view.findViewById<TextInputLayout>(R.id.input_port_layout)

        val groupAuth = view.findViewById<View>(R.id.group_auth)
        val authSwitch = view.findViewById<SwitchMaterial>(R.id.auth_switch)
        val authFields = view.findViewById<View>(R.id.auth_fields)
        val usernameInput = view.findViewById<TextInputEditText>(R.id.input_username)
        val usernameLayout = view.findViewById<TextInputLayout>(R.id.input_username_layout)
        val passwordInput = view.findViewById<TextInputEditText>(R.id.input_password)
        val passwordLayout = view.findViewById<TextInputLayout>(R.id.input_password_layout)

        val groupSocks4 = view.findViewById<View>(R.id.group_socks4)
        val socks4UserIdInput = view.findViewById<TextInputEditText>(R.id.input_socks4_userid)

        val groupSs = view.findViewById<View>(R.id.group_ss)
        val ssCipherLayout = view.findViewById<TextInputLayout>(R.id.ss_cipher_layout)
        val ssCipherDropdown = view.findViewById<AutoCompleteTextView>(R.id.ss_cipher_dropdown)
        val ssPasswordInput = view.findViewById<TextInputEditText>(R.id.input_ss_password)
        val ssPasswordLayout = view.findViewById<TextInputLayout>(R.id.input_ss_password_layout)

        val groupSsh = view.findViewById<View>(R.id.group_ssh)
        val sshUsernameInput = view.findViewById<TextInputEditText>(R.id.input_ssh_username)
        val sshUsernameLayout = view.findViewById<TextInputLayout>(R.id.input_ssh_username_layout)
        val sshPasswordInput = view.findViewById<TextInputEditText>(R.id.input_ssh_password)
        val sshUseKeySwitch = view.findViewById<SwitchMaterial>(R.id.ssh_use_key_switch)
        val sshKeyFields = view.findViewById<View>(R.id.ssh_key_fields)
        val sshKeyInput = view.findViewById<TextInputEditText>(R.id.input_ssh_key)
        val sshKeyLayout = view.findViewById<TextInputLayout>(R.id.input_ssh_key_layout)
        val btnImportKeyFile = view.findViewById<MaterialButton>(R.id.btn_import_key_file)
        val sshPassphraseInput = view.findViewById<TextInputEditText>(R.id.input_ssh_passphrase)

        val groupRelayAdvanced = view.findViewById<View>(R.id.group_relay_advanced)
        val relayNoDelaySwitch = view.findViewById<SwitchMaterial>(R.id.relay_nodelay_switch)

        val groupRemoteDns = view.findViewById<View>(R.id.group_remote_dns)
        val remoteDnsUnsupportedHint = view.findViewById<View>(R.id.remote_dns_unsupported_hint)
        val remoteDnsToggle = view.findViewById<MaterialButtonToggleGroup>(R.id.remote_dns_toggle)

        val nameInput = view.findViewById<TextInputEditText>(R.id.input_name)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.input_name_layout)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_cancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btn_form_save)

        val isEdit = config != null
        title.text = getString(if (isEdit) R.string.edit_config_title else R.string.add_config_title)

        val protocolValues = ProxyProtocol.values()
        val protocolLabels = protocolValues.map { protocolDisplayName(it) }
        protocolDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, protocolLabels))
        ssCipherDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, SHADOWSOCKS_CIPHERS))

        fun currentProtocol(): ProxyProtocol {
            val idx = protocolLabels.indexOf(protocolDropdown.text?.toString())
            return if (idx >= 0) protocolValues[idx] else ProxyProtocol.SOCKS5
        }

        fun applyProtocolVisibility(protocol: ProxyProtocol) {
            groupAuth.visibility = if (protocol in PROTOCOLS_WITH_GENERIC_AUTH) View.VISIBLE else View.GONE
            groupSocks4.visibility = if (protocol == ProxyProtocol.SOCKS4) View.VISIBLE else View.GONE
            groupSs.visibility = if (protocol == ProxyProtocol.SHADOWSOCKS) View.VISIBLE else View.GONE
            groupSsh.visibility = if (protocol == ProxyProtocol.SSH) View.VISIBLE else View.GONE
            groupRelayAdvanced.visibility = if (protocol == ProxyProtocol.RELAY) View.VISIBLE else View.GONE

            // tun2socks can only hand a hostname to http/socks4/socks5/ss to
            // resolve themselves (engine/engine.go's remoteDNSProtocols) --
            // ssh and relay don't support it at all, so don't offer it (and
            // reset back to Default so a stale override can't linger).
            val dnsSupported = protocolSupportsRemoteDns(protocol.scheme)
            groupRemoteDns.visibility = if (dnsSupported) View.VISIBLE else View.GONE
            remoteDnsUnsupportedHint.visibility = if (dnsSupported) View.GONE else View.VISIBLE
            if (!dnsSupported) remoteDnsToggle.check(R.id.btn_dns_default)
        }

        protocolDropdown.setOnItemClickListener { _, _, _, _ -> applyProtocolVisibility(currentProtocol()) }

        val initialProtocol = when {
            config != null -> ProxyProtocol.fromScheme(config.protocol)
            else -> ProxyProtocol.SOCKS5
        }
        protocolDropdown.setText(protocolDisplayName(initialProtocol), false)
        applyProtocolVisibility(initialProtocol)
        if (ssCipherDropdown.text.isNullOrEmpty()) {
            ssCipherDropdown.setText(SHADOWSOCKS_CIPHERS.first(), false)
        }

        // Remote DNS 3-state: null=Default / true=On / false=Off
        remoteDnsToggle.check(when (config?.remoteDnsOverride) {
            true -> R.id.btn_dns_on
            false -> R.id.btn_dns_off
            null -> R.id.btn_dns_default
        })

        // Pre-fill from existing config
        if (config != null) {
            hostInput.setText(config.host)
            portInput.setText(config.port.toString())
            // Blank for an auto-generated name so the user sees the field empty
            // and only fills it if they want a custom label.
            nameInput.setText(if (config.name == autoConfigName(initialProtocol, config.host)) "" else config.name)
            when (initialProtocol) {
                ProxyProtocol.SOCKS4 -> {
                    socks4UserIdInput.setText(config.username ?: "")
                }
                ProxyProtocol.SHADOWSOCKS -> {
                    ssCipherDropdown.setText(config.ssCipher ?: SHADOWSOCKS_CIPHERS.first(), false)
                    ssPasswordInput.setText(config.password ?: "")
                }
                ProxyProtocol.SSH -> {
                    sshUsernameInput.setText(config.username ?: "")
                    sshPasswordInput.setText(config.password ?: "")
                    sshUseKeySwitch.isChecked = config.sshUseKey
                    sshKeyFields.visibility = if (config.sshUseKey) View.VISIBLE else View.GONE
                    sshKeyInput.setText(config.sshPrivateKeyPem ?: "")
                    sshPassphraseInput.setText(config.sshPassphrase ?: "")
                }
                else -> {
                    authSwitch.isChecked = config.authEnabled
                    authFields.visibility = if (config.authEnabled) View.VISIBLE else View.GONE
                    usernameInput.setText(config.username ?: "")
                    passwordInput.setText(config.password ?: "")
                    if (initialProtocol == ProxyProtocol.RELAY) {
                        relayNoDelaySwitch.isChecked = config.relayNoDelay
                    }
                }
            }
        }

        authSwitch.setOnCheckedChangeListener { _, isChecked ->
            authFields.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                usernameInput.text?.clear()
                passwordInput.text?.clear()
                usernameLayout.error = null
                passwordLayout.error = null
            }
        }

        sshUseKeySwitch.setOnCheckedChangeListener { _, isChecked ->
            sshKeyFields.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) sshKeyLayout.error = null
        }

        btnImportKeyFile.setOnClickListener {
            pendingKeyImportCallback = { content -> sshKeyInput.setText(content) }
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(
                    Intent.createChooser(intent, getString(R.string.btn_import_key_file)),
                    REQUEST_IMPORT_SSH_KEY
                )
            } catch (e: Exception) {
                pendingKeyImportCallback = null
                Snackbar.make(view, R.string.key_file_import_failed, Snackbar.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener { sheet.dismiss() }

        btnSave.setOnClickListener {
            // Clear previous errors
            hostLayout.error = null
            portLayout.error = null
            usernameLayout.error = null
            passwordLayout.error = null
            nameLayout.error = null
            ssCipherLayout.error = null
            ssPasswordLayout.error = null
            sshUsernameLayout.error = null
            sshKeyLayout.error = null

            val protocol = currentProtocol()
            val host = hostInput.text?.toString()?.trim() ?: ""
            val portStr = portInput.text?.toString()?.trim() ?: ""
            val name = nameInput.text?.toString()?.trim() ?: ""

            var valid = true
            if (host.isEmpty()) {
                hostLayout.error = getString(R.string.error_host_required)
                valid = false
            } else if (!isValidHost(host)) {
                hostLayout.error = getString(R.string.error_host_invalid)
                valid = false
            }
            val port = portStr.toIntOrNull()
            if (portStr.isEmpty()) {
                portLayout.error = getString(R.string.error_port_required)
                valid = false
            } else if (port == null || port < 1 || port > 65535) {
                portLayout.error = getString(R.string.error_port_invalid)
                valid = false
            }
            // Name is optional -- auto-generated below if left blank.

            var authEnabled = false
            var username: String? = null
            var password: String? = null
            var ssCipher: String? = null
            var sshUseKey = false
            var sshPrivateKeyPem: String? = null
            var sshPassphrase: String? = null
            var relayNoDelay = false

            when (protocol) {
                ProxyProtocol.SOCKS4 -> {
                    username = socks4UserIdInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    authEnabled = username != null
                }
                ProxyProtocol.SHADOWSOCKS -> {
                    ssCipher = ssCipherDropdown.text?.toString()?.trim()
                    if (ssCipher.isNullOrEmpty() || ssCipher !in SHADOWSOCKS_CIPHERS) {
                        ssCipherLayout.error = getString(R.string.error_cipher_required)
                        valid = false
                    }
                    password = ssPasswordInput.text?.toString()
                    if (password.isNullOrEmpty()) {
                        ssPasswordLayout.error = getString(R.string.error_ss_password_required)
                        valid = false
                    }
                    authEnabled = true
                }
                ProxyProtocol.SSH -> {
                    username = sshUsernameInput.text?.toString()?.trim()
                    if (username.isNullOrEmpty()) {
                        sshUsernameLayout.error = getString(R.string.error_ssh_username_required)
                        valid = false
                    }
                    password = sshPasswordInput.text?.toString()?.takeIf { it.isNotEmpty() }
                    sshUseKey = sshUseKeySwitch.isChecked
                    if (sshUseKey) {
                        sshPrivateKeyPem = sshKeyInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        if (sshPrivateKeyPem == null || !looksLikePrivateKey(sshPrivateKeyPem)) {
                            sshKeyLayout.error = getString(R.string.error_ssh_key_invalid)
                            valid = false
                        }
                        sshPassphrase = sshPassphraseInput.text?.toString()?.takeIf { it.isNotEmpty() }
                    }
                    authEnabled = password != null
                    if (password.isNullOrEmpty() && !sshUseKey) {
                        sshUsernameLayout.error = getString(R.string.error_ssh_auth_required)
                        valid = false
                    }
                }
                else -> {
                    authEnabled = authSwitch.isChecked
                    username = usernameInput.text?.toString()?.trim()
                    password = passwordInput.text?.toString()?.trim()
                    if (authEnabled) {
                        if (username.isNullOrEmpty()) {
                            usernameLayout.error = getString(R.string.error_username_required)
                            valid = false
                        }
                        if (password.isNullOrEmpty()) {
                            passwordLayout.error = getString(R.string.error_password_required)
                            valid = false
                        }
                    }
                    if (protocol == ProxyProtocol.RELAY) {
                        relayNoDelay = relayNoDelaySwitch.isChecked
                    }
                }
            }

            if (!valid || port == null) return@setOnClickListener

            // Defense in depth: even though the toggle is hidden+reset for
            // protocols that don't support Remote DNS, never persist an
            // override for them (effectiveRemoteDns() also clamps this).
            val remoteDnsOverride: Boolean? = if (!protocolSupportsRemoteDns(protocol.scheme)) null else {
                when (remoteDnsToggle.checkedButtonId) {
                    R.id.btn_dns_on -> true
                    R.id.btn_dns_off -> false
                    else -> null
                }
            }

            // Name is optional: fall back to an auto-generated "<PROTOCOL> <host>".
            val finalName = name.ifBlank { autoConfigName(protocol, host) }

            // Is this an edit of a config already in the repo, or a first save
            // (brand-new, pasted, or deep-link imported)? Ephemeral/imported
            // configs carry an id that isn't in the repo yet, so check presence
            // rather than just id-non-empty.
            val existingId = config?.id?.takeIf { it.isNotEmpty() && configRepository.getById(it) != null }
            val newConfig = ProxyConfig(
                id = existingId ?: UUID.randomUUID().toString(),
                name = finalName,
                protocol = protocol.scheme,
                host = host,
                port = port,
                authEnabled = authEnabled,
                username = if (authEnabled || protocol == ProxyProtocol.SSH) username else null,
                password = if (authEnabled) password else null,
                ssCipher = ssCipher,
                sshUseKey = sshUseKey,
                sshPrivateKeyPem = sshPrivateKeyPem,
                sshPassphrase = sshPassphrase,
                relayNoDelay = relayNoDelay,
                remoteDnsOverride = remoteDnsOverride,
                createdAt = config?.createdAt ?: System.currentTimeMillis()
            )

            if (existingId != null) {
                configRepository.update(newConfig)
                Snackbar.make(containerView, R.string.config_updated, Snackbar.LENGTH_SHORT).show()
            } else {
                configRepository.save(newConfig)
                Snackbar.make(containerView, R.string.config_saved, Snackbar.LENGTH_SHORT).show()
            }

            // Make the just-saved config the active one shown on the main screen.
            setActiveConfig(newConfig)

            hideKeyboard()
            sheet.dismiss()
        }

        sheet.setContentView(view)
        sheet.show()
    }

    private fun isValidHost(host: String): Boolean {
        // Accept IPv4 or simple hostnames
        val ipv4 = """^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""".toRegex()
        val match = ipv4.matchEntire(host)
        if (match != null) {
            return match.groupValues.drop(1).all { it.toInt() in 0..255 }
        }
        // Accept hostnames (letters, digits, dots, hyphens)
        return host.matches("""^[a-zA-Z0-9]([a-zA-Z0-9\-.]*[a-zA-Z0-9])?$""".toRegex())
    }

    // --- Saved Configs Bottom Sheet ---

    private fun showSavedConfigsBottomSheet() {
        val bottomSheet = BottomSheetDialog(this)
        savedConfigsSheet = bottomSheet
        val sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_saved_configs, null)

        val recyclerView = sheetView.findViewById<RecyclerView>(R.id.saved_configs_list)
        val emptyState = sheetView.findViewById<TextView>(R.id.empty_state)
        val btnAddNew = sheetView.findViewById<MaterialButton>(R.id.btn_add_new)

        val adapter = SavedConfigsAdapter(
            onUseClick = { config ->
                setActiveConfig(config)
                bottomSheet.dismiss()
            },
            onEditClick = { config ->
                showAddEditConfigSheet(config = config)
            },
            onShareClick = { config ->
                showQrSheet(config)
            },
            onDeleteClick = { config ->
                lastDeletedConfig = config
                configRepository.delete(config.id)
                if (activeConfig?.id == config.id) setActiveConfig(null)
                val updatedList = configRepository.getAll()
                (recyclerView.adapter as? SavedConfigsAdapter)?.submitList(updatedList)
                emptyState.visibility = if (updatedList.isEmpty()) View.VISIBLE else View.GONE
                recyclerView.visibility = if (updatedList.isEmpty()) View.GONE else View.VISIBLE
                Snackbar.make(sheetView, R.string.config_deleted, Snackbar.LENGTH_LONG)
                    .setAction(R.string.btn_undo) {
                        lastDeletedConfig?.let { deleted ->
                            configRepository.save(deleted)
                            val restoredList = configRepository.getAll()
                            (recyclerView.adapter as? SavedConfigsAdapter)?.submitList(restoredList)
                            emptyState.visibility = if (restoredList.isEmpty()) View.VISIBLE else View.GONE
                            recyclerView.visibility = if (restoredList.isEmpty()) View.GONE else View.VISIBLE
                        }
                        lastDeletedConfig = null
                    }
                    .show()
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val configs = configRepository.getAll()
        adapter.submitList(configs)
        emptyState.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (configs.isEmpty()) View.GONE else View.VISIBLE

        btnAddNew.setOnClickListener {
            showAddEditConfigSheet()
        }

        bottomSheet.setContentView(sheetView)
        bottomSheet.show()
    }

    // --- Proxy Parsing ---

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(containerView.windowToken, 0)
    }

    private val proxyRegex = """(?:(socks5h?|http)://)?(?:(\w+):(\w+)@)?([\w.\-]+):(\d+)""".toRegex()
    private val schemeRegex = """^([a-zA-Z][a-zA-Z0-9+.\-]*)://""".toRegex()
    private val KNOWN_PROXY_SCHEMES = setOf("http", "socks4", "socks5", "socks5h", "ss", "ssh", "relay")

    private fun detectProtocolFromUrl(url: String): String {
        val scheme = schemeRegex.find(url)?.groupValues?.get(1)?.lowercase()
        return if (scheme != null && scheme in KNOWN_PROXY_SCHEMES) scheme else "http"
    }

    /**
     * Parses a pasted/typed [user:pass@]host:port (optionally scheme-prefixed)
     * into an ephemeral ProxyConfig (id == ""). Accepts "socks5h://" as a typed
     * prefix for backward compat, but normalizes it to socks5 + Remote DNS on
     * (see ProxyConfig.normalized -- socks5h was never a real tun2socks
     * protocol). Returns null if it can't be parsed. ss/ssh/relay have
     * structured fields, so those come via the editor, not paste.
     */
    private fun adhocConfigFromUrl(input: String): ProxyConfig? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val match = proxyRegex.find(trimmed) ?: return null
        val (proxyType, proxyUser, proxyPass, proxyHost, proxyPort) = match.destructured
        val port = proxyPort.toIntOrNull() ?: return null
        if (port < 1 || port > 65535 || !isValidHost(proxyHost)) return null
        val protocol = if (proxyType.isNotEmpty()) proxyType else "http"
        val hasAuth = proxyUser.isNotEmpty() && proxyPass.isNotEmpty()
        return ProxyConfig(
            id = "",
            name = "",
            protocol = protocol,
            host = proxyHost,
            port = port,
            authEnabled = hasAuth,
            username = if (hasAuth) proxyUser else null,
            password = if (hasAuth) proxyPass else null,
            createdAt = System.currentTimeMillis()
        ).normalized()
    }

    /** Auto-generates a display name for a config the user didn't name, e.g. "SOCKS5 1.2.3.4". */
    private fun autoConfigName(protocol: ProxyProtocol, host: String): String =
        "${protocolDisplayName(protocol)} $host"

    // --- VPN Control ---

    private fun startVpn(proxy: String, remoteDns: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                this, POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION
            )
        }

        pendingProxy = proxy
        intentVPNService?.putExtra("data", proxy)
        intentVPNService?.putExtra(Tun2SocksVpnService.EXTRA_REMOTE_DNS, remoteDns)
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startService(intentVPNService)
        }

        // Store masked proxy for display/logging.
        val maskedProxy = Tun2SocksVpnService.maskProxyUrl(proxy)
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(PREF_FORMATTED_CONFIG, maskedProxy)
            .apply()

        applyVpnStateToUI(VpnState.CONNECTING)
    }

    private fun stopVpn() {
        // Disable rotation if active, so it doesn't restart the VPN
        if (rotationManager.getState().enabled) {
            rotationManager.disable()
        }
        try {
            val intent = Intent(this, Tun2SocksVpnService::class.java)
            intent.action = ACTION_STOP_SERVICE
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "stopVpn: ${e.message}")
        }
        applyVpnStateToUI(VpnState.DISCONNECTED)
    }

    // --- Navigation & Menus ---

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat, pref: Preference
    ): Boolean {
        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, pref.fragment)
        fragment.arguments = args
        @Suppress("DEPRECATION")
        fragment.setTargetFragment(caller, 0)
        supportFragmentManager.beginTransaction()
            .replace(R.id.activity_settings, fragment)
            .addToBackStack(null)
            .commit()
        title = pref.title
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val item = menu.findItem(R.id.action_activity_settings)
        item.isEnabled = currentVpnState == VpnState.DISCONNECTED
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_rotation -> showRotationSettings()
            R.id.action_test_proxy -> activeConfig?.let { testProxyConnection(it) }
                ?: Snackbar.make(containerView, R.string.active_proxy_none_subtitle, Snackbar.LENGTH_SHORT).show()
            R.id.action_connection_log -> showConnectionLog()
            R.id.action_import -> showImportDialog()
            R.id.action_activity_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_show_about -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // --- QR Code Sharing ---

    private fun showQrSheet(config: ProxyConfig) {
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_qr, null)

        view.findViewById<TextView>(R.id.qr_config_name).text = config.name
        val uri = QrGenerator.configToUri(config)
        val bitmap = QrGenerator.generateQrBitmap(uri)
        view.findViewById<ImageView>(R.id.qr_image).setImageBitmap(bitmap)

        view.findViewById<MaterialButton>(R.id.btn_share_qr).setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ProxSox Config: ${config.name}")
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share)))
        }

        sheet.setContentView(view)
        sheet.show()
    }

    // --- Proxy Health Check ---

    private fun testProxyConnection(config: ProxyConfig) {
        Snackbar.make(containerView, R.string.health_testing, Snackbar.LENGTH_SHORT).show()

        Thread {
            val result = ProxyHealthCheck.test(config.host, config.port)
            runOnUiThread {
                if (result.reachable) {
                    Snackbar.make(containerView, getString(R.string.health_success, result.latencyMs.toInt()), Snackbar.LENGTH_LONG).show()
                } else {
                    Snackbar.make(containerView, getString(R.string.health_failed, result.error ?: "Unknown"), Snackbar.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // --- Connection Log ---

    private fun showConnectionLog() {
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_log, null)

        val recyclerView = view.findViewById<RecyclerView>(R.id.log_list)
        val emptyState = view.findViewById<TextView>(R.id.log_empty_state)
        val btnClear = view.findViewById<MaterialButton>(R.id.btn_clear_log)
        val btnExport = view.findViewById<MaterialButton>(R.id.btn_export_log)

        val adapter = ConnectionLogAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val events = logRepository.getAll()
        adapter.submitList(events)
        emptyState.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE

        btnClear.setOnClickListener {
            logRepository.clear()
            adapter.submitList(emptyList())
            emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        }

        btnExport.setOnClickListener {
            exportConnectionLog()
        }

        sheet.setContentView(view)
        sheet.show()
    }

    @SuppressLint("SimpleDateFormat")
    private fun exportConnectionLog() {
        val events = logRepository.getAll()
        if (events.isEmpty()) {
            Snackbar.make(containerView, R.string.export_empty, Snackbar.LENGTH_SHORT).show()
            return
        }

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        val csv = StringBuilder()
        csv.appendLine("Timestamp,Action,Protocol,Duration (s),Error")
        for (event in events) {
            val time = dateFormat.format(java.util.Date(event.timestamp))
            val duration = event.duration?.let { it / 1000 }?.toString() ?: ""
            val error = event.error?.replace(",", ";")?.replace("\n", " ") ?: ""
            csv.appendLine("$time,${event.action},${event.protocol},$duration,$error")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_TEXT, csv.toString())
            putExtra(Intent.EXTRA_SUBJECT, "ProxSox Connection Log")
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_export)))
    }

    // --- Deep Link Import ---

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "proxsox" && uri.host == "import") {
            val config = QrGenerator.uriToConfig(uri.toString())
            if (config == null) {
                Snackbar.make(containerView, R.string.qr_import_failed, Snackbar.LENGTH_LONG).show()
                return
            }
            showAddEditConfigSheet(config = config.copy(id = UUID.randomUUID().toString()))
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    // --- Proxy Rotation ---

    private fun showRotationSettings() {
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_rotation, null)

        val rotationSwitch = view.findViewById<SwitchMaterial>(R.id.rotation_switch)
        val settingsArea = view.findViewById<View>(R.id.rotation_settings)
        val intervalSpinner = view.findViewById<Spinner>(R.id.rotation_interval_spinner)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rotation_config_list)
        val emptyState = view.findViewById<TextView>(R.id.rotation_empty)
        val btnAdd = view.findViewById<MaterialButton>(R.id.btn_add_to_rotation)
        val statusText = view.findViewById<TextView>(R.id.rotation_status)

        val state = rotationManager.getState()
        val rotationConfigs = rotationManager.getRotationConfigs().toMutableList()

        // Interval spinner
        val intervalLabels = resources.getStringArray(R.array.rotation_intervals)
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervalLabels)
        intervalSpinner.adapter = spinnerAdapter
        val currentIntervalIndex = ProxyRotationManager.INTERVAL_OPTIONS.indexOf(state.intervalMinutes)
        if (currentIntervalIndex >= 0) intervalSpinner.setSelection(currentIntervalIndex)

        // Config list adapter
        val configAdapter = RotationConfigAdapter { config ->
            rotationConfigs.remove(config)
            configAdapter_refresh(configAdapter_ref = recyclerView, configs = rotationConfigs, emptyState = emptyState)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = configAdapter
        configAdapter.submitList(rotationConfigs)
        emptyState.visibility = if (rotationConfigs.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (rotationConfigs.isEmpty()) View.GONE else View.VISIBLE

        // Switch state
        rotationSwitch.isChecked = state.enabled
        settingsArea.alpha = if (state.enabled) 1f else 0.5f

        // Show status if active
        if (state.enabled && state.nextRotationTime > System.currentTimeMillis()) {
            val remainingMs = state.nextRotationTime - System.currentTimeMillis()
            val remainingMin = (remainingMs / 60_000).toInt()
            statusText.text = getString(R.string.rotation_next_in,
                if (remainingMin > 0) "${remainingMin}m" else "<1m"
            )
            statusText.visibility = View.VISIBLE
        }

        rotationSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsArea.alpha = if (isChecked) 1f else 0.5f
            if (isChecked) {
                val ids = (recyclerView.adapter as? RotationConfigAdapter)?.getConfigIds() ?: emptyList()
                if (ids.size < 2) {
                    rotationSwitch.isChecked = false
                    Snackbar.make(view, R.string.rotation_need_configs, Snackbar.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                val intervalIndex = intervalSpinner.selectedItemPosition.coerceIn(0, ProxyRotationManager.INTERVAL_OPTIONS.size - 1)
                val interval = ProxyRotationManager.INTERVAL_OPTIONS[intervalIndex]
                rotationManager.enable(ids, interval)

                val firstConfig = rotationManager.getCurrentConfig()
                val configCount = ids.size

                // Dismiss sheet first, then start VPN on the activity
                sheet.dismiss()
                Snackbar.make(containerView, getString(R.string.rotation_started, configCount), Snackbar.LENGTH_SHORT).show()
                if (firstConfig != null) {
                    setActiveConfig(firstConfig)
                    startVpn(
                        ProxyUrlBuilder.build(this, firstConfig),
                        firstConfig.effectiveRemoteDns(globalRemoteDnsDefault())
                    )
                }
            } else {
                rotationManager.disable()
                statusText.visibility = View.GONE
                val wasActive = Tun2SocksVpnService.isActive
                sheet.dismiss()
                Snackbar.make(containerView, R.string.rotation_stopped, Snackbar.LENGTH_SHORT).show()
                // Stop the VPN after the sheet is fully dismissed
                if (wasActive) {
                    containerView.postDelayed({
                        stopVpn()
                    }, 300)
                }
                return@setOnCheckedChangeListener
            }
        }

        // Add config button
        btnAdd.setOnClickListener {
            showRotationConfigPicker(view, rotationConfigs) { picked ->
                rotationConfigs.add(picked)
                configAdapter_refresh(configAdapter_ref = recyclerView, configs = rotationConfigs, emptyState = emptyState)
            }
        }

        sheet.setContentView(view)
        sheet.show()
    }

    private fun configAdapter_refresh(configAdapter_ref: RecyclerView, configs: List<ProxyConfig>, emptyState: TextView) {
        (configAdapter_ref.adapter as? RotationConfigAdapter)?.submitList(configs)
        emptyState.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        configAdapter_ref.visibility = if (configs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showRotationConfigPicker(anchorView: View, existing: List<ProxyConfig>, onPick: (ProxyConfig) -> Unit) {
        val allConfigs = configRepository.getAll()
        val existingIds = existing.map { it.id }.toSet()
        val available = allConfigs.filter { it.id !in existingIds }

        if (available.isEmpty()) {
            Snackbar.make(anchorView, R.string.rotation_already_added, Snackbar.LENGTH_SHORT).show()
            return
        }

        val names = available.map { "${it.name} (${it.protocol.uppercase()})" }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.Widget_ProxSox_Dialog_Default)
            .setTitle(R.string.rotation_pick_title)
            .setItems(names) { _, which ->
                onPick(available[which])
            }
            .show()
    }

    // --- Clipboard Detection ---

    private fun checkClipboardForProxy() {
        if (currentVpnState != VpnState.DISCONNECTED) return
        val detected = clipboardDetector.checkClipboard() ?: return
        val adhoc = adhocConfigFromUrl(detected) ?: return
        Snackbar.make(containerView, getString(R.string.clipboard_detected), Snackbar.LENGTH_LONG)
            .setAction(R.string.clipboard_action_use) {
                setActiveConfig(adhoc)
            }
            .show()
    }

    // --- Import Configs ---

    private fun showImportDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_import, null)
        val importInput = dialogView.findViewById<TextInputEditText>(R.id.import_input)

        MaterialAlertDialogBuilder(this, R.style.Widget_ProxSox_Dialog_Default)
            .setTitle(R.string.import_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_import) { _, _ ->
                val text = importInput.text?.toString()?.trim() ?: ""
                if (text.isEmpty()) return@setPositiveButton
                val result = ConfigImporter.fromJson(text)
                if (result.configs.isEmpty()) {
                    val msg = if (result.errors.isNotEmpty()) result.errors.first() else getString(R.string.import_empty)
                    Snackbar.make(containerView, getString(R.string.import_failed, msg), Snackbar.LENGTH_LONG).show()
                } else {
                    result.configs.forEach { configRepository.save(it) }
                    if (result.errors.isEmpty()) {
                        Snackbar.make(containerView, getString(R.string.import_success, result.configs.size), Snackbar.LENGTH_LONG).show()
                    } else {
                        Snackbar.make(containerView, getString(R.string.import_partial, result.configs.size, result.configs.size + result.errors.size, result.errors.size), Snackbar.LENGTH_LONG).show()
                    }
                }
                hideKeyboard()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // --- Onboarding ---

    private fun showOnboardingIfFirstRun() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("onboarding_shown", false)) return
        prefs.edit().putBoolean("onboarding_shown", true).apply()

        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_onboarding, null)
        view.findViewById<MaterialButton>(R.id.btn_get_started).setOnClickListener {
            sheet.dismiss()
        }
        sheet.setCancelable(false)
        sheet.setContentView(view)
        sheet.show()
    }

    // --- About ---

    private fun showAbout() {
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_about, null)

        view.findViewById<TextView>(R.id.about_version).text = "v$versionName"

        view.findViewById<MaterialButton>(R.id.about_github).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_github_url))))
        }

        sheet.setContentView(view)
        sheet.show()
    }

    // --- Lifecycle Callbacks ---

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                startService(intentVPNService)
            } else {
                applyVpnStateToUI(VpnState.DISCONNECTED)
            }
        } else if (requestCode == REQUEST_IMPORT_SSH_KEY) {
            val callback = pendingKeyImportCallback
            pendingKeyImportCallback = null
            val uri = data?.data
            if (resultCode == Activity.RESULT_OK && uri != null && callback != null) {
                try {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (content.isNullOrBlank()) {
                        Snackbar.make(containerView, R.string.key_file_import_failed, Snackbar.LENGTH_SHORT).show()
                    } else {
                        callback(content)
                        Snackbar.make(containerView, R.string.key_file_imported, Snackbar.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SSH key import failed: ${e.message}")
                    Snackbar.make(containerView, R.string.key_file_import_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Snackbar.make(containerView, R.string.notification_permission_granted, Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(containerView, R.string.notification_permission_required, Snackbar.LENGTH_SHORT).show()
                startNotificationSetting()
            }
        }
    }

    private fun startNotificationSetting() {
        try {
            val intent = Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                action = "android.settings.APP_NOTIFICATION_SETTINGS"
                putExtra("app_package", applicationInfo.packageName)
                putExtra("android.provider.extra.APP_PACKAGE", applicationInfo.packageName)
                putExtra("app_uid", applicationInfo.uid)
            }
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                action = "android.settings.APPLICATION_DETAILS_SETTINGS"
                data = Uri.fromParts("package", applicationInfo.packageName, null)
            }
            startActivity(intent)
        }
    }

    private val versionName: String?
        get() = try {
            packageManager?.getPackageInfo(packageName, 0)?.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
}
