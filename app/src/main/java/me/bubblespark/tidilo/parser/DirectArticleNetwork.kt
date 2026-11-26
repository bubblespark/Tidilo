package me.bubblespark.tidilo.parser

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.NetworkException
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun formatDirectNetworkFailure(errorCode: Int?, rawMessage: String?): String = when (errorCode) {
    1 -> "Unable to resolve host"
    2 -> "No internet connection"
    3 -> "Connection interrupted by network change"
    4, 6 -> "Connection timed out"
    5 -> "Connection closed unexpectedly"
    7 -> "Connection refused"
    8 -> "Connection reset"
    9 -> "Unable to reach host"
    else -> {
        val lower = rawMessage?.lowercase().orEmpty()
        when {
            listOf("err_ssl_", "err_cert_", "ssl", "certificat", "handshake").any { it in lower } ->
                "SSL connection error"
            "err_name_not_resolved" in lower -> "Unable to resolve host"
            "err_timed_out" in lower || "timeout" in lower -> "Connection timed out"
            else -> "Network request failed"
        }
    }
}

/** Direct import transport with cancellation delegated to Android's HTTP engine. */
internal class DirectArticleNetwork(context: Context) : ArticleNetwork {
    override val scopeKey = "direct-article"
    private val engine by lazy {
        HttpEngine.Builder(context.applicationContext)
            .setUserAgent(WebPageParser.USER_AGENT)
            .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED, 0)
            .build()
    }
    private val callbacks = Executor { Dispatchers.IO.dispatch(EmptyCoroutineContext, it) }

    override suspend fun fetch(url: String): ArticleResponse = suspendCancellableCoroutine { continuation ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(READ_BUFFER_BYTES)
        var redirects = 0
        val callback = object : UrlRequest.Callback {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                if (++redirects <= MAX_REDIRECTS) request.followRedirect()
                else fail(request, IOException("Too many article redirects"))
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                if (info.httpStatusCode != 200) fail(request, ArticleHttpFailure(info.httpStatusCode))
                else request.read(ByteBuffer.allocateDirect(READ_BUFFER_BYTES))
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                byteBuffer.flip()
                val readSize = minOf(byteBuffer.remaining(), MAX_RESPONSE_BYTES - output.size())
                byteBuffer.get(chunk, 0, readSize)
                output.write(chunk, 0, readSize)
                if (output.size() == MAX_RESPONSE_BYTES) {
                    // Match the previous Jsoup fetch limit by returning the collected prefix.
                    request.cancel()
                    complete(info)
                    return
                }
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) = complete(info)

            private fun complete(info: UrlResponseInfo) {
                if (!continuation.isActive) return
                val contentType = info.headers.asList.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                continuation.resume(ArticleResponse(info.url, info.httpStatusCode, output.toByteArray(), contentType))
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
                if (continuation.isActive) {
                    val message = formatDirectNetworkFailure((error as? NetworkException)?.errorCode, error.message)
                    continuation.resumeWithException(IOException(message, error))
                }
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) = Unit

            private fun fail(request: UrlRequest, error: IOException) {
                request.cancel()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        val builder = engine.newUrlRequestBuilder(url, callbacks, callback)
            .setCacheDisabled(true)
        WebPageParser.REQUEST_HEADERS.forEach { (name, value) -> builder.addHeader(name, value) }
        val request = builder.build()
        continuation.invokeOnCancellation { request.cancel() }
        if (continuation.isActive) request.start()
        if (!continuation.isActive) request.cancel()
    }

    private companion object {
        const val READ_BUFFER_BYTES = 16 * 1024
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_REDIRECTS = 20
    }
}
