package com.drivediary.talkrec.summary

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Deliberately not data classes: toString must not expose bodies or credentials.
class SummaryHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val readTimeoutMs: Int = 30_000,
)
class SummaryHttpResponse(val status: Int, val body: String)

fun interface SummaryHttpClient {
    suspend fun post(request: SummaryHttpRequest): SummaryHttpResponse
}

class UrlConnectionSummaryHttpClient : SummaryHttpClient {
    override suspend fun post(request: SummaryHttpRequest): SummaryHttpResponse = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            ensureActive()
            connection = URL(request.url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            // A redirect must never forward an API key or transcript to another host.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = request.readTimeoutMs
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val bytes = request.body.toByteArray(Charsets.UTF_8)
            // Relay requires Content-Length and rejects chunked requests.
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 65_536) throw SummaryException(SummaryErrorCode.HTTP_ERROR)
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }.orEmpty()
            ensureActive()
            SummaryHttpResponse(status, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (safe: SummaryException) {
            throw safe
        } catch (_: SocketTimeoutException) {
            throw SummaryException(SummaryErrorCode.TIMEOUT)
        } catch (_: IOException) {
            throw SummaryException(SummaryErrorCode.NETWORK)
        } catch (_: Exception) {
            throw SummaryException(SummaryErrorCode.HTTP_ERROR)
        } finally {
            connection?.disconnect()
        }
    }
}

internal fun checkSummaryHttp(response: SummaryHttpResponse, cloudflare: Boolean = false) {
    if (response.status in 200..299) return
    throw SummaryException(when (response.status) {
        401, 403 -> SummaryErrorCode.AUTH
        408 -> if (cloudflare) SummaryErrorCode.NETWORK else SummaryErrorCode.TIMEOUT
        413 -> SummaryErrorCode.REQUEST_TOO_LARGE
        429 -> SummaryErrorCode.RATE_LIMIT
        in 500..599 -> if (cloudflare) SummaryErrorCode.NETWORK else SummaryErrorCode.SERVER_ERROR
        else -> SummaryErrorCode.HTTP_ERROR
    })
}
