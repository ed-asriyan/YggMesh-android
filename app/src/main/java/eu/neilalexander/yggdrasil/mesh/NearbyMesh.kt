package eu.neilalexander.yggdrasil.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections

private const val TAG = "NearbyMesh"

// One switch for both transports before they got their own; the default of both now
const val PREF_KEY_NEARBY_MESH = "nearby_mesh"
const val PREF_KEY_NEARBY_BLE = "nearby_ble"
const val PREF_KEY_NEARBY_WIFI_AWARE = "nearby_wifi_aware"

const val WIFI_AWARE = "wifi-aware"
const val BLE = "ble"

// Length of the node ID that nearby transports advertise: a prefix of the
// Yggdrasil public key, short enough to fit a BLE advertisement.
const val NODE_ID_LENGTH = 8

// Time for the node to see a replaced link go before the new one comes in
private const val LINK_HANDOVER_MS = 1_000L

/**
 * Finds nearby devices running this app and peers Yggdrasil with them, with no
 * user interaction: Wi-Fi Aware when the device supports it, BLE L2CAP as a
 * fallback. Yggdrasil then routes over these links, so devices out of range of
 * each other still reach each other through the ones in between.
 *
 * Of any two nodes, the one with the lower node ID opens the link, so every
 * pair gets one link per transport and the opener can drop duplicates.
 */
