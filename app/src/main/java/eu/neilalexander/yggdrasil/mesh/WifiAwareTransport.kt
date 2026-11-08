package eu.neilalexander.yggdrasil.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.DataInputStream
import java.net.Inet6Address
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

private const val TAG = "MeshWifiAware"

private const val SERVICE_NAME = "yggdrasil-mesh"
private const val TRANSPORT = WIFI_AWARE

// Wi-Fi Aware only lets the responder announce a port over an encrypted data
// path, so the data path needs a passphrase. It is not a secret: Yggdrasil
// authenticates and encrypts everything end to end on its own. Open data paths
// were tried: between a Pixel 10 and a Pixel 2 they came up and carried nothing.
private const val PASSPHRASE = "yggdrasil-mesh-link"

private const val NETWORK_TIMEOUT_MS = 30_000
private const val RETRY_DELAY_MS = 10_000L
private const val RESUBSCRIBE_DELAY_MS = 2 * 60_000L
private const val MIN_RESUBSCRIBE_INTERVAL_MS = 30_000L
private const val MAX_ATTEMPTS = 3
private const val HELLO_TIMEOUT_MS = 10_000
// Responder requests not yet met that we hold per peer; the oldest goes beyond
private const val MAX_PENDING_RESPONSES = 3

/**
 * Every node both publishes and subscribes to [SERVICE_NAME], carrying its node
 * ID as service-specific info. When the subscriber of a pair is the initiator
 * (see [NearbyMesh.isInitiatorFor]) it messages its node ID to the publisher,
 * and both sides request a NAN data path: the publisher as responder, with a
 * TCP port, and the subscriber as initiator, which connects to that port over
 * the peer's link-local IPv6 address.
 *
 * A subscribe session reports each peer once, so a peer that left and came
 * back is found only by a newer session. A new one starts every
 * [RESUBSCRIBE_DELAY_MS], and at once when BLE sees a peer we hold no Wi-Fi
 * Aware link to. Older sessions are closed once no data path of ours uses them.
 */
@SuppressLint("MissingPermission") // NearbyMesh starts this only once permissions are granted
@RequiresApi(Build.VERSION_CODES.Q)
class WifiAwareTransport(private val context: Context, private val mesh: NearbyMesh) {
    companion object {
        fun isSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }

