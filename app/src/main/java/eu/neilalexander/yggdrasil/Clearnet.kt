package eu.neilalexander.yggdrasil

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "Clearnet"

const val KEY_CLEARNET_MODE = "clearnet_mode"
const val KEY_CLEARNET_EXITS = "clearnet_exits"

const val CLEARNET_WAN = "wan"
const val CLEARNET_EXIT = "exit"
const val CLEARNET_WAN_EXIT = "wan_exit"
const val CLEARNET_OFF = "off"

// As in the router firmware (yggmesh-clearnet)
private const val CHECK_INTERVAL_S = 30L
private const val RETRY_INTERVAL_S = 5L
private const val REPLY_TIMEOUT_MS = 5_000L
private const val FAIL_LIMIT = 3
private const val RETURN_LIMIT = 10
private const val SWITCH_PCT = 70
private const val SWITCH_MS = 30
private const val COOLDOWN_MS = 600_000L
private const val WAN_UP_LIMIT = 2

// Answers these from the exit prove its allow list, NAT and Internet access
private val CHECK_HOSTS = listOf("1.1.1.1", "8.8.8.8")
private const val ICMP_ID = 0x7967

/**
 * Settings of the clearnet (the Internet) through YggMesh exit nodes, with
 * Crypto-Key Routing, the same as on YggMesh routers. The IPv4 address of a node
 * is 100.64.0.0/10 plus the last 22 bits of its public key: an exit derives it
 * from the key and accepts the node's packets from it only, so its owner needs
 * nothing but the node's public key.
 */
object Clearnet {
    private val KEY = Regex("^[0-9a-f]{64}$")

    // Resolvers reached through the exit, as on the routers
    val dnsServers = listOf("1.0.0.1", "8.8.4.4")

    fun mode(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_CLEARNET_MODE, CLEARNET_WAN)!!

    fun setMode(context: Context, mode: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { putString(KEY_CLEARNET_MODE, mode) }
    }

