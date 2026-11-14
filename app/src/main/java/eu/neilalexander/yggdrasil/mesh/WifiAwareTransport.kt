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
import java.nio.ByteBuffer
import java.net.Inet6Address
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

private const val TAG = "MeshWifiAware"

private const val SERVICE_NAME = "yggdrasil-mesh"
private const val TRANSPORT = WIFI_AWARE

// Not a secret: Yggdrasil authenticates and encrypts everything end to end on its
// own. Open data paths were tried: between a Pixel 10 and a Pixel 2 they came up
// and carried nothing.
private const val PASSPHRASE = "yggdrasil-mesh-link"

private const val NETWORK_TIMEOUT_MS = 30_000
private const val RETRY_DELAY_MS = 3_000L
// A publish or subscribe the system does not answer within this means a dead session
private const val SESSION_ANSWER_MS = 10_000L
private const val MAX_SUBSCRIBE_FAILURES = 2
// After Wi-Fi Aware comes back the system recreates its interfaces: a session
// attached meanwhile dies a few seconds later (seen on a Pixel 2, Android 11)
private const val SETTLE_MS = 5_000L
// A subscribe this soon after attaching checks that the session lives
private const val PROBE_AFTER_ATTACH_MS = 15_000L
// How long the initiator waits for the responder to answer its hello
private const val READY_TIMEOUT_MS = 5_000L
// requestNetwork returns before the system has the request. A data path request
// that arrives before it is dropped, so the responder gives it this long.
private const val READY_DELAY_MS = 300L
private const val READY_LENGTH = NODE_ID_LENGTH + Short.SIZE_BYTES
private const val RESUBSCRIBE_DELAY_MS = 2 * 60_000L
private const val MIN_RESUBSCRIBE_INTERVAL_MS = 30_000L
private const val MAX_ATTEMPTS = 3
private const val HELLO_TIMEOUT_MS = 10_000
// Responder requests not yet met that we hold per peer; the oldest goes beyond
private const val MAX_PENDING_RESPONSES = 3

