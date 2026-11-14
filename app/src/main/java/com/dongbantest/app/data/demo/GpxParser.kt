package com.dongbantest.app.data.demo

import java.io.InputStream
import java.time.Instant
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Shared by bundled demos and the debug file importer. Missing times advance by three seconds. */
object GpxParser {
    data class Point(val lat: Double, val lng: Double, val time: Long)

    fun parse(input: InputStream): List<Point> {
        val result = mutableListOf<Point>()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val handler = object : DefaultHandler() {
            var lat: Double? = null
            var lng = 0.0
            var time: Long? = null
            var inTime = false
            val text = StringBuilder()
            override fun startElement(uri: String?, local: String, qName: String?, attributes: Attributes) {
                when (local) {
                    "trkpt" -> {
                        lat = attributes.getValue("lat").toDouble()
                        lng = attributes.getValue("lon").toDouble()
                        time = null
                    }
                    "time" -> if (lat != null) { inTime = true; text.clear() }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inTime) text.append(ch, start, length)
            }
            override fun endElement(uri: String?, local: String, qName: String?) {
                when (local) {
                    "time" -> if (inTime) {
                        time = runCatching { Instant.parse(text.toString().trim()).toEpochMilli() }.getOrNull()
                        inTime = false
                    }
                    "trkpt" -> {
                        result += Point(requireNotNull(lat), lng, time ?: (result.lastOrNull()?.time?.plus(3_000) ?: 0L))
                        lat = null
                    }
                }
            }
        }
        factory.newSAXParser().parse(input, handler)
        require(result.size >= 2) { "GPX has too few points" }
        require(result.zipWithNext().all { (a, b) -> a.time <= b.time }) { "GPX times out of order" }
        return result
    }
}
