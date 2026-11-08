package eu.neilalexander.yggdrasil

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.widget.doOnTextChanged
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import eu.neilalexander.yggdrasil.mesh.NearbyMesh
import eu.neilalexander.yggdrasil.mesh.BleTransport
import eu.neilalexander.yggdrasil.mesh.PREF_KEY_NEARBY_BLE
import eu.neilalexander.yggdrasil.mesh.PREF_KEY_NEARBY_WIFI_AWARE
import eu.neilalexander.yggdrasil.mesh.WifiAwareTransport
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI


class PeersActivity : AppCompatActivity() {
    private lateinit var config: ConfigurationProxy
    private lateinit var inflater: LayoutInflater
    private lateinit var peers: Array<JSONObject>

    private lateinit var connectedTableLayout: TableLayout
    private lateinit var connectedTableLabel: TextView
    private lateinit var configuredTableLayout: TableLayout
    private lateinit var configuredTableLabel: TextView
    private lateinit var multicastListenSwitch: Switch
    private lateinit var multicastBeaconSwitch: Switch
    private lateinit var passwordEdit: EditText
    private lateinit var addPeerButton: ImageButton

    private val requestMeshPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        reconnect(this)
    }

    /**
     * A switch of one nearby transport. Greyed out where the device lacks it.
     * A change applies to the running service at once.
     */
    private fun setUpNearbySwitch(
        switchId: Int,
        labelId: Int,
        panelId: Int,
        key: String,
        labelText: Int,
        supported: Boolean,
        enabled: () -> Boolean,
    ) {
        val switch = findViewById<Switch>(switchId)
        val label = findViewById<TextView>(labelId)
        val panel = findViewById<TableRow>(panelId)
        if (!supported) {
            label.text = getString(R.string.nearby_not_supported, getString(labelText))
            label.alpha = 0.5f
            switch.isChecked = false
            switch.isEnabled = false
            panel.isEnabled = false
            return
        }
        switch.isChecked = enabled()
        switch.setOnCheckedChangeListener { _, isChecked ->
            PreferenceManager.getDefaultSharedPreferences(this).edit { putBoolean(key, isChecked) }
            val missing = NearbyMesh.missingPermissions(this)
            if (isChecked && missing.isNotEmpty()) {
                requestMeshPermissions.launch(missing)
            } else {
                reconnect(this)
            }
        }
        panel.setOnClickListener { switch.toggle() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_peers)

        config = ConfigurationProxy(applicationContext)
        inflater = LayoutInflater.from(this)
        peers = emptyArray()

        connectedTableLayout = findViewById(R.id.connectedPeersTableLayout)
        connectedTableLabel = findViewById(R.id.connectedPeersLabel)

        configuredTableLayout = findViewById(R.id.configuredPeersTableLayout)
        configuredTableLabel = findViewById(R.id.configuredPeersLabel)

        val discoveryLink = findViewById<TextView>(R.id.peers_discovery_link)
        discoveryLink.movementMethod = LinkMovementMethod.getInstance()

        multicastListenSwitch = findViewById(R.id.enableMulticastListen)
        multicastListenSwitch.setOnCheckedChangeListener { button, _ ->
            config.multicastListen = button.isChecked
        }
        multicastBeaconSwitch = findViewById(R.id.enableMulticastBeacon)
        multicastBeaconSwitch.setOnCheckedChangeListener { button, _ ->
            config.multicastBeacon = button.isChecked
        }
        multicastListenSwitch.isChecked = config.multicastListen
        multicastBeaconSwitch.isChecked = config.multicastBeacon

        val multicastBeaconPanel = findViewById<TableRow>(R.id.enableMulticastBeaconPanel)
        multicastBeaconPanel.setOnClickListener {
            multicastBeaconSwitch.toggle()
        }
        val multicastListenPanel = findViewById<TableRow>(R.id.enableMulticastListenPanel)
        multicastListenPanel.setOnClickListener {
            multicastListenSwitch.toggle()
        }
        setUpNearbySwitch(
            R.id.enableNearbyBle, R.id.enableNearbyBleLabel, R.id.enableNearbyBlePanel,
            PREF_KEY_NEARBY_BLE, R.string.nearby_over_bluetooth,
            supported = BleTransport.isSupported(this),
            enabled = { NearbyMesh.bleEnabled(this) },
        )
        setUpNearbySwitch(
            R.id.enableNearbyWifiAware, R.id.enableNearbyWifiAwareLabel, R.id.enableNearbyWifiAwarePanel,
            PREF_KEY_NEARBY_WIFI_AWARE, R.string.nearby_over_wifi_aware,
            supported = WifiAwareTransport.isSupported(this),
            enabled = { NearbyMesh.wifiAwareEnabled(this) },
        )

        passwordEdit = findViewById(R.id.passwordEdit)
        passwordEdit.setText(config.multicastPassword)

        passwordEdit.doOnTextChanged { text, _, _, _ ->
            config.multicastPassword = text.toString()
        }

        passwordEdit.setOnKeyListener { _, keyCode, _ ->
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        }

        findViewById<View>(R.id.passwordTableRow).setOnKeyListener { _, keyCode, event ->
            Log.i("Key", keyCode.toString())
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    passwordEdit.requestFocus()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        setUpPublicPeers()

        addPeerButton = findViewById(R.id.addPeerButton)
        addPeerButton.setOnClickListener {
            val view = inflater.inflate(R.layout.dialog_addpeer, null)
            val input = view.findViewById<TextInputEditText>(R.id.addPeerInput)
            val inputLayout = view.findViewById<TextInputLayout>(R.id.addPeerInputLayout)
            val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            builder.setTitle(getString(R.string.peers_add_peer))
            builder.setView(view)
            builder.setPositiveButton(getString(R.string.peers_add)) { dialog, _ ->
                config.updateJSON { json ->
                    json.getJSONArray("Peers").put(input.text.toString().trim())
                }
                dialog.dismiss()
                updateConfiguredPeers()
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            val dialog = builder.create()
            dialog.show()
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            addButton.isEnabled = false
            input.doOnTextChanged { text, _, _, _ ->
                val error = validatePeerUri(text.toString().trim())
                inputLayout?.error = error
                addButton.isEnabled = error == null && !text.isNullOrBlank()
            }
        }
    }

    /** Public peers: the yggpeers instances, how many peers to pick from them and how often. */
    private fun setUpPublicPeers() {
        setUpNumberRow(
            R.id.publicPeersCheckIntervalPanel, R.string.public_peers_check_interval, YGGPEERS_CHECK_INTERVAL_RANGE,
            { PublicPeers.checkInterval(this) }, { PublicPeers.setCheckInterval(this, it) },
        )
        setUpNumberRow(
            R.id.publicPeersRerankIntervalPanel, R.string.public_peers_rerank_interval, YGGPEERS_RERANK_INTERVAL_RANGE,
            { PublicPeers.rerankInterval(this) }, { PublicPeers.setRerankInterval(this, it) },
        )
        findViewById<TableRow>(R.id.addPublicPeersInstancePanel).setOnClickListener {
            val input = EditText(this).apply {
                setSingleLine()
                hint = DEFAULT_YGGPEERS_INSTANCE
            }
            val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            builder.setTitle(getString(R.string.public_peers_add_instance))
            builder.setMessage(getString(R.string.public_peers_instance_help))
            builder.setView(input)
            builder.setPositiveButton(getString(R.string.peers_add)) { dialog, _ ->
                val url = input.text.toString().trim().trimEnd('/')
                val instances = PublicPeers.instances(this)
                if (url !in instances) PublicPeers.setInstances(this, instances + url)
                dialog.dismiss()
                updatePublicPeers()
                reconnect(this)
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }
            val dialog = builder.create()
            dialog.show()
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            addButton.isEnabled = false
            input.doOnTextChanged { text, _, _, _ ->
                val url = text.toString().trim()
                val valid = PublicPeers.isInstanceUrl(url)
                input.error = if (valid || url.isEmpty()) null else getString(R.string.public_peers_instance_invalid)
                addButton.isEnabled = valid
            }
        }
        findViewById<TableRow>(R.id.publicPeersCountPanel).setOnClickListener {
            val choices = (0..MAX_YGGPEERS_COUNT).map {
                if (it == 0) getString(R.string.public_peers_count_off) else it.toString()
            }.toTypedArray()
            val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            builder.setTitle(getString(R.string.public_peers_count))
            builder.setSingleChoiceItems(choices, PublicPeers.count(this)) { dialog, which ->
                PublicPeers.setCount(this, which)
                dialog.dismiss()
                updatePublicPeers()
                reconnect(this)
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }
            builder.show()
        }
    }

    /** A row that asks for a whole number in [range] and applies it to the running service. */
    private fun setUpNumberRow(panelId: Int, titleId: Int, range: IntRange, current: () -> Int, save: (Int) -> Unit) {
        findViewById<TableRow>(panelId).setOnClickListener {
            val input = EditText(this).apply {
                setSingleLine()
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(current().toString())
                selectAll()
            }
            val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            builder.setTitle(getString(titleId))
            builder.setMessage(getString(R.string.public_peers_number_range, range.first, range.last))
            builder.setView(input)
            builder.setPositiveButton(getString(R.string.save)) { dialog, _ ->
                save(input.text.toString().toInt())
                dialog.dismiss()
                updatePublicPeers()
                reconnect(this)
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }
            val dialog = builder.create()
            dialog.show()
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            input.doOnTextChanged { text, _, _, _ ->
                saveButton.isEnabled = text.toString().toIntOrNull()?.let { it in range } == true
            }
        }
    }

    private fun updatePublicPeers() {
        val layout = findViewById<TableLayout>(R.id.publicPeersInstancesLayout)
        layout.removeAllViewsInLayout()
        val instances = PublicPeers.instances(this)
        layout.visibility = if (instances.isEmpty()) View.GONE else View.VISIBLE
        for (instance in instances) {
            val view = inflater.inflate(R.layout.peers_configured, null)
            view.findViewById<TextView>(R.id.addressValue).text = instance
            view.findViewById<ImageButton>(R.id.deletePeerButton).setOnClickListener {
                val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
                builder.setTitle(getString(R.string.public_peers_remove_title, instance))
                builder.setPositiveButton(getString(R.string.peers_remove)) { dialog, _ ->
                    PublicPeers.setInstances(this, PublicPeers.instances(this) - instance)
                    dialog.dismiss()
                    updatePublicPeers()
                    reconnect(this)
                }
                builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.cancel() }
                builder.show()
            }
            layout.addView(view)
        }
        val count = PublicPeers.count(this)
        findViewById<TextView>(R.id.publicPeersCountValue).text =
            if (count == 0) getString(R.string.public_peers_count_off) else count.toString()
        findViewById<TextView>(R.id.publicPeersCheckIntervalValue).text =
            PublicPeers.checkInterval(this).toString()
        findViewById<TextView>(R.id.publicPeersRerankIntervalValue).text =
            PublicPeers.rerankInterval(this).toString()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver, IntentFilter(PacketTunnelProvider.STATE_INTENT)
        )
        (application as GlobalApplication).subscribe()

        updateConfiguredPeers()
        updateConnectedPeers()
        updatePublicPeers()
    }

    override fun onPause() {
        super.onPause()
        (application as GlobalApplication).unsubscribe()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
    }

    private fun updateConfiguredPeers() {
        val peers = config.getJSON().getJSONArray("Peers")

        when (peers.length()) {
            0 -> {
                configuredTableLayout.visibility = View.GONE
                configuredTableLabel.text = getString(R.string.peers_no_configured_title)
            }
            else -> {
                configuredTableLayout.visibility = View.VISIBLE
                configuredTableLabel.text = getString(R.string.peers_configured_title)

                configuredTableLayout.removeAllViewsInLayout()
                for (i in 0 until peers.length()) {
                    val peer = peers[i].toString()
                    val view = inflater.inflate(R.layout.peers_configured, null)
                    view.findViewById<TextView>(R.id.addressValue).text = peer
                    view.findViewById<ImageButton>(R.id.deletePeerButton).tag = i

                    view.findViewById<ImageButton>(R.id.deletePeerButton).setOnClickListener { button ->
                        val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
                        builder.setTitle(getString(R.string.peers_remove_title, peer))
                        builder.setPositiveButton(getString(R.string.peers_remove)) { dialog, _ ->
                            config.updateJSON { json ->
                                json.getJSONArray("Peers").remove(button.tag as Int)
                            }
                            dialog.dismiss()
                            updateConfiguredPeers()
                        }
                        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                            dialog.cancel()
                        }
                        builder.show()
                    }
                    configuredTableLayout.addView(view)
                }
            }
        }
    }

    private fun updateConnectedPeers() {
        when (peers.size) {
            0 -> {
                connectedTableLayout.visibility = View.GONE
                connectedTableLabel.text = getString(R.string.peers_no_connected_title)
            }
            else -> {
                var connected = false
                connectedTableLayout.removeAllViewsInLayout()
                for (peer in peers) {
                    val view = inflater.inflate(R.layout.peers_connected, null)
                    val ip = peer.getString("IP")
                    // Only connected peers have IPs
                    if (ip.isNotEmpty()) {
                        view.findViewById<TextView>(R.id.addressLabel).text = ip
                        view.findViewById<TextView>(R.id.detailsLabel).text = peer.getString("URI")
                        connectedTableLayout.addView(view)
                        connected = true
                    }
                }
                if (connected) {
                    connectedTableLayout.visibility = View.VISIBLE
                    connectedTableLabel.text = getString(R.string.peers_connected_title)
                } else {
                    connectedTableLayout.visibility = View.GONE
                    connectedTableLabel.text = getString(R.string.peers_no_connected_title)
                }
            }
        }
    }

    private val validSchemes = setOf("tcp", "tls", "quic", "ws", "wss", "socks")

    private fun validatePeerUri(input: String): String? {
        if (input.isEmpty()) return null
        val uri = try {
            URI(input)
        } catch (e: Exception) {
            return getString(R.string.peer_invalid_uri)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in validSchemes) {
            return getString(R.string.peer_invalid_scheme, validSchemes.joinToString(", "))
        }
        if (scheme != "socks") {
            if (uri.host.isNullOrEmpty()) {
                return getString(R.string.peer_missing_host)
            }
            if (uri.port == -1) {
                return getString(R.string.peer_missing_port)
            }
        }
        return null
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.getStringExtra("type")) {
                "state" -> {
                    intent.getStringExtra("mesh")?.let {
                        findViewById<TextView>(R.id.nearbyMeshStatus).text = it
                    }
                    intent.getStringExtra("publicPeers")?.let {
                        findViewById<TextView>(R.id.publicPeersStatus).text = it
                    }
                    if (intent.hasExtra("peers")) {
                        val peers1 = intent.getStringExtra("peers")
                        //Log.i("PeersActivity", "Peers json: $peers1")
                        val peersArray = JSONArray(peers1 ?: "[]")
                        val array = Array(peersArray.length()) { i ->
                            peersArray.getJSONObject(i)
                        }
                        array.sortWith(compareBy { it.getString("IP") })
                        peers = array

                        updateConnectedPeers()
                    }
                }
            }
        }
    }
}