/**
 * Every node both publishes and subscribes to [SERVICE_NAME], carrying its node
 * ID as service-specific info. The subscriber of a pair that initiates (see
 * [NearbyMesh.isInitiatorFor]) messages its node ID to the publisher, which
 * gives the publisher the peer handle that a responder's request needs.
 * The publisher requests a NAN data path as responder and only then messages
 * back its node ID and TCP port. The subscriber then requests the data path as
 * initiator and connects to that port over the peer's link-local IPv6 address.
 *
 * The order matters: a data path request reaching a responder with no network
 * request of its own is dropped ("can't find a request") and the setup fails.
 * On a Pixel 2 a setup abandoned halfway can also wedge the Wi-Fi firmware until
 * a reboot: every later attempt fails at once with status 9003.
 *
 * The port travels in the message as nothing needs the port in the data path
 * setup. When a link closes the initiator says hello again right away.
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

    private class Initiated(val session: SubscribeDiscoverySession, val peer: PeerHandle) {
        // Null until the responder answers the hello
        var callback: ConnectivityManager.NetworkCallback? = null
    }

    // Peers we are linking to, by node ID. Releasing a callback tears the data path down.
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
                if (manager.isAvailable) {
                    handler.postDelayed({ if (manager.isAvailable) attach() }, SETTLE_MS)
                } else {
                    unavailable()
                }
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

    // The publish or subscribe waiting for the system to confirm it, see expectAnswer
    private var awaited: Any? = null
    private var subscribeFailures = 0

    /**
     * Attaches again unless the system confirms [token] in time. Right after Wi-Fi
     * came back on, a Pixel 2 (Android 11) dropped the session it had just given
     * us without telling us: every subscribe on it failed in silence
     * ("subscribeLocal: no client exists") and nothing was found for 6 minutes.
     */
    private fun expectAnswer(token: Any) {
        awaited = token
        handler.postDelayed({
            if (awaited === token && session != null && !stopped) {
                Log.w(TAG, "The system does not answer, attaching again")
                status = "session lost, attaching again"
                detach()
                attach()
            }
        }, SESSION_ANSWER_MS)
    }

    private fun answered(token: Any) {
        if (awaited === token) awaited = null
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
                handler.postDelayed({ if (session === s) subscribe() }, PROBE_AFTER_ATTACH_MS)
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
        (initiated.values.mapNotNull { it.callback } + responded).forEach { release(it) }
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
        val token = Any()
        session?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(s: PublishDiscoverySession) {
                answered(token)
                publishSession = s
            }

            override fun onSessionConfigFailed() {
                answered(token)
                Log.w(TAG, "Publishing failed")
            }

            override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                if (message.size != NODE_ID_LENGTH) return
                respond(peer, NearbyMesh.bytesToHex(message))
            }

            override fun onSessionTerminated() {
                publishSession = null
            }
        }, handler)
        expectAnswer(token)
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
        val token = Any()
        session?.subscribe(config, object : DiscoverySessionCallback() {
            private var own: SubscribeDiscoverySession? = null

            override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
                answered(token)
                subscribeFailures = 0
                own = s
                subscribeSessions.add(s)
                closeUnusedSubscribeSessions()
            }

            override fun onSessionConfigFailed() {
                answered(token)
                Log.w(TAG, "Subscribing failed")
                // A session that refuses every subscribe is broken (seen on a
                // Pixel 2 after its peer restarted): attach again
                if (++subscribeFailures >= MAX_SUBSCRIBE_FAILURES && session != null) {
                    Log.w(TAG, "Subscribing keeps failing, attaching again")
                    subscribeFailures = 0
                    detach()
                    attach()
                    return
                }
                status = "subscribing failed"
            }

            override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray, matchFilter: List<ByteArray>) {
                val discovery = own ?: return
                if (info.size != NODE_ID_LENGTH) return
                val peerId = NearbyMesh.bytesToHex(info)
                if (seen.add(peerId)) Log.i(TAG, "Found $peerId")
                if (!mesh.isInitiatorFor(info)) return
                initiate(discovery, peer, peerId)
            }

            override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                if (message.size != READY_LENGTH) return
                val port = ByteBuffer.wrap(message, NODE_ID_LENGTH, Short.SIZE_BYTES).short.toInt() and 0xffff
                requestDataPath(peer, NearbyMesh.bytesToHex(message.copyOf(NODE_ID_LENGTH)), port)
            }

            override fun onSessionTerminated() {
                own?.let { subscribeSessions.remove(it) }
            }
        }, handler)
        expectAnswer(token)
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

    /** Says hello to the peer; [requestDataPath] goes on when it answers. */
    private fun initiate(discovery: SubscribeDiscoverySession, peer: PeerHandle, peerId: String) {
        if (discovery !in subscribeSessions) return
        if (initiated.containsKey(peerId) || mesh.hasLink(peerId, TRANSPORT)) return
        val entry = Initiated(discovery, peer)
        initiated[peerId] = entry
        discovery.sendMessage(peer, messageId++, mesh.nodeId)
        handler.postDelayed({
            if (initiated[peerId] === entry && entry.callback == null) {
                Log.w(TAG, "No answer from $peerId")
                status = "no answer from ${peerId.take(8)}"
                initiated.remove(peerId)
                retry(discovery, peer, peerId)
            }
        }, READY_TIMEOUT_MS)
    }

    /** The responder holds its request now, so the data path can come up. */
    private fun requestDataPath(peer: PeerHandle, peerId: String, port: Int) {
        val entry = initiated[peerId] ?: return
        if (entry.callback != null) return
        val discovery = entry.session
        val specifier = WifiAwareNetworkSpecifier.Builder(discovery, peer)
            .setPskPassphrase(PASSPHRASE)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var connecting = false

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val info = capabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                val address = info.peerIpv6Addr
                if (address == null) {
                    Log.w(TAG, "Data path to $peerId has no peer address")
                    return
                }
                if (connecting) return
                connecting = true
                thread(name = "mesh-wifi-aware-connect") { connect(network, address, port, peerId, discovery, peer, this) }
            }

            override fun onLost(network: Network) = failed(discovery, peer, peerId, this)
            override fun onUnavailable() {
                Log.w(TAG, "Data path to $peerId unavailable")
                status = "data path to ${peerId.take(8)} failed"
                failed(discovery, peer, peerId, this)
            }
        }
        entry.callback = callback
        if (!request(specifier, callback)) initiated.remove(peerId)
    }

    private fun connect(
        network: Network,
        address: Inet6Address,
        port: Int,
        peerId: String,
        discovery: SubscribeDiscoverySession,
        peer: PeerHandle,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        try {
            val socket = network.socketFactory.createSocket(address, port)
            socket.tcpNoDelay = true
            // The opener sends its node ID first, so the responder knows the peer too
            socket.getOutputStream().apply { write(mesh.nodeId); flush() }
            handler.post { failedAttempts.remove(peerId) }
            mesh.attach(peerId, TRANSPORT, true, socket, socket.getInputStream(), socket.getOutputStream()) {
                failed(discovery, peer, peerId, callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connecting to $peerId failed: $e")
            status = "connecting to ${peerId.take(8)} failed: ${e.message}"
            failed(discovery, peer, peerId, callback)
        }
    }

    private fun failed(
        discovery: SubscribeDiscoverySession,
        peer: PeerHandle,
        peerId: String,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        handler.post {
            if (!removeInitiated(peerId, callback)) return@post
            release(callback)
            retry(discovery, peer, peerId)
        }
    }

    private fun retry(discovery: SubscribeDiscoverySession, peer: PeerHandle, peerId: String) {
        // The peer handle stays valid while the peer is in range. Once it is
        // gone, give up: a newer subscribe session finds it when it comes back.
        val attempts = (failedAttempts[peerId] ?: 0) + 1
        if (attempts >= MAX_ATTEMPTS) {
            // Look for the peer again right away: its handle may be stale. Not
            // rediscover(), whose limit is for how often BLE sees a peer.
            failedAttempts.remove(peerId)
            handler.postDelayed({ if (!initiated.containsKey(peerId)) subscribe() }, RETRY_DELAY_MS)
            return
        }
        failedAttempts[peerId] = attempts
        handler.postDelayed({ initiate(discovery, peer, peerId) }, RETRY_DELAY_MS)
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
        if (!request(specifier, callback)) {
            responded.remove(callback)
            return
        }
        handler.postDelayed({
            if (publishSession === discovery) {
                val ready = ByteBuffer.allocate(READY_LENGTH).put(mesh.nodeId).putShort(port.toShort()).array()
                discovery.sendMessage(peer, messageId++, ready)
            }
        }, READY_DELAY_MS)
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
