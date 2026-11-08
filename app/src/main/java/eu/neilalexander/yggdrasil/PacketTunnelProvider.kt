package eu.neilalexander.yggdrasil

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import eu.neilalexander.yggdrasil.YggStateReceiver.Companion.YGG_STATE_INTENT
import eu.neilalexander.yggdrasil.mesh.NearbyMesh
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.NetworkInterface
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


private const val TAG = "PacketTunnelProvider"
const val SERVICE_NOTIFICATION_ID = 1000
// How often "Connected" is re-evaluated for the UI: a cheap local broadcast
private const val STATE_UPDATE_MS = 2_000

open class PacketTunnelProvider: VpnService() {
    companion object {
        const val STATE_INTENT = "eu.neilalexander.yggdrasil.PacketTunnelProvider.STATE_MESSAGE"

        const val ACTION_START = "eu.neilalexander.yggdrasil.PacketTunnelProvider.START"
        const val ACTION_STOP = "eu.neilalexander.yggdrasil.PacketTunnelProvider.STOP"
        const val ACTION_TOGGLE = "eu.neilalexander.yggdrasil.PacketTunnelProvider.TOGGLE"
        const val ACTION_CONNECT = "eu.neilalexander.yggdrasil.PacketTunnelProvider.CONNECT"
    }

    private var yggdrasil = Yggdrasil()
    private var started = AtomicBoolean()

    private lateinit var config: ConfigurationProxy

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var updateThread: Thread? = null

    private var parcel: ParcelFileDescriptor? = null
    private var readerStream: FileInputStream? = null
    private var writerStream: FileOutputStream? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nearbyMesh: NearbyMesh? = null
    private var publicPeers: PublicPeers? = null
    private var clearnet: ClearnetMonitor? = null

    override fun onCreate() {
        super.onCreate()
        config = ConfigurationProxy(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d(TAG, "Intent is null")
            return START_NOT_STICKY
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val enabled = preferences.getBoolean(PREF_KEY_ENABLED, false)
        return when (intent.action ?: ACTION_STOP) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping...")
                stop(); START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                Log.d(TAG, "Connecting...")
                if (started.get()) {
                    connect()
                } else {
                    start()
                }
                START_STICKY
            }
            ACTION_TOGGLE -> {
                Log.d(TAG, "Toggling...")
                if (started.get()) {
                    stop(); START_NOT_STICKY
                } else {
                    start(); START_STICKY
                }
            }
            else -> {
                if (!enabled) {
                    Log.d(TAG, "Service is disabled")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Starting...")
                start(); START_STICKY
            }
        }
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        val notification = createServiceNotification(this, State.Enabled)
        startForeground(notification)

        // Acquire multicast lock
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("Yggdrasil").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            yggdrasil.startJSON(config.getJSONByteArray(), networkInterfaces())
        } catch (e: Exception) {
            Log.e(TAG, "Starting Yggdrasil failed: $e")
            multicastLock?.release()
            started.set(false)
            stopForeground(true)
            stopSelf()
            return
        }

        clearnet = ClearnetMonitor(this, yggdrasil, yggdrasil.publicKeyString, yggdrasil.addressString) {
            establishTun()
        }
        if (!establishTun()) {
            stop()
            return
        }

        writerThread = thread {
            writer()
        }
        updateThread = thread {
            updater()
        }
        clearnet?.start()

        // Always there: it runs only the transports switched on in Peers, which
        // can change while the service runs (see connect()).
        nearbyMesh = NearbyMesh(this, yggdrasil.publicKeyString, config.localListenPort).also { it.refresh() }
        publicPeers = PublicPeers(this, yggdrasil) {
            val peers = config.getJSON().getJSONArray("Peers")
            (0 until peers.length()).map { peers.getString(it) }
        }.also { it.start() }

