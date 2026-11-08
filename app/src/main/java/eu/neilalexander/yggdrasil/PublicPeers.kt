package eu.neilalexander.yggdrasil

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.URI
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "PublicPeers"

const val KEY_YGGPEERS_INSTANCES = "yggpeers_instances"
const val KEY_YGGPEERS_COUNT = "yggpeers_count"
const val KEY_YGGPEERS_CHECK_INTERVAL = "yggpeers_check_interval"
const val KEY_YGGPEERS_RERANK_INTERVAL = "yggpeers_rerank_interval"
private const val KEY_AUTO_PEERS = "yggpeers_auto_peers"
private const val KEY_SELECTED_AT = "yggpeers_selected_at"
// The instances the peers were picked from: picked again when they change
private const val KEY_SELECTED_FROM = "yggpeers_selected_from"

const val DEFAULT_YGGPEERS_INSTANCE = "https://yggpeers.fsky.io"
const val DEFAULT_YGGPEERS_COUNT = 2
const val MAX_YGGPEERS_COUNT = 10
// Minutes between checks of the picked peers. Any value works, unlike in the
// router firmware, which needs one cron can express.
const val DEFAULT_YGGPEERS_CHECK_INTERVAL = 30
val YGGPEERS_CHECK_INTERVAL_RANGE = 1..1440
// Days between forced refreshes of the picked peers, as in the router firmware
const val DEFAULT_YGGPEERS_RERANK_INTERVAL = 7
val YGGPEERS_RERANK_INTERVAL_RANGE = 1..90

private const val FIRST_CHECK_DELAY_S = 5L
private const val RECHECK_DOWN_MS = 30_000L
private const val FETCH_TIMEOUT_MS = 20_000
private const val PING_TIMEOUT_S = 10L
private const val PROBE_PARALLEL = 16

// Preference of the schemes, best first
private val SCHEMES = listOf("tls", "quic", "tcp")

/**
 * Picks public Yggdrasil peers by itself, as YggMesh routers do
 * (yggmesh-fetch-peers): candidates come from yggpeers instances
 * (https://gitfield.org/fsky/yggpeers), only those a majority of the
 * instances report up are kept, one URI per host, ranked by latency, and the
 * best ones are added to the running node, next to the configured peers.
 *
 * The peers change at runtime: the node is never restarted, so nearby links
 * stay up. Every check interval (and when the network or the settings change)
 * the picked peers are checked; a peer still down after [RECHECK_DOWN_MS] is
 * replaced, and the whole list is picked again every forced refresh interval. A device without
 * Internet of its own skips all this: it reaches Yggdrasil through its
 * neighbours.
 */