    fun exits(context: Context): List<String> =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_CLEARNET_EXITS, "")!!
            .split(" ").filter { KEY.matches(it) }

    fun setExits(context: Context, exits: List<String>) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY_CLEARNET_EXITS, exits.joinToString(" "))
        }
    }

    fun isPublicKey(text: String) = KEY.matches(text)

    /** The node's IPv4 address for exits, the same as routers and exits derive. */
    fun ipv4ForKey(publicKey: String): String {
        val n = publicKey.substring(58, 64).toInt(16) and 0x3fffff
        return "100.${64 + (n shr 16)}.${(n shr 8) and 255}.${n and 255}"
    }

    /** Whether a network other than the VPN reaches the Internet. */
    fun hasWan(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.any {
            val caps = connectivity.getNetworkCapabilities(it) ?: return@any false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}

/**
 * Picks the exit and decides what the VPN routes, as yggmesh-clearnet does on
 * routers. Every [CHECK_INTERVAL_S] every exit is pinged over Yggdrasil and the
 * active one is also checked end to end, with a ping through it.
 *
 * - The active exit is kept while it works; it is dropped after [FAIL_LIMIT]
 *   failed checks in a row and not used again for [COOLDOWN_MS].
 * - Without an active exit the fastest reachable one is used.
 * - Another exit takes over only when reachable for [RETURN_LIMIT] checks in a
 *   row and clearly faster: below [SWITCH_PCT] % of the latency of the active
 *   one and at least [SWITCH_MS] ms faster.
 *
 * Unlike on routers, switching does not restart the node, so links stay up.
 * The pings go through the node itself, not through sockets: this app is left
 * out of the VPN while it routes everything, or its peerings would loop.
 */
class ClearnetMonitor(
    private val context: Context,
    private val yggdrasil: Yggdrasil,
    private val publicKey: String,
    private val address: String,
    /** The VPN has to route everything (true) or just Yggdrasil (false). */
    private val onRouteAll: (Boolean) -> Unit,
) {
    private class Exit(val key: String, val address: InetAddress) {
        var ok = false
        var streak = 0
        var rtt: Long? = null
        var badAt = 0L
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "clearnet") }
    private var next: ScheduledFuture<*>? = null
    private val ipv4 = Clearnet.ipv4ForKey(publicKey)
    private val ownIpv4 = InetAddress.getByName(ipv4)
    private val ownIpv6 = InetAddress.getByName(address)
    private var exits = emptyList<Exit>()
    private var active: Exit? = null
    private var fails = 0
    private var wanUp = false
    private var wanStreak = 0
    private var sequence = 0
    // Sent echoes waiting for an answer: sequence number to the time it was sent
    private val pending = ConcurrentHashMap<Int, Long>()
    private val answers = ConcurrentHashMap<Int, Long>()

    /** What it is doing, for the Clearnet screen. */
    @Volatile
    var status = ""
        private set

    /** The VPN routes everything now. */
    @Volatile
    var routeAll = routeAllNow(Clearnet.mode(context))
        private set

    /** An exit is carrying the IPv4 of the device: without one, IPv4 is refused at once. */
    @Volatile
    var hasExit = false
        private set

    fun start() {
        next = executor.schedule({ run() }, 0, TimeUnit.SECONDS)
    }

    /** Checks at once, as when the network or the settings change. */
    fun check() {
        executor.execute { run() }
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun routeAllNow(mode: String) = when (mode) {
        CLEARNET_EXIT, CLEARNET_OFF -> true
        CLEARNET_WAN_EXIT -> !Clearnet.hasWan(context)
        else -> false
    }

    private fun run() {
        var delay = CHECK_INTERVAL_S
        try {
            delay = tick()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        } catch (e: Exception) {
            Log.w(TAG, "Check failed: $e")
            status = "failed: ${e.javaClass.simpleName}"
        }
        next?.cancel(false)
        if (!executor.isShutdown) next = executor.schedule({ run() }, delay, TimeUnit.SECONDS)
    }

    private fun tick(): Long {
        val mode = Clearnet.mode(context)
        syncExits(if (mode == CLEARNET_EXIT || mode == CLEARNET_WAN_EXIT) Clearnet.exits(context) else emptyList())

        if (mode == CLEARNET_WAN_EXIT) updateWan() else wanStreak = 0
        val routes = when (mode) {
            CLEARNET_EXIT, CLEARNET_OFF -> true
            CLEARNET_WAN_EXIT -> !wanUp
            else -> false
        }
        if (routes != routeAll) {
            routeAll = routes
            Log.i(TAG, "Routing ${if (routes) "everything" else "only Yggdrasil"} through the VPN")
            onRouteAll(routes)
        }
        if (exits.isEmpty()) {
            status = when (mode) {
                CLEARNET_WAN -> "using the Internet of this device"
                CLEARNET_OFF -> "off: Yggdrasil only"
                else -> "no exits: add one"
            }
            return CHECK_INTERVAL_S
        }

        checkAll()
        active?.let { exit ->
            if (checkEndToEnd()) {
                fails = 0
            } else if (++fails >= FAIL_LIMIT) {
                Log.i(TAG, "Exit ${exit.key} does not work")
                exit.badAt = SystemClock.elapsedRealtime()
                deactivate()
            }
        }
        val current = active
        if (current == null) {
            val candidate = fastest(null, 1) ?: exits.filter { it.ok }.minByOrNull { it.rtt ?: Long.MAX_VALUE }
            if (candidate != null) activate(candidate)
        } else {
            val other = fastest(current, RETURN_LIMIT)
            val otherRtt = other?.rtt
            val currentRtt = current.rtt
            if (other != null && otherRtt != null && currentRtt != null &&
                otherRtt * 100 < currentRtt * SWITCH_PCT && currentRtt - otherRtt >= SWITCH_MS
            ) {
                Log.i(TAG, "Exit ${other.key} is faster ($otherRtt ms vs $currentRtt ms)")
                activate(other)
            }
        }

        val shown = active
        status = when {
            mode == CLEARNET_WAN_EXIT && wanUp ->
                "using the Internet of this device" + (shown?.let { "; exit ready: ${it.key.take(8)}" } ?: "")
            shown != null -> "exit ${shown.key.take(8)} (${shown.rtt ?: "?"} ms), IPv4 $ipv4"
            else -> "no working exit (IPv4 $ipv4)"
        }
        // Soon again while there is no exit, or right after a new one is set
        return if (shown == null || fails > 0 || shown.rtt == null) RETRY_INTERVAL_S else CHECK_INTERVAL_S
    }

    /** Follows the settings: an active exit that left the list stops being used. */
    private fun syncExits(keys: List<String>) {
        if (keys == exits.map { it.key }) return
        val old = exits.associateBy { it.key }
        exits = keys.mapNotNull { key ->
            old[key] ?: Yggdrasil.addressForKey(key)?.let { Exit(key, InetAddress.getByName(it)) }
        }
        if (active != null && active !in exits) deactivate()
    }

    private fun updateWan() {
        if (Clearnet.hasWan(context)) {
            if (++wanStreak >= WAN_UP_LIMIT || routeAll.not()) wanUp = true
        } else {
            wanStreak = 0
            wanUp = false
        }
    }

    private fun activate(exit: Exit) {
        Log.i(TAG, "Using exit ${exit.key} as $ipv4 (${exit.rtt ?: "?"} ms)")
        val error = yggdrasil.setExit("$ipv4/32", exit.key)
        if (error != null) {
            Log.w(TAG, "Setting exit ${exit.key} failed: $error")
            return
        }
        active = exit
        fails = 0
        hasExit = true
    }

    private fun deactivate() {
        active = null
        fails = 0
        hasExit = false
        yggdrasil.setExit("$ipv4/32", null)?.let { Log.w(TAG, "Clearing the exit failed: $it") }
    }

    /** Pings every exit over Yggdrasil, and keeps a smoothed latency. */
    private fun checkAll() {
        val sent = exits.associateWith { exit ->
            val seq = nextSequence()
            pending[seq] = SystemClock.elapsedRealtime()
            yggdrasil.sendBuffer(echo6(ownIpv6, exit.address, seq), echoLength6.toLong())
            seq
        }
        waitFor(sent.values)
        for ((exit, seq) in sent) {
            val sample = answers.remove(seq)
            pending.remove(seq)
            if (sample != null) {
                exit.rtt = exit.rtt?.let { (3 * it + sample) / 4 } ?: sample
                exit.ok = true
                exit.streak++
            } else {
                exit.ok = false
                exit.streak = 0
            }
        }
    }

    /** A ping through the active exit: covers its allow list, NAT and Internet access. */
    private fun checkEndToEnd(): Boolean {
        for (host in CHECK_HOSTS) {
            val seq = nextSequence()
            pending[seq] = SystemClock.elapsedRealtime()
            yggdrasil.sendBuffer(echo4(ownIpv4, InetAddress.getByName(host), seq), echoLength4.toLong())
            waitFor(listOf(seq))
            pending.remove(seq)
            if (answers.remove(seq) != null) return true
        }
        return false
    }

    private fun waitFor(sequences: Collection<Int>) {
        val until = SystemClock.elapsedRealtime() + REPLY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < until && !sequences.all { answers.containsKey(it) }) {
            Thread.sleep(50)
        }
    }

    private fun fastest(except: Exit?, minStreak: Int): Exit? {
        val now = SystemClock.elapsedRealtime()
        return exits.filter {
            it !== except && it.ok && it.streak >= minStreak && (it.badAt == 0L || now - it.badAt >= COOLDOWN_MS)
        }.minByOrNull { it.rtt ?: Long.MAX_VALUE }
    }

    private fun nextSequence(): Int {
        sequence = (sequence + 1) and 0xffff
        return sequence
    }

    /**
     * Takes the answer to one of our pings out of the packets for the TUN.
     * Returns whether the packet was one, so it should not reach the TUN.
     */
    fun onPacket(buf: ByteArray, len: Int): Boolean {
        if (len < 28) return false
        val version = (buf[0].toInt() and 0xf0) shr 4
        val (id, seq) = when {
            version == 4 && buf[9].toInt() == 1 -> {
                val icmp = (buf[0].toInt() and 0x0f) * 4
                if (len < icmp + 8 || buf[icmp].toInt() != 0) return false
                u16(buf, icmp + 4) to u16(buf, icmp + 6)
            }
            version == 6 && buf[6].toInt() == 58 && len >= 48 -> {
                if ((buf[40].toInt() and 0xff) != 129) return false
                u16(buf, 44) to u16(buf, 46)
            }
            else -> return false
        }
        if (id != ICMP_ID) return false
        val sentAt = pending[seq] ?: return true
        answers[seq] = SystemClock.elapsedRealtime() - sentAt
        return true
    }

    private fun u16(buf: ByteArray, at: Int) =
        ((buf[at].toInt() and 0xff) shl 8) or (buf[at + 1].toInt() and 0xff)
}

