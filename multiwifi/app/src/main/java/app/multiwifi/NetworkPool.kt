package app.multiwifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps several [Network]s alive at the same time and hands out the least busy one.
 *
 * Link sources:
 *  - extra WiFi networks requested by SSID (WifiNetworkSpecifier; the user approves each in a system dialog)
 *  - the WiFi the phone is already on
 *  - mobile data (optional)
 */
class NetworkPool(context: Context) {

    class Link(val label: String, val network: Network) {
        val inflight = AtomicInteger(0)
        @Volatile var totalConnections = 0L
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val links = CopyOnWriteArrayList<Link>()
    private val callbacks = CopyOnWriteArrayList<ConnectivityManager.NetworkCallback>()

    @Volatile var onChange: (() -> Unit)? = null

    fun snapshot(): List<Link> = links.toList()

    /** Ask Android to join [ssid] as an additional WiFi link. */
    fun requestWifi(ssid: String, passphrase: String?) {
        val spec = WifiNetworkSpecifier.Builder().apply {
            setSsid(ssid)
            if (!passphrase.isNullOrEmpty()) setWpa2Passphrase(passphrase)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(spec)
            .build()
        register("WiFi: $ssid", request, ownRequest = true)
    }

    /** Track the WiFi the phone is already connected to. */
    fun trackSystemWifi() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        register("WiFi (current)", request, ownRequest = false)
    }

    /** Use mobile data as one more link. */
    fun requestCellular() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        register("Mobile data", request, ownRequest = true)
    }

    private fun register(label: String, request: NetworkRequest, ownRequest: Boolean) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                links.removeAll { it.label == label }
                links.add(Link(label, network))
                onChange?.invoke()
            }

            override fun onLost(network: Network) {
                links.removeAll { it.network == network }
                onChange?.invoke()
            }

            override fun onUnavailable() {
                links.removeAll { it.label == label }
                onChange?.invoke()
            }
        }
        callbacks.add(cb)
        try {
            if (ownRequest) cm.requestNetwork(request, cb) else cm.registerNetworkCallback(request, cb)
        } catch (e: SecurityException) {
            callbacks.remove(cb)
        }
    }

    /** Least in-flight connections wins; the caller must decrement [Link.inflight] when done. */
    fun pick(): Link? {
        val best = links.minByOrNull { it.inflight.get() } ?: return null
        best.inflight.incrementAndGet()
        best.totalConnections++
        return best
    }

    fun stop() {
        callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        callbacks.clear()
        links.clear()
        onChange?.invoke()
    }
}
