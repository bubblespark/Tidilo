package me.bubblespark.tidilo.data.rss

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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

    fun availableNetwork(): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let { caps ->
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                (!wifiOnly() || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        } == true
    }

    suspend fun request(url: String, headers: Map<String, String>): RssHttpPage = withContext(Dispatchers.IO) {
        val network = availableNetwork() ?: throw RssWifiUnavailable()
        var currentUrl = url
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val connection = network.openConnection(URL(currentUrl)) as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Tidilo RSS/1.0")
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    currentUrl = RssFeedParser.httpUrl(currentUrl, connection.getHeaderField("Location").orEmpty())
                        ?: throw IOException("Invalid feed redirect")
                } else {
                    if (status != 200) return@withContext RssHttpPage(status, currentUrl, "",
                        connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
                    if (connection.contentLengthLong > MAX_BYTES) throw IOException("RSS response exceeds 2 MB")
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val caps = connectivity.getNetworkCapabilities(network)
                            if (wifiOnly() && caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) throw RssWifiUnavailable()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (output.size() + read > MAX_BYTES) throw IOException("RSS response exceeds 2 MB")
                            output.write(buffer, 0, read)
                        }
                    }
                    val bytes = output.toByteArray()
                    val declared = Regex("charset=[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                        .find(connection.contentType.orEmpty())?.groupValues?.get(1)
                        ?: Regex("encoding=[\"']([^\"']+)").find(String(bytes.take(200).toByteArray(), Charsets.US_ASCII))?.groupValues?.get(1)
                    val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                    return@withContext RssHttpPage(status, currentUrl, String(bytes, charset),
                        connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Too many feed redirects")
    }

    companion object { private const val MAX_BYTES = 2 * 1024 * 1024 }
}