private const val ECHO_PAYLOAD = 16
private val echoLength4 = 20 + 8 + ECHO_PAYLOAD
private val echoLength6 = 40 + 8 + ECHO_PAYLOAD

private fun checksum(data: ByteArray, from: Int, length: Int, start: Long = 0): Int {
    var sum = start
    var i = from
    while (i + 1 < from + length) {
        sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
        i += 2
    }
    if (i < from + length) sum += (data[i].toInt() and 0xff) shl 8
    while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
    return sum.toInt().inv() and 0xffff
}

/** The ICMPv6 checksum start: the pseudo-header of src, dst, length and next header. */
private fun pseudo6(packet: ByteArray, payload: Int): Long {
    var sum = 0L
    var i = 8
    while (i < 40) {
        sum += ((packet[i].toInt() and 0xff) shl 8) or (packet[i + 1].toInt() and 0xff)
        i += 2
    }
    return sum + payload + 58
}

private fun echo4(src: InetAddress, dst: InetAddress, seq: Int): ByteArray {
    val packet = ByteBuffer.allocate(echoLength4)
        .put(0x45.toByte()).put(0).putShort(echoLength4.toShort()).putShort(seq.toShort()).putShort(0)
        .put(64).put(1).putShort(0).put(src.address).put(dst.address)
        .put(8).put(0).putShort(0).putShort(ICMP_ID.toShort()).putShort(seq.toShort())
        .array()
    ByteBuffer.wrap(packet).putShort(10, checksum(packet, 0, 20).toShort())
    ByteBuffer.wrap(packet).putShort(22, checksum(packet, 20, 8 + ECHO_PAYLOAD).toShort())
    return packet
}