        var intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_ENABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Creates the TUN for what the clearnet mode routes now, or replaces it when
     * that changes. Returns false if the system refuses.
     */
    @Synchronized
    private fun establishTun(): Boolean {
        val routeAll = clearnet?.routeAll == true
        val address = yggdrasil.addressString
        val builder = Builder()
            .addAddress(address, 7)
            .addRoute("200::", 7)
            // We do this to trick the DNS-resolver into thinking that we have "regular" IPv6,
            // and therefore we need to resolve AAAA DNS-records.
            // See: https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/dns/net/getaddrinfo.c#1935
            // and: https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/dns/net/getaddrinfo.c#365
            // If we don't do this the DNS-resolver just doesn't do DNS-requests with record type AAAA,
            // and we can't use DNS with Yggdrasil addresses.
            .addRoute("2000::", 128)
            .allowFamily(OsConstants.AF_INET)
            .allowBypass()
            .setBlocking(true)
            .setMtu(yggdrasil.mtu.toInt())
            .setSession("Yggdrasil")
        // On Android API 29+ apps can opt-in/out to using metered networks.
        // If we don't set metered status of VPN it is considered as metered.
        // If we set it to false, then it will inherit this status from underlying network.
        // See: https://developer.android.com/reference/android/net/VpnService.Builder#setMetered(boolean)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val serverString = preferences.getString(KEY_DNS_SERVERS, "")
        if (serverString!!.isNotEmpty()) {
            val servers = serverString.split(",")
            if (servers.isNotEmpty()) {
                servers.forEach {
                    Log.i(TAG, "Using DNS server $it")
                    builder.addDnsServer(it)
                }
            }
        }
        if (preferences.getBoolean(KEY_ENABLE_CHROME_FIX, false)) {
            builder.addRoute("2001:4860:4860::8888", 128)
        }
        if (routeAll) {
            // All IPv4 goes to the exit, and IPv6 outside Yggdrasil nowhere: exits
            // carry IPv4 only, and it would show the IP of this device. The sockets
            // of this app, the peerings among them, stay outside of the VPN:
            // through it they would need Yggdrasil to reach Yggdrasil.
            builder.addAddress(Clearnet.ipv4ForKey(yggdrasil.publicKeyString), 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDisallowedApplication(packageName)
            Clearnet.dnsServers.forEach { builder.addDnsServer(it) }
        }

        val new = builder.establish()
        if (new == null || !new.fileDescriptor.valid()) return false
        val old = parcel
        parcel = new
        val input = FileInputStream(new.fileDescriptor)
        readerStream = input
        writerStream = FileOutputStream(new.fileDescriptor)
        readerThread = thread {
            reader(input)
        }
        try { old?.close() } catch (_: Exception) {}
        return true
    }

    /**
     * Before Android 13 (Wi-Fi Aware) and 12 (BLE) the nearby mesh needs
     * location, which a foreground service keeps once the app's UI is gone only
     * with the location type; without it Wi-Fi Aware calls start failing then.
     * From Android 14 the service is system exempted and needs no location.
     */
    private fun startForeground(notification: android.app.Notification) {
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else -> null
        }
        try {
            if (type != null) {
                startForeground(SERVICE_NOTIFICATION_ID, notification, type)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service of type $type refused: $e")
        }
        startForeground(SERVICE_NOTIFICATION_ID, notification)
    }

    private fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }

        clearnet?.stop()
        clearnet = null
        publicPeers?.stop()
        publicPeers = null
        nearbyMesh?.stop()
        nearbyMesh = null

        yggdrasil.stop()

        readerStream?.let {
            it.close()
            readerStream = null
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
        parcel?.let {
            it.close()
            parcel = null
        }

        readerThread?.let {
            it.interrupt()
            readerThread = null
        }
        writerThread?.let {
            it.interrupt()
            writerThread = null
        }
        updateThread?.let {
            it.interrupt()
            updateThread = null
        }

        var intent = Intent(STATE_INTENT)
        intent.putExtra("type", "state")
        intent.putExtra("started", false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_DISABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        stopForeground(true)
        stopSelf()
        multicastLock?.release()
    }

    private fun connect() {
        if (!started.get()) {
            return
        }
        yggdrasil.updateNetworkInterfaces(networkInterfaces())
        yggdrasil.retryPeersNow()
        // Picks up permissions granted since the start.
        nearbyMesh?.refresh()
        // The network changed, or the public peers settings
        publicPeers?.check()
        clearnet?.check()
    }

    /** Interfaces for multicast discovery: Android restricts their enumeration in native code. */
    private fun networkInterfaces(): String {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val result = JSONArray()
        @Suppress("DEPRECATION")
        for (network in connectivity.allNetworks) {
            // Not over Wi-Fi Aware data paths: the nearby mesh bridges those, and a
            // second, multicast peering over the same data path ran beside it.
            val transports = connectivity.getNetworkCapabilities(network)
            if (transports?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) == true) continue
            val properties = connectivity.getLinkProperties(network) ?: continue
            val name = properties.interfaceName ?: continue
            val index = try { NetworkInterface.getByName(name)?.index } catch (_: Exception) { null } ?: continue
            val addresses = JSONArray()
            properties.linkAddresses
                .map { it.address }
                .filter { it is Inet6Address && it.isLinkLocalAddress }
                .forEach { addresses.put(it.hostAddress?.substringBefore('%')) }
            if (addresses.length() == 0) continue
            result.put(JSONObject().put("Name", name).put("Index", index).put("Addrs", addresses))
        }
        return result.toString()
    }

