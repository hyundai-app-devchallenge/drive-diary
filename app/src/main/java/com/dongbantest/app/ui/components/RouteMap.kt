package com.dongbantest.app.ui.components

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.TimelineEvent
import com.dongbantest.app.domain.model.TimelineType
import org.maplibre.android.MapLibre
import androidx.core.view.doOnLayout
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

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
 * MapLibre 지도에 기록 경로를 그린다. 지도 타일은 OpenFreeMap(무료, 키 없음, OpenStreetMap 데이터).
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
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        // 스크롤되는 화면 안에서도 잘 그려지도록 TextureView 방식을 쓴다.
        val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        MapView(context, options).apply { onCreate(null) }
    }

    // 화면 생명주기에 맞춰 지도를 켜고 끈다.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                map = m
                style = s
            }
        }
    }

    LaunchedEffect(map, style, routes) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        drawRoutes(s, routes)
        moveCamera(m, mapView, routes)
    }

    val clickHandler by rememberUpdatedState(onRouteClick)
    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { latLng ->
            val handler = clickHandler ?: return@OnMapClickListener false
            val p = m.projection.toScreenLocation(latLng)
            val touch = RectF(p.x - TOUCH_PX, p.y - TOUCH_PX, p.x + TOUCH_PX, p.y + TOUCH_PX)
            val tripId = m.queryRenderedFeatures(touch, ROUTE_LAYER)
                .firstOrNull()?.getNumberProperty(PROP_TRIP)?.toLong()
                ?: return@OnMapClickListener false
            handler(tripId)
            true
        }
        m.addOnMapClickListener(listener)
        onDispose { m.removeOnMapClickListener(listener) }
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

    if (style.getLayer(ROUTE_LAYER) == null) {
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.get(PROP_COLOR)),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
    }
    if (style.getLayer(EVENT_LAYER) == null) {
        style.addLayer(
            CircleLayer(EVENT_LAYER, EVENT_SOURCE).withProperties(
                PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            )
        )
    }
}

private fun upsertSource(style: Style, id: String, data: FeatureCollection) {
    val existing = style.getSourceAs<GeoJsonSource>(id)
    if (existing != null) existing.setGeoJson(data) else style.addSource(GeoJsonSource(id, data))
}

/**
 * 모든 경로가 보이게 카메라를 맞춘다. 경로가 없으면 서울을 보여준다.
 * 지도 크기가 정해진 뒤에 맞춘다(크기가 0일 때 맞추면 아주 멀리서 보인다).
 * 경로 둘레 여백은 지도 크기에 비례한다: 다이어리의 작은 지도(높이 200dp)에서 고정 여백을 크게 주면
 * 경로가 손톱만 하게 나오기 때문이다.
 */
private fun moveCamera(map: MapLibreMap, view: MapView, routes: List<MapRoute>) {
    val points = routes.flatMap { it.segments.flatten() }.map { LatLng(it.latitude, it.longitude) }
    view.doOnLayout {
        when {
            points.isEmpty() -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(SEOUL, 10.0))
            points.distinct().size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 15.0))
            else -> {
                val bounds = LatLngBounds.Builder().includes(points).build()
                val padding = (minOf(view.width, view.height) * CAMERA_PADDING_RATIO).toInt().coerceAtLeast(CAMERA_PADDING_MIN_PX)
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            }
        }
    }
}

private fun eventColor(type: TimelineType): String = when (type) {
    TimelineType.START -> "#2E7D32"
    TimelineType.STOP, TimelineType.RESUME -> "#F9A825"
    TimelineType.END -> "#C62828"
}

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_SOURCE = "dongban-routes"
private const val ROUTE_LAYER = "dongban-routes-line"
private const val EVENT_SOURCE = "dongban-events"
private const val EVENT_LAYER = "dongban-events-circle"
private const val PROP_TRIP = "tripId"
private const val PROP_COLOR = "color"
private const val TOUCH_PX = 24f
/** 경로 둘레 여백 = 지도 짧은 변의 12% (최소 16px) */
private const val CAMERA_PADDING_RATIO = 0.12f
private const val CAMERA_PADDING_MIN_PX = 16
private val SEOUL = LatLng(37.5665, 126.9780)