private fun echo6(src: InetAddress, dst: InetAddress, seq: Int): ByteArray {
    val payload = 8 + ECHO_PAYLOAD
    val packet = ByteBuffer.allocate(echoLength6)
        .putInt(0x60000000).putShort(payload.toShort()).put(58).put(64).put(src.address).put(dst.address)
        .put(128.toByte()).put(0).putShort(0).putShort(ICMP_ID.toShort()).putShort(seq.toShort())
        .array()
    ByteBuffer.wrap(packet).putShort(42, checksum(packet, 40, payload, pseudo6(packet, payload)).toShort())
    return packet
}

/**
 * A "destination unreachable" for a packet from the TUN the device has no way
 * to send, or null when it is not an IP packet. Apps then give up on it at once,
 * and try IPv4 at once when it was IPv6, instead of waiting for a timeout.
 */
fun unreachableFor(packet: ByteArray, len: Int): ByteArray? {
    return when ((packet[0].toInt() and 0xf0) shr 4) {
        4 -> {
            if (len < 20) return null
            val quoted = minOf(len, ((packet[0].toInt() and 0x0f) * 4) + 8)
            val total = 20 + 8 + quoted
            val reply = ByteArray(total)
            val out = ByteBuffer.wrap(reply)
            out.put(0x45.toByte()).put(0).putShort(total.toShort()).putShort(0).putShort(0)
                .put(64).put(1).putShort(0).put(packet, 16, 4).put(packet, 12, 4)
                .put(3).put(13).putShort(0).putInt(0).put(packet, 0, quoted)
            ByteBuffer.wrap(reply).putShort(10, checksum(reply, 0, 20).toShort())
            ByteBuffer.wrap(reply).putShort(22, checksum(reply, 20, 8 + quoted).toShort())
            reply
        }
        6 -> {
            if (len < 40) return null
            val quoted = minOf(len, 1232)
            val payload = 8 + quoted
            val reply = ByteArray(40 + payload)
            val out = ByteBuffer.wrap(reply)
            out.putInt(0x60000000).putShort(payload.toShort()).put(58).put(64)
                .put(packet, 24, 16).put(packet, 8, 16)
                .put(1).put(1).putShort(0).putInt(0).put(packet, 0, quoted)
            ByteBuffer.wrap(reply).putShort(42, checksum(reply, 40, payload, pseudo6(reply, payload)).toShort())
            reply
        }
        else -> null
    }
}

/** Whether the IPv6 packet goes to Yggdrasil, 200::/7. */
fun isYggdrasilPacket(packet: ByteArray): Boolean = (packet[24].toInt() and 0xfe) == 0x02
