package me.bubblespark.tidilo.data.rss

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.parser.RssFeedParser

internal class RssWifiUnavailable : IOException("Waiting for Wi-Fi")

/** RSS connections are bound to a concrete network so Wi-Fi loss cannot fall back to cellular. */
internal class RssHttpClient(context: Context, private val wifiOnly: () -> Boolean) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // Enumerate non-default routes too: prefer validated Wi-Fi and bind requests to it without cellular fallback.
    @Suppress("DEPRECATION")
    fun availableNetwork(): Network? = selectRssNetwork(connectivity.allNetworks.mapNotNull { network ->
        val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            network to caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) else null
    }, wifiOnly())

    suspend fun request(url: String, headers: Map<String, String>, maxBytes: Int = MAX_BYTES): RssHttpPage = withContext(Dispatchers.IO) {
        val network = availableNetwork() ?: throw RssWifiUnavailable()
        fun ensurePermittedNetwork() {
            val caps = connectivity.getNetworkCapabilities(network)
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                (wifiOnly() && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))) throw RssWifiUnavailable()
        }
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        fun remaining(): Int = (deadline - SystemClock.elapsedRealtime()).let {
            if (it <= 0) throw SocketTimeoutException("RSS request exceeded 30 seconds")
            it.coerceAtMost(10_000L).toInt()
        }
        var currentUrl = url
        repeat(6) {
            currentCoroutineContext().ensureActive()
            ensurePermittedNetwork()
            val connection = try { network.openConnection(URL(currentUrl)) as HttpURLConnection }
                catch (error: IOException) { ensurePermittedNetwork(); throw error }
            try {
                connection.connectTimeout = remaining()
                connection.readTimeout = remaining()
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", me.bubblespark.tidilo.parser.WebPageParser.USER_AGENT)
                me.bubblespark.tidilo.parser.WebPageParser.REQUEST_HEADERS.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                connection.connect()
                ensurePermittedNetwork()
                connection.readTimeout = remaining()
                val status = connection.responseCode
                ensurePermittedNetwork()
                remaining()
                if (status in listOf(301, 302, 303, 307, 308)) {
                    currentUrl = RssFeedParser.httpUrl(currentUrl, connection.getHeaderField("Location").orEmpty())
                        ?: throw IOException("Invalid feed redirect")
                } else {
                    if (status != 200) return@withContext RssHttpPage(status, currentUrl, "",
                        connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
                    if (connection.contentLengthLong > maxBytes) throw IOException("Response exceeds limit of $maxBytes bytes")
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val caps = connectivity.getNetworkCapabilities(network)
                            if (wifiOnly() && caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) throw RssWifiUnavailable()
                            connection.readTimeout = remaining()
                            val read = input.read(buffer)
                            remaining()
                            if (read < 0) break
                            if (output.size() + read > maxBytes) throw IOException("Response exceeds limit of $maxBytes bytes")
                            output.write(buffer, 0, read)
                        }
                    }
                    val bytes = output.toByteArray()
                    val declared = Regex("charset=[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                        .find(connection.contentType.orEmpty())?.groupValues?.get(1)
                        ?: Regex("encoding=[\"']([^\"']+)").find(String(bytes.take(200).toByteArray(), Charsets.US_ASCII))?.groupValues?.get(1)
                    val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                    return@withContext RssHttpPage(status, currentUrl, String(bytes, charset),
                        connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"), bytes, connection.contentType)
                }
            } catch (error: IOException) { ensurePermittedNetwork(); throw error }
            finally { connection.disconnect() }
        }
        throw IOException("Too many feed redirects")
    }

    companion object { private const val MAX_BYTES = 2 * 1024 * 1024 }
}

/** Candidates have already passed Internet validation; Wi-Fi wins regardless of enumeration order. */
internal fun <T> selectRssNetwork(candidates: List<Pair<T, Boolean>>, wifiOnly: Boolean): T? =
    candidates.firstOrNull { it.second }?.first ?: if (wifiOnly) null else candidates.firstOrNull()?.first
