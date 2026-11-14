package com.drivediary.talkrec.ask

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Current weather in words, e.g. "비, 12도" or "흐림, 10도, 3시간 안 비 60%". */
data class WeatherSnapshot(
    val code: Int,
    val temperatureC: Double,
    val isDay: Boolean,
    /** Max precipitation probability over the next few hours, %. */
    val rainChance: Int?,
    val fetchedAtMs: Long,
) {
    fun describe(): String = buildString {
        append(weatherWord(code)).append(", ").append(temperatureC.roundToInt()).append("도")
        if (rainChance != null && rainChance >= 40 && weatherWord(code) !in setOf("비", "소나기", "뇌우", "이슬비"))
            append(", 3시간 안 비 ").append(rainChance).append('%')
    }
}

/** WMO weather code → Korean word (Open-Meteo docs table). */
fun weatherWord(code: Int): String = when (code) {
    0 -> "맑음"
    1, 2 -> "구름 조금"
    3 -> "흐림"
    45, 48 -> "안개"
    in 51..57 -> "이슬비"
    in 61..67 -> "비"
    in 71..77 -> "눈"
    in 80..82 -> "소나기"
    85, 86 -> "눈 소나기"
    in 95..99 -> "뇌우"
    else -> "알 수 없음"
}

fun interface HttpGetter {
    /** Returns (status, body). Throws IOException on network failure. */
    suspend fun get(url: String): Pair<Int, String>
}

object UrlConnectionGetter : HttpGetter {
    override suspend fun get(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000; connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            status to body
        } finally { connection.disconnect() }
    }
}

/**
 * Open-Meteo forecast API (no key; free tier is non-commercial only, CC BY 4.0 attribution).
 * Coordinates are rounded to 2 decimals (~1 km) before leaving the device. Results are cached 30 minutes.
 */
class WeatherClient(
    private val http: HttpGetter = UrlConnectionGetter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMs: Long = 30 * 60_000L,
) {
    private val cache = mutableMapOf<String, WeatherSnapshot>()

    /** Null on any failure: questions are then made without weather. */
    suspend fun current(latitude: Double, longitude: Double): WeatherSnapshot? {
        if (!latitude.isFinite() || !longitude.isFinite() || (latitude == 0.0 && longitude == 0.0)) return null
        val lat = String.format(Locale.ROOT, "%.2f", latitude)
        val lon = String.format(Locale.ROOT, "%.2f", longitude)
        val key = "$lat,$lon"
        synchronized(cache) { cache[key]?.takeIf { clock() - it.fetchedAtMs < cacheMs }?.let { return it } }
        val url = "$BASE?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,precipitation,is_day" +
            "&hourly=precipitation_probability&forecast_hours=3&timezone=Asia%2FSeoul"
        val snapshot = (try {
            val (status, body) = http.get(url)
            if (status in 200..299) parse(body, clock()) else null
        } catch (_: IOException) { null } catch (_: RuntimeException) { null }) ?: return null
        synchronized(cache) { cache[key] = snapshot }
        return snapshot
    }

    companion object {
        const val BASE = "https://api.open-meteo.com/v1/forecast"
        const val ATTRIBUTION = "Weather data by Open-Meteo.com"
        const val ATTRIBUTION_URL = "https://open-meteo.com/"

        internal fun parse(body: String, nowMs: Long): WeatherSnapshot? {
            val json = JSONObject(body)
            val current = json.optJSONObject("current") ?: return null
            val code = current.optInt("weather_code", -1).takeIf { it >= 0 } ?: return null
            val temperature = current.optDouble("temperature_2m").takeIf { it.isFinite() } ?: return null
            val chances = json.optJSONObject("hourly")?.optJSONArray("precipitation_probability")
            val rain = chances?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? Number }.maxOfOrNull { it.toInt() } }
            return WeatherSnapshot(code, temperature, current.optInt("is_day", 1) == 1, rain, nowMs)
        }
    }
}
