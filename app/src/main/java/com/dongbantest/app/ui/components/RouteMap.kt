package com.dongbantest.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dongbantest.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.TimelineEvent
import com.dongbantest.app.domain.model.TimelineType
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.MultiLineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.ScreenBox
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.Style
import com.mapbox.maps.extension.localization.localizeLabels
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.get
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.gestures.removeOnMapClickListener
import com.mapbox.maps.plugin.scalebar.scalebar
import java.util.Locale

/** 지도에 그릴 기록 하나의 경로. */
data class MapRoute(
    val tripId: Long,
    /** "#RRGGBB" */
    val color: String,
    /** 위치가 튄 곳에서 끊긴 구간들 */
    val segments: List<List<LocationPoint>>,
    /** 출발·정차·도착 표시. 비우면 점을 찍지 않는다. */
    val events: List<TimelineEvent> = emptyList(),
)

/**
 * Mapbox 지도에 기록 경로를 그린다. 공개 토큰은 local.properties 의 MAPBOX_ACCESS_TOKEN 에서 온다
 * (build.gradle.kts 가 mapbox_access_token 문자열 리소스로 넣고, Mapbox SDK 가 자동으로 읽는다).
 * 지도 스타일은 [STYLE_URL] 하나만 바꾸면 된다 (Mapbox Studio 로 만든 스타일 주소도 넣을 수 있다).
 *
 * @param onRouteClick 경로를 누르면 그 기록의 id 로 부른다. null 이면 누를 수 없다.
 */