class PublicPeers(
    private val context: Context,
    private val yggdrasil: Yggdrasil,
    /** The configured peers: their hosts are not picked again. */
    private val configuredPeers: () -> List<String>,
) {
    companion object {
        /** The yggpeers instances, the default one unless changed. Empty: switched off. */
        fun instances(context: Context): List<String> {
            val value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(KEY_YGGPEERS_INSTANCES, null) ?: return listOf(DEFAULT_YGGPEERS_INSTANCE)
            return value.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        }

        fun setInstances(context: Context, instances: List<String>) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(KEY_YGGPEERS_INSTANCES, instances.joinToString("\n"))
            }
        }

        fun count(context: Context): Int =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(KEY_YGGPEERS_COUNT, DEFAULT_YGGPEERS_COUNT)
                .coerceIn(0, MAX_YGGPEERS_COUNT)

        fun setCount(context: Context, count: Int) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putInt(KEY_YGGPEERS_COUNT, count.coerceIn(0, MAX_YGGPEERS_COUNT))
            }
        }

        /** Minutes between checks of the picked peers. */
        fun checkInterval(context: Context): Int =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(KEY_YGGPEERS_CHECK_INTERVAL, DEFAULT_YGGPEERS_CHECK_INTERVAL)
                .coerceIn(YGGPEERS_CHECK_INTERVAL_RANGE)

        fun setCheckInterval(context: Context, minutes: Int) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putInt(KEY_YGGPEERS_CHECK_INTERVAL, minutes.coerceIn(YGGPEERS_CHECK_INTERVAL_RANGE))
            }
        }

        /** Days between forced refreshes of the picked peers, even when all are up. */
        fun rerankInterval(context: Context): Int =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(KEY_YGGPEERS_RERANK_INTERVAL, DEFAULT_YGGPEERS_RERANK_INTERVAL)
                .coerceIn(YGGPEERS_RERANK_INTERVAL_RANGE)

        fun setRerankInterval(context: Context, days: Int) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putInt(KEY_YGGPEERS_RERANK_INTERVAL, days.coerceIn(YGGPEERS_RERANK_INTERVAL_RANGE))
            }
        }

        /** Whether the text is a usable base URL of a yggpeers instance. */
        fun isInstanceUrl(text: String): Boolean = try {
            val uri = URI(text)
            (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "public-peers") }
    private val blacklist = HashSet<String>()
    // The next periodic check; replaced after every check, so a new interval applies at once
    private var next: ScheduledFuture<*>? = null

    /** What it is doing, for Peers. */
    @Volatile
    var status = ""
        private set

    /** Adds the peers picked before, then checks them soon and every check interval. */
    fun start() {
        executor.execute {
            if (instances(context).isNotEmpty()) {
                savedPeers().forEach { add(it) }
                status = picked(savedPeers())
            }
        }
        next = executor.schedule({ run(false) }, FIRST_CHECK_DELAY_S, TimeUnit.SECONDS)
    }

    /** Checks the picked peers soon, as when the network or the settings change. */
    fun check() {
        executor.execute { run(false) }
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun run(force: Boolean) {
        try {
            select(force)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        } catch (e: Exception) {
            Log.w(TAG, "Picking public peers failed: $e")
            status = "failed: ${e.javaClass.simpleName}"
        }
        next?.cancel(false)
        if (!executor.isShutdown) {
            val minutes = checkInterval(context).toLong()
            Log.i(TAG, "Next check in $minutes min")
            next = executor.schedule({ run(false) }, minutes, TimeUnit.MINUTES)
        }
    }

    private fun select(force: Boolean) {
        val instances = instances(context)
        val target = count(context)
        val current = savedPeers()
        if (instances.isEmpty() || target == 0) {
            current.forEach { remove(it) }
            save(emptyList(), stamp = false)
            status = "off"
            return
        }
        if (!hasInternet()) {
            status = "no Internet on this device" + if (current.isEmpty()) "" else "; " + picked(current)
            return
        }

        val due = force || current.size != target ||
            preferences.getString(KEY_SELECTED_FROM, null) != instances.joinToString("\n") ||
            System.currentTimeMillis() - selectedAt() !in 0..rerankInterval(context) * 24 * 3600_000L
        if (!due) {
            var down = current.filterNot { isUp(it) }
            if (down.isEmpty()) {
                status = picked(current)
                return
            }
            // It may still be connecting: one more chance
            Thread.sleep(RECHECK_DOWN_MS)
            down = down.filterNot { isUp(it) }
            if (down.isEmpty()) {
                status = picked(current)
                return
            }
            down.forEach { Log.i(TAG, "Peer is down, replacing it: $it") }
            blacklist.addAll(down)
        }

        status = "asking ${instances.size} yggpeers instance(s)"
        val reports = instances.mapNotNull { fetch(it) }
        if (reports.isEmpty()) {
            Log.w(TAG, "No yggpeers instance answered, keeping the current peers")
            status = "no yggpeers instance answered" + if (current.isEmpty()) "" else "; " + picked(current)
            return
        }
        val need = reports.size / 2 + 1
        val majority = reports.flatten().groupingBy { it }.eachCount().filterValues { it >= need }.keys

        val configuredHosts = configuredPeers().mapNotNull { host(it) }.toSet()
        val ipv6 = hasIpv6()
        var candidates = majority
            .filter { SCHEMES.contains(scheme(it)) }
            .filter { ipv6 || host(it)?.contains(':') != true }
            .filter { host(it) !in configuredHosts }
            .groupBy { host(it) }
            .mapNotNull { (_, uris) -> uris.minByOrNull { SCHEMES.indexOf(scheme(it)) } }
        // The blacklist goes only as long as enough candidates are left
        val allowed = candidates.filterNot { it in blacklist }
        if (allowed.size >= target) candidates = allowed

        status = "measuring ${candidates.size} candidates"
        val ranked = rank(candidates)
        if (ranked.isEmpty()) {
            Log.w(TAG, "No reachable candidates, keeping the current peers")
            status = "no reachable public peers" + if (current.isEmpty()) "" else "; " + picked(current)
            return
        }
        val chosen = ranked.take(target)
        Log.i(TAG, "Picked ${chosen.size} of $target peers from ${reports.size} instance(s): " +
            chosen.joinToString { "${it.first} ${it.second} ms" })

        val uris = chosen.map { it.first }
        (current - uris.toSet()).forEach { remove(it) }
        (uris - current.toSet()).forEach { add(it) }
        save(uris, stamp = true)
        preferences.edit { putString(KEY_SELECTED_FROM, instances.joinToString("\n")) }
        status = chosen.joinToString("\n") { "${it.first} (${it.second ?: "?"} ms)" }
    }

    /** The up, non-network peers an instance reports, or null if it does not answer. */
    private fun fetch(base: String): Set<String>? = try {
        val url = URL(base.trimEnd('/') + "/api/v1/peers?status=up&is_network=false")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = FETCH_TIMEOUT_MS
        connection.readTimeout = FETCH_TIMEOUT_MS
        try {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val peers = JSONObject(text).getJSONArray("peers")
            (0 until peers.length()).mapNotNull { peers.getJSONObject(it).optString("uri").ifEmpty { null } }.toSet()
                .ifEmpty { null }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "yggpeers instance $base: $e")
        null
    }

    /** Candidates with their ping in ms, fastest first; the silent ones (null) come last. */
    private fun rank(uris: List<String>): List<Pair<String, Long?>> {
        if (uris.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(PROBE_PARALLEL.coerceAtMost(uris.size))
        try {
            val results = pool.invokeAll(uris.map { uri -> Callable { uri to probe(uri) } })
            return results.map { it.get() }.sortedBy { it.second ?: Long.MAX_VALUE }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probe(uri: String): Long? {
        val host = host(uri) ?: return null
        val command = if (host.contains(':')) "ping6" else "ping"
        return try {
            val process = ProcessBuilder(command, "-c", "3", "-W", "2", "-q", host).redirectErrorStream(true).start()
            try {
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor(PING_TIMEOUT_S, TimeUnit.SECONDS)
                // rtt min/avg/max/mdev = a/b/c/d ms
                Regex("= [0-9.]+/([0-9.]+)/").find(output)?.groupValues?.get(1)?.toDouble()?.toLong()
            } finally {
                process.destroy()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Whether the node reports the peer up. The URI may come back without its ?query. */
    private fun isUp(uri: String): Boolean {
        val peers = try { JSONArray(yggdrasil.peersJSON) } catch (_: Exception) { return true }
        val hostPort = hostPort(uri)
        return (0 until peers.length()).map { peers.getJSONObject(it) }.any {
            val reported = it.optString("URI")
            it.optBoolean("Up") && (reported == uri || hostPort(reported) == hostPort)
        }
    }

    /** Whether a network other than the VPN reaches the Internet. */
    private fun hasInternet(): Boolean {
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.any {
            val caps = connectivity.getNetworkCapabilities(it) ?: return@any false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    /** Whether such a network has an IPv6 default route: without one, IPv6 hosts are unreachable. */
    private fun hasIpv6(): Boolean {
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.any { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@any false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
            val properties = connectivity.getLinkProperties(network) ?: return@any false
            properties.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }
        }
    }

    private fun add(uri: String) {
        yggdrasil.addPeer(uri)?.let { Log.w(TAG, "Adding $uri failed: $it") }
    }

    private fun remove(uri: String) {
        yggdrasil.removePeer(uri)?.let { Log.w(TAG, "Removing $uri failed: $it") }
    }

    private fun savedPeers(): List<String> =
        preferences.getString(KEY_AUTO_PEERS, "")!!.split(" ").filter { it.isNotEmpty() }

    private fun selectedAt(): Long = preferences.getLong(KEY_SELECTED_AT, 0)

    private fun save(uris: List<String>, stamp: Boolean) {
        preferences.edit {
            putString(KEY_AUTO_PEERS, uris.joinToString(" "))
            if (stamp) putLong(KEY_SELECTED_AT, System.currentTimeMillis())
        }
    }

    private fun picked(uris: List<String>) =
        if (uris.isEmpty()) "no public peers picked yet" else uris.joinToString("\n")

    private fun scheme(uri: String) = uri.substringBefore("://", "")

    private fun host(uri: String): String? = try { URI(uri).host?.trim('[', ']') } catch (_: Exception) { null }

    private fun hostPort(uri: String) = uri.substringAfter("://").substringBefore("?").substringBefore("/")
}