class NearbyMesh(
    private val context: Context,
    publicKeyHex: String,
    private val localPort: Int,
) {
    val nodeId: ByteArray = hexToBytes(publicKeyHex).copyOf(NODE_ID_LENGTH)

    private val bridges = Collections.synchronizedSet(mutableSetOf<LinkBridge>())
    @Volatile
    private var wifiAware: WifiAwareTransport? = null
    @Volatile
    private var ble: BleTransport? = null
    @Volatile
    private var running = false

    /** One line per transport and the links, for the diagnostics in Peers. */
    fun status(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Nearby devices need Android 10+"
        val aware = wifiAware?.summary() ?: when {
            !WifiAwareTransport.isSupported(context) -> "not supported by this device"
            !wifiAwareEnabled(context) -> "off"
            !hasWifiAwarePermissions(context) -> "no permission"
            else -> "not started"
        }
        val bt = ble?.summary() ?: when {
            !BleTransport.isSupported(context) -> "not supported by this device"
            !bleEnabled(context) -> "off"
            !hasBlePermissions(context) -> "no permission"
            else -> "not started"
        }
        val links = synchronized(bridges) { bridges.groupingBy { it.transport }.eachCount() }
        return "Wi-Fi Aware: $aware\nBluetooth: $bt\n" +
            "Links: ${links[WIFI_AWARE] ?: 0} Wi-Fi Aware, ${links[BLE] ?: 0} Bluetooth\n" +
            "Node ID: ${bytesToHex(nodeId)}"
    }

    /**
     * Starts the transports that are switched on and can run, and stops the
     * ones switched off, with their links. Safe to call repeatedly.
     */
    @Synchronized
    fun refresh() {
        running = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "Nearby mesh needs Android 10+")
            return
        }
        if (wifiAwareEnabled(context) && hasWifiAwarePermissions(context)) {
            if (wifiAware == null) wifiAware = WifiAwareTransport(context, this).also { it.start() }
        } else if (wifiAware != null) {
            wifiAware?.stop()
            wifiAware = null
            closeLinks(WIFI_AWARE)
        }
        if (bleEnabled(context) && hasBlePermissions(context)) {
            if (ble == null) ble = BleTransport(context, this).also { it.start() }
        } else if (ble != null) {
            ble?.stop()
            ble = null
            closeLinks(BLE)
        }
    }

    private fun closeLinks(transport: String) =
        synchronized(bridges) { bridges.filter { it.transport == transport } }.forEach { it.close() }

    @Synchronized
    fun stop() {
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiAware?.stop()
            ble?.stop()
        }
        wifiAware = null
        ble = null
        synchronized(bridges) { bridges.toList() }.forEach { it.close() }
    }

    fun isInitiatorFor(peerId: ByteArray): Boolean {
        for (i in 0 until NODE_ID_LENGTH) {
            val a = nodeId[i].toInt() and 0xff
            val b = peerId[i].toInt() and 0xff
            if (a != b) return a < b
        }
        return false
    }

    /**
     * Whether we hold a link to the peer, over the given transport or over any.
     */
    fun hasLink(peerId: String, transport: String? = null): Boolean = synchronized(bridges) {
        bridges.any { it.peerId == peerId && (transport == null || it.transport == transport) }
    }

    /** A peer that BLE sees: look for it over Wi-Fi Aware too, unless linked there already. */
    fun seenNearby(peerId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasLink(peerId, WIFI_AWARE)) return
        wifiAware?.rediscover(peerId)
    }

    /**
     * Bridges a connected transport stream into the local Yggdrasil node.
     * [peerId] is null where the transport does not tell it. Returns false and
     * closes the stream when the local listener refuses, the mesh is stopped,
     * or a link to the peer over the transport that both sides keep exists.
     */
    fun attach(
        peerId: String?,
        transport: String,
        outbound: Boolean,
        remote: Closeable,
        input: InputStream,
        output: OutputStream,
        onClosed: () -> Unit = {},
    ): Boolean {
        val bridge = try {
            LinkBridge(peerId, transport, outbound, remote, input, output, localPort) {
                bridges.remove(it)
                onClosed()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local Yggdrasil listener unreachable on port $localPort: $e")
            try { remote.close() } catch (_: Exception) {}
            onClosed()
            return false
        }
        if (!running) {
            bridge.close()
            return false
        }
        // Yggdrasil-ng keeps one link per peer and direction: it drops a second one at
        // once. Our links all come in to its local listener, so of BLE and Wi-Fi Aware
        // only the first one up carried traffic, and BLE, up first, kept Wi-Fi Aware out
        // for good (seen between two Pixel 2). Wi-Fi Aware, much faster, takes over
        // from BLE; BLE stays off while it lives and comes back when it drops.
        if (peerId != null && transport == BLE && hasLink(peerId, WIFI_AWARE)) {
            bridge.close()
            return false
        }
        if (peerId != null && transport == WIFI_AWARE) {
            val ble = synchronized(bridges) { bridges.filter { it.peerId == peerId && it.transport == BLE } }
            if (ble.isNotEmpty()) {
                ble.forEach { it.close() }
                // Until the node sees the BLE link go, it would drop this one
                Thread.sleep(LINK_HANDOVER_MS)
            }
        }
        // When both sides opened a link, both keep the one the lower node ID opened.
        // Over Wi-Fi Aware only one side opens, so a new link replaces one that has died.
        if (peerId != null) {
            val existing = synchronized(bridges) { bridges.filter { it.peerId == peerId && it.transport == transport } }
            if (existing.isNotEmpty()) {
                val lowerOpened = outbound == isInitiatorFor(hexToBytes(peerId))
                if (!lowerOpened && transport != WIFI_AWARE) {
                    bridge.close()
                    return false
                }
                existing.forEach { it.close() }
            }
        }
        bridges.add(bridge)
        bridge.start()
        return true
    }

    companion object {
        fun wifiAwarePermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun blePermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        private fun switchedOn(context: Context, key: String): Boolean {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            return preferences.getBoolean(key, preferences.getBoolean(PREF_KEY_NEARBY_MESH, true))
        }

        /** Whether Wi-Fi Aware is switched on in Peers and the device has it. */
        fun wifiAwareEnabled(context: Context) =
            WifiAwareTransport.isSupported(context) && switchedOn(context, PREF_KEY_NEARBY_WIFI_AWARE)

        /** Whether Bluetooth is switched on in Peers and the device has BLE. */
        fun bleEnabled(context: Context) =
            BleTransport.isSupported(context) && switchedOn(context, PREF_KEY_NEARBY_BLE)

        /** Runtime permissions the switched-on transports still need, empty when all are granted. */
        fun missingPermissions(context: Context): Array<String> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return arrayOf()
            val needed = (if (wifiAwareEnabled(context)) wifiAwarePermissions() else arrayOf()) +
                (if (bleEnabled(context)) blePermissions() else arrayOf())
            return needed.distinct()
                .filter { !granted(context, it) }
                .toTypedArray()
        }

        fun hasWifiAwarePermissions(context: Context) = wifiAwarePermissions().all { granted(context, it) }

        fun hasBlePermissions(context: Context) = blePermissions().all { granted(context, it) }

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