    private fun updater() {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            return
        }
        var lastStateUpdate = System.currentTimeMillis()
        updates@ while (started.get()) {
            val treeJSON = yggdrasil.treeJSON
            if ((application as  GlobalApplication).needUiUpdates()) {
                val intent = Intent(STATE_INTENT)
                intent.putExtra("type", "state")
                intent.putExtra("started", true)
                intent.putExtra("ip", yggdrasil.addressString)
                intent.putExtra("subnet", yggdrasil.subnetString)
                intent.putExtra("pubkey", yggdrasil.publicKeyString)
                intent.putExtra("peers", yggdrasil.peersJSON)
                intent.putExtra("mesh", nearbyMesh?.status() ?: getString(R.string.nearby_mesh_off))
                intent.putExtra("publicPeers", publicPeers?.status ?: "")
                intent.putExtra("clearnet", clearnet?.status ?: "")
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
            val curTime = System.currentTimeMillis()
            if (lastStateUpdate + STATE_UPDATE_MS < curTime) {
                val intent = Intent(YGG_STATE_INTENT)
                var state = STATE_ENABLED
                if (yggdrasil.routingEntries > 0) {
                    state = STATE_CONNECTED
                }
                if (treeJSON != null && treeJSON != "null") {
                    val treeState = JSONArray(treeJSON)
                    val count = treeState.length()
                    if (count > 1)
                        state = STATE_CONNECTED
                }
                intent.putExtra("state", state)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                lastStateUpdate = curTime
            }

            if (Thread.currentThread().isInterrupted) {
                break@updates
            }
            if (sleep()) return
        }
    }

    private fun sleep(): Boolean {
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            return true
        }
        return false
    }

    private fun writer() {
        val buf = ByteArray(65535)
        writes@ while (started.get()) {
            val writerStream = writerStream
            val writerThread = writerThread
            if (writerThread == null || writerStream == null) {
                Log.i(TAG, "Write thread or stream is null")
                break@writes
            }
            if (Thread.currentThread().isInterrupted || !writerStream.fd.valid()) {
                Log.i(TAG, "Write thread interrupted or file descriptor is invalid")
                break@writes
            }
            try {
                val len = yggdrasil.recvBuffer(buf)
                if (len > 0 && clearnet?.onPacket(buf, len.toInt()) != true) {
                    writerStream.write(buf, 0, len.toInt())
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error in write: $e")
                // EINVAL: the TUN refused one malformed packet, the next ones may pass
                if (e.toString().contains("ENOBUFS") || e.toString().contains("EINVAL") ||
                    this.writerStream !== writerStream // the TUN was replaced meanwhile
                ) {
                    //TODO Check this by some error code
                    //More info about this: https://github.com/AdguardTeam/AdguardForAndroid/issues/724
                    continue
                }
                break@writes
            }
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
    }

    private fun reader(input: FileInputStream) {
        val b = ByteArray(65535)
        // Until the TUN is replaced (see establishTun)
        reads@ while (started.get() && readerStream === input) {
            if (Thread.currentThread().isInterrupted || !input.fd.valid()) {
                Log.i(TAG, "Read thread interrupted or file descriptor is invalid")
                break@reads
            }
            try {
                val n = input.read(b)
                if (n <= 0) continue
                if (refuse(b, n)) continue
                yggdrasil.sendBuffer(b, n.toLong())
            } catch (e: Exception) {
                Log.i(TAG, "Error in sendBuffer: $e")
                break@reads
            }
        }
        try { input.close() } catch (_: Exception) {}
    }

    /**
     * Answers a packet this device has no way to send while the VPN routes
     * everything: apps fail, or fall back to IPv4, at once instead of on a timeout.
     */
    private fun refuse(packet: ByteArray, len: Int): Boolean {
        val clearnet = clearnet ?: return false
        if (!clearnet.routeAll) return false
        val version = (packet[0].toInt() and 0xf0) shr 4
        val blocked = when {
            version == 4 -> !clearnet.hasExit
            version == 6 && len >= 40 -> !isYggdrasilPacket(packet)
            else -> false
        }
        if (!blocked) return false
        val reply = unreachableFor(packet, len) ?: return true
        try { writerStream?.write(reply) } catch (_: Exception) {}
        return true
    }
}