    private val manager = context.getSystemService(WifiAwareManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handlerThread = HandlerThread("mesh-wifi-aware").apply { start() }
    // Wi-Fi Aware calls throw when a permission goes away (location while the
    // app is in the background before Android 13); that must not take the VPN down.
    private val handler = object : Handler(handlerThread.looper) {
        override fun dispatchMessage(msg: Message) {
            try {
                super.dispatchMessage(msg)
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi Aware call failed: $e")
                status = "failed: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    // Oldest first
    private val subscribeSessions = ArrayList<SubscribeDiscoverySession>()
    private var lastSubscribe = 0L
    private var server: ServerSocket? = null
    private var stopped = false
    private var messageId = 0

    private class Initiated(val session: SubscribeDiscoverySession, val callback: ConnectivityManager.NetworkCallback)

    // Data path requests we hold, by peer node ID. Releasing one tears the data path down.
    private val initiated = ConcurrentHashMap<String, Initiated>()
    private abstract class Responder(val peerId: String) : ConnectivityManager.NetworkCallback() {
        @Volatile var available = false
    }
    // Responder requests, oldest first; touched on the handler thread only
    private val responded = ArrayList<Responder>()
    private val failedAttempts = HashMap<String, Int>()

    /** What this transport is doing, for the diagnostics in Peers. */
    @Volatile
    private var status = "starting"
    private val seen = ConcurrentHashMap.newKeySet<String>()

    fun summary(): String = "$status, ${seen.size} seen"

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.post {
                if (manager.isAvailable) attach() else unavailable()
            }
        }
    }

    fun start() {
        context.registerReceiver(stateReceiver, IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED))
        handler.post {
            server = ServerSocket(0).also { startAccepting(it) }
            if (manager.isAvailable) attach() else unavailable()
        }
    }

    fun stop() {
        try { context.unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        handler.post {
            stopped = true
            detach()
            try { server?.close() } catch (_: Exception) {}
            server = null
            handlerThread.quitSafely()
        }
    }

    private fun unavailable() {
        detach()
        status = "unavailable (Wi-Fi off, hotspot on, or location off before Android 13)"
    }

    private fun attach() {
        if (stopped || session != null) return
        manager.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                if (stopped) {
                    s.close()
                    return
                }
                Log.i(TAG, "Attached")
                status = "searching"
                session = s
                publish()
                subscribe()
                scheduleResubscribe()
            }

            override fun onAttachFailed() {
                Log.w(TAG, "Attach failed, retrying")
                status = "attach failed, retrying"
                handler.postDelayed({ attach() }, RETRY_DELAY_MS)
            }
        }, handler)
    }

    private fun detach() {
        handler.removeCallbacksAndMessages(null)
        (initiated.values.map { it.callback } + responded).forEach { release(it) }
        initiated.clear()
        responded.clear()
        failedAttempts.clear()
        publishSession?.close()
        publishSession = null
        subscribeSessions.forEach { it.close() }
        subscribeSessions.clear()
        session?.close()
        session = null
    }

    private fun publish() {
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(mesh.nodeId)
            .build()
        session?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(s: PublishDiscoverySession) {
                publishSession = s
            }

            override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                if (message.size != NODE_ID_LENGTH) return
                respond(peer, NearbyMesh.bytesToHex(message))
            }

            override fun onSessionTerminated() {
                publishSession = null
            }
        }, handler)
    }

    /** Finds the peers seen over BLE again over Wi-Fi Aware. */
    fun rediscover(peerId: String) {
        handler.post {
            if (session == null || initiated.containsKey(peerId)) return@post
            if (SystemClock.elapsedRealtime() - lastSubscribe < MIN_RESUBSCRIBE_INTERVAL_MS) return@post
            subscribe()
        }
    }

    private fun subscribe() {
        lastSubscribe = SystemClock.elapsedRealtime()
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()
        session?.subscribe(config, object : DiscoverySessionCallback() {
            private var own: SubscribeDiscoverySession? = null

            override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
                own = s
                subscribeSessions.add(s)
                closeUnusedSubscribeSessions()
            }

            override fun onSessionConfigFailed() {
                Log.w(TAG, "Subscribing failed")
                status = "subscribing failed"
            }

            override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray, matchFilter: List<ByteArray>) {
                val discovery = own ?: return
                if (info.size != NODE_ID_LENGTH) return
                if (seen.add(NearbyMesh.bytesToHex(info))) Log.i(TAG, "Found ${NearbyMesh.bytesToHex(info)}")
                if (!mesh.isInitiatorFor(info)) return
                initiate(discovery, peer, NearbyMesh.bytesToHex(info))
            }

            override fun onSessionTerminated() {
                own?.let { subscribeSessions.remove(it) }
            }
        }, handler)
    }

    // Closing a session could tear down the data paths requested through it,
    // so only the sessions without them go, and never the newest one.
    private fun closeUnusedSubscribeSessions() {
        val used = initiated.values.map { it.session }.toSet()
        subscribeSessions.dropLast(1).filter { it !in used }.forEach {
            it.close()
            subscribeSessions.remove(it)
        }
    }

    private fun scheduleResubscribe() {
        handler.postDelayed({
            subscribe()
            scheduleResubscribe()
        }, RESUBSCRIBE_DELAY_MS)
    }

    private fun removeInitiated(peerId: String, callback: ConnectivityManager.NetworkCallback): Boolean {
        val entry = initiated[peerId] ?: return false
        if (entry.callback !== callback) return false
        initiated.remove(peerId)
        return true
    }

    private fun initiate(discovery: SubscribeDiscoverySession, peer: PeerHandle, peerId: String) {
        if (discovery !in subscribeSessions) return
        if (initiated.containsKey(peerId) || mesh.hasLink(peerId, TRANSPORT)) return
        discovery.sendMessage(peer, messageId++, mesh.nodeId)

        val specifier = WifiAwareNetworkSpecifier.Builder(discovery, peer)
            .setPskPassphrase(PASSPHRASE)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var connecting = false

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val info = capabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                val address = info.peerIpv6Addr
                if (address == null || info.port <= 0) {
                    Log.w(TAG, "Data path to $peerId has no peer address or port")
                    return
                }
                if (connecting) return
                connecting = true
                thread(name = "mesh-wifi-aware-connect") { connect(network, address, info.port, peerId, this) }
            }

            override fun onLost(network: Network) = retry(discovery, peer, peerId, this)
            override fun onUnavailable() {
                Log.w(TAG, "Data path to $peerId unavailable")
                status = "data path to ${peerId.take(8)} failed"
                retry(discovery, peer, peerId, this)
            }
        }
        initiated[peerId] = Initiated(discovery, callback)
        if (!request(specifier, callback)) initiated.remove(peerId)
    }

    private fun connect(
        network: Network,
        address: Inet6Address,
        port: Int,
        peerId: String,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        try {
            val socket = network.socketFactory.createSocket(address, port)
            socket.tcpNoDelay = true
            // The opener sends its node ID first, so the responder knows the peer too
            socket.getOutputStream().apply { write(mesh.nodeId); flush() }
            handler.post { failedAttempts.remove(peerId) }
            mesh.attach(peerId, TRANSPORT, true, socket, socket.getInputStream(), socket.getOutputStream()) {
                handler.post { if (removeInitiated(peerId, callback)) release(callback) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connecting to $peerId failed: $e")
            status = "connecting to ${peerId.take(8)} failed: ${e.message}"
            handler.post { if (removeInitiated(peerId, callback)) release(callback) }
        }
    }

    private fun retry(
        discovery: SubscribeDiscoverySession,
        peer: PeerHandle,
        peerId: String,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        handler.post {
            if (!removeInitiated(peerId, callback)) return@post
            release(callback)
            // The peer handle stays valid while the peer is in range. Once it is
            // gone, give up: a newer subscribe session finds it when it comes back.
            val attempts = (failedAttempts[peerId] ?: 0) + 1
            if (attempts >= MAX_ATTEMPTS) {
                // Look for the peer again soon rather than at the next periodic
                // resubscribe, up to minutes away: its handle may be stale.
                failedAttempts.remove(peerId)
                handler.postDelayed({ rediscover(peerId) }, MIN_RESUBSCRIBE_INTERVAL_MS)
                return@post
            }
            failedAttempts[peerId] = attempts
            handler.postDelayed({ initiate(discovery, peer, peerId) }, RETRY_DELAY_MS)
        }
    }

    private fun respond(peer: PeerHandle, peerId: String) {
        val discovery = publishSession ?: return
        val port = server?.localPort ?: return
        // The initiator messages again on every retry, and each message gets a
        // request of its own: older ones are never released here. Releasing a
        // request tore down the data path that was just coming up through it
        // (seen on a Pixel 2, 10 ms after the setup), and keeping only one left
        // the retry unmatched after the system dropped a failed one silently
        // ("can't find a request"). Requests not met time out by themselves.
        // Not even a request with a data path stops a new one: the peer messages
        // again only when it has no link, and a data path this side still holds
        // may be gone on the other (seen on a Pixel 2, which then never answered).
        responded.filter { it.peerId == peerId && !it.available }
            .dropLast(MAX_PENDING_RESPONSES - 1)
            .forEach { forget(it) }

        val specifier = WifiAwareNetworkSpecifier.Builder(discovery, peer)
            .setPskPassphrase(PASSPHRASE)
            .setPort(port)
            .build()
        val callback = object : Responder(peerId) {
            override fun onAvailable(network: Network) { available = true }
            override fun onLost(network: Network) {
                handler.post { forget(this) }
            }
            override fun onUnavailable() {
                handler.post {
                    if (responded.none { it.peerId == peerId && it !== this }) {
                        status = "data path from ${peerId.take(8)} failed"
                    }
                    forget(this)
                }
            }
        }
        status = "answering ${peerId.take(8)}"
        responded.add(callback)
        if (!request(specifier, callback)) responded.remove(callback)
    }

    private fun forget(responder: Responder) {
        if (responded.remove(responder)) release(responder)
    }

    /** Whether the request was made; when not, nothing will call [callback]. */
    private fun request(specifier: WifiAwareNetworkSpecifier, callback: ConnectivityManager.NetworkCallback): Boolean {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        return try {
            connectivity.requestNetwork(request, callback, NETWORK_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Data path request failed: $e")
            status = "data path request failed: ${e.javaClass.simpleName}"
            false
        }
    }

    private fun release(callback: ConnectivityManager.NetworkCallback) {
        try { connectivity.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }

    private fun startAccepting(server: ServerSocket) {
        thread(name = "mesh-wifi-aware-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                // Only peers on a Wi-Fi Aware data path may link through here.
                if (!socket.inetAddress.isLinkLocalAddress) {
                    socket.close()
                    continue
                }
                socket.tcpNoDelay = true
                thread(name = "mesh-wifi-aware-hello") {
                    try {
                        socket.soTimeout = HELLO_TIMEOUT_MS
                        val id = ByteArray(NODE_ID_LENGTH)
                        DataInputStream(socket.getInputStream()).readFully(id)
                        socket.soTimeout = 0
                        mesh.attach(NearbyMesh.bytesToHex(id), TRANSPORT, false, socket, socket.getInputStream(), socket.getOutputStream())
                    } catch (e: Exception) {
                        Log.w(TAG, "Inbound link failed: $e")
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            }
        }
    }
}
