package me.bubblespark.tidilo.parser

import android.content.Context
import android.net.http.DnsOptions
import android.net.http.HttpEngine
import android.net.http.HttpException
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

/** Direct import transport with cancellation delegated to Android's HTTP engine. */
internal class DirectArticleNetwork(context: Context) : ArticleNetwork {
    override val scopeKey = "direct-article"
    private val engine by lazy {
        HttpEngine.Builder(context.applicationContext)
            .setUserAgent(WebPageParser.USER_AGENT)
            .setDnsOptions(DnsOptions.Builder().setUseHttpStackDnsResolver(DnsOptions.DNS_OPTION_ENABLED).build())
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
                if (info.httpStatusCode !in 200..299) fail(request, ArticleHttpFailure(info.httpStatusCode))
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
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) = Unit

            private fun fail(request: UrlRequest, error: IOException) {
                request.cancel()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        val builder = engine.newUrlRequestBuilder(url, callbacks, callback)
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