@Composable
fun RouteMap(
    routes: List<MapRoute>,
    modifier: Modifier = Modifier,
    onRouteClick: ((Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    // 토큰이 없으면(local.properties 에 MAPBOX_ACCESS_TOKEN 이 없음) 지도를 만들지 않고 안내 문구만 보인다.
    val hasToken = remember { context.getString(R.string.mapbox_access_token).isNotBlank() }
    if (!hasToken) {
        Box(modifier.background(Color(0xFFF2F2F2)), contentAlignment = Alignment.Center) {
            Text(
                "지도를 보려면 local.properties 에 MAPBOX_ACCESS_TOKEN 을 넣어 주세요",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B6B6B),
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    MapboxRouteMap(routes, modifier, onRouteClick)
}

@Composable
private fun MapboxRouteMap(
    routes: List<MapRoute>,
    modifier: Modifier,
    onRouteClick: ((Long) -> Unit)?,
) {
    val context = LocalContext.current
    val mapView = remember {
        // 스크롤되는 화면 안에서도 잘 그려지도록 TextureView 방식을 쓴다.
        MapView(context, MapInitOptions(context, textureView = true)).apply {
            gestures.rotateEnabled = false
            gestures.pitchEnabled = false
            scalebar.enabled = false // 작은 지도에서 거리 눈금이 경로를 가려서 숨긴다
        }
    }
    // 화면에서 지도가 사라지면 지도 자원을 정리한다. 시작·멈춤은 Mapbox 가 화면 생명주기를 따라 스스로 한다.
    DisposableEffect(mapView) {
        onDispose { mapView.onDestroy() }
    }

    var style by remember { mutableStateOf<Style?>(null) }
    LaunchedEffect(mapView) {
        mapView.mapboxMap.loadStyle(STYLE_URL) { s ->
            runCatching { s.localizeLabels(Locale.KOREAN) } // 지명을 한국어로
            style = s
        }
    }

    LaunchedEffect(style, routes) {
        val s = style ?: return@LaunchedEffect
        drawRoutes(s, routes)
        moveCamera(mapView, routes)
    }

    val clickHandler by rememberUpdatedState(onRouteClick)
    DisposableEffect(mapView) {
        val map = mapView.mapboxMap
        val listener = OnMapClickListener { point ->
            if (clickHandler == null) return@OnMapClickListener false
            val p = map.pixelForCoordinate(point)
            val box = ScreenBox(
                ScreenCoordinate(p.x - TOUCH_PX, p.y - TOUCH_PX),
                ScreenCoordinate(p.x + TOUCH_PX, p.y + TOUCH_PX),
            )
            map.queryRenderedFeatures(
                RenderedQueryGeometry(box),
                RenderedQueryOptions(listOf(ROUTE_LAYER), null),
            ) { result ->
                val tripId = result.value?.firstOrNull()?.queriedFeature?.feature
                    ?.getNumberProperty(PROP_TRIP)?.toLong() ?: return@queryRenderedFeatures
                mapView.post { clickHandler?.invoke(tripId) }
            }
            true
        }
        map.addOnMapClickListener(listener)
        onDispose { map.removeOnMapClickListener(listener) }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun drawRoutes(style: Style, routes: List<MapRoute>) {
    val lines = routes.filter { r -> r.segments.any { it.size >= 2 } }.map { route ->
        val geometry = MultiLineString.fromLineStrings(
            route.segments.filter { it.size >= 2 }.map { seg ->
                LineString.fromLngLats(seg.map { Point.fromLngLat(it.longitude, it.latitude) })
            }
        )
        Feature.fromGeometry(geometry).apply {
            addNumberProperty(PROP_TRIP, route.tripId)
            addStringProperty(PROP_COLOR, route.color)
        }
    }
    val dots = routes.flatMap { route ->
        // 다시 출발은 정차와 거의 같은 자리라 점을 생략하고, 출발·도착 점이 위에 오도록 마지막에 그린다.
        route.events.filter { it.type != TimelineType.RESUME }
            .sortedBy { if (it.type == TimelineType.STOP) 0 else 1 }
            .map { event ->
                Feature.fromGeometry(Point.fromLngLat(event.longitude, event.latitude)).apply {
                    addStringProperty(PROP_COLOR, eventColor(event.type))
                }
            }
    }

    upsertSource(style, ROUTE_SOURCE, FeatureCollection.fromFeatures(lines))
    upsertSource(style, EVENT_SOURCE, FeatureCollection.fromFeatures(dots))

    if (!style.styleLayerExists(ROUTE_LAYER)) {
        style.addLayer(
            lineLayer(ROUTE_LAYER, ROUTE_SOURCE) {
                lineColor(get(PROP_COLOR))
                lineWidth(6.0)
                lineCap(LineCap.ROUND)
                lineJoin(LineJoin.ROUND)
            }
        )
    }
    if (!style.styleLayerExists(EVENT_LAYER)) {
        style.addLayer(
            circleLayer(EVENT_LAYER, EVENT_SOURCE) {
                circleColor(get(PROP_COLOR))
                circleRadius(7.0)
                circleStrokeColor("#FFFFFF")
                circleStrokeWidth(2.0)
            }
        )
    }
}

private fun upsertSource(style: Style, id: String, data: FeatureCollection) {
    val existing = style.getSourceAs<GeoJsonSource>(id)
    if (existing != null) {
        existing.featureCollection(data)
    } else {
        style.addSource(geoJsonSource(id) { featureCollection(data) })
    }
}

/**
 * 모든 경로가 보이게 카메라를 맞춘다. 경로가 없으면 서울을 보여준다.
 * 지도 크기가 정해진 뒤에 맞춘다(크기가 0일 때 맞추면 아주 멀리서 보인다).
 * 경로 둘레 여백은 지도 크기에 비례한다: 다이어리의 작은 지도(높이 200dp)에서 고정 여백을 크게 주면
 * 경로가 손톱만 하게 나오기 때문이다.
 */
private fun moveCamera(view: MapView, routes: List<MapRoute>) {
    val points = routes.flatMap { it.segments.flatten() }.map { Point.fromLngLat(it.longitude, it.latitude) }
    view.doOnLayout {
        val map: MapboxMap = view.mapboxMap
        when {
            points.isEmpty() -> map.setCamera(CameraOptions.Builder().center(SEOUL).zoom(10.0).build())
            points.distinct().size == 1 -> map.setCamera(CameraOptions.Builder().center(points.first()).zoom(15.0).build())
            else -> {
                val pad = (minOf(view.width, view.height) * CAMERA_PADDING_RATIO).coerceAtLeast(CAMERA_PADDING_MIN_PX).toDouble()
                map.cameraForCoordinates(
                    points,
                    CameraOptions.Builder().build(),
                    EdgeInsets(pad, pad, pad, pad),
                    null,
                    null,
                ) { camera -> map.setCamera(camera) }
            }
        }
    }
}

private fun eventColor(type: TimelineType): String = when (type) {
    TimelineType.START -> "#2E7D32"
    TimelineType.STOP, TimelineType.RESUME -> "#F9A825"
    TimelineType.END -> "#C62828"
}

/** 밝은 지도: v3 흰 바탕 디자인과 어울리고 경로 색이 잘 보인다. Mapbox Studio 스타일 주소로 바꿔도 된다. */
private val STYLE_URL = Style.LIGHT
private const val ROUTE_SOURCE = "dongban-routes"
private const val ROUTE_LAYER = "dongban-routes-line"
private const val EVENT_SOURCE = "dongban-events"
private const val EVENT_LAYER = "dongban-events-circle"
private const val PROP_TRIP = "tripId"
private const val PROP_COLOR = "color"
private const val TOUCH_PX = 24.0
/** 경로 둘레 여백 = 지도 짧은 변의 12% (최소 16px) */
private const val CAMERA_PADDING_RATIO = 0.12f
private const val CAMERA_PADDING_MIN_PX = 16f
private val SEOUL = Point.fromLngLat(126.9780, 37.5665)
