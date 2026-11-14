package com.drivediary.talkrec.ask

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherClientTest {
    private val sample = """{"latitude":37.45,"longitude":127.14,
        "current":{"time":"2026-11-14T10:00","temperature_2m":11.6,"weather_code":61,"precipitation":0.4,"is_day":1},
        "hourly":{"time":["a","b","c"],"precipitation_probability":[20,70,40]}}"""

    @Test fun parseSample() {
        val w = WeatherClient.parse(sample, 5L)!!
        assertEquals(61, w.code)
        assertEquals(11.6, w.temperatureC, 0.0)
        assertTrue(w.isDay)
        assertEquals(70, w.rainChance)
        assertEquals(5L, w.fetchedAtMs)
        assertEquals("비, 12도", w.describe())
    }

    @Test fun parseMissingCurrentIsNull() = assertNull(WeatherClient.parse("""{"hourly":{}}""", 0))

    @Test fun describeAddsRainChanceWhenNotRaining() {
        val w = WeatherSnapshot(3, 10.0, true, 60, 0)
        assertEquals("흐림, 10도, 3시간 안 비 60%", w.describe())
    }

    @Test fun roundsCoordinatesInUrl() = runBlocking {
        val urls = mutableListOf<String>()
        val client = WeatherClient(HttpGetter { urls += it; 200 to sample }, { 0L })
        assertNotNull(client.current(37.456789, 127.143219))
        val url = urls.single()
        assertTrue(url, url.contains("latitude=37.46&longitude=127.14"))
        assertFalse(url, url.contains("37.456"))
        assertFalse(url, url.contains("127.143"))
    }

    @Test fun nullOnNon2xxAndIoException() = runBlocking {
        assertNull(WeatherClient(HttpGetter { 500 to "x" }).current(37.0, 127.0))
        assertNull(WeatherClient(HttpGetter { throw IOException("down") }).current(37.0, 127.0))
        assertNull(WeatherClient(HttpGetter { 200 to "not json" }).current(37.0, 127.0))
    }

    @Test fun cachesWithin30Minutes() = runBlocking {
        var now = 0L
        var calls = 0
        val client = WeatherClient(HttpGetter { calls++; 200 to sample }, { now })
        client.current(37.451, 127.141)
        now = 29 * 60_000L
        client.current(37.449, 127.139) // rounds to the same key
        assertEquals(1, calls)
        now = 31 * 60_000L
        client.current(37.451, 127.141)
        assertEquals(2, calls)
    }

    @Test fun invalidCoordinatesSkipRequest() = runBlocking {
        var calls = 0
        val client = WeatherClient(HttpGetter { calls++; 200 to sample })
        assertNull(client.current(0.0, 0.0))
        assertNull(client.current(Double.NaN, 127.0))
        assertEquals(0, calls)
    }
}
