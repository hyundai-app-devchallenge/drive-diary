package com.dongbantest.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.data.map.MapboxTokenSource
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.TimelineEvent
import com.dongbantest.app.domain.model.TimelineType
import com.dongbantest.app.ui.v3.components.DbIcon
import com.dongbantest.app.ui.v3.components.MomentKind
import com.dongbantest.app.ui.v3.components.MomentPin
import com.dongbantest.app.ui.v3.components.drawMomentPins
import com.dongbantest.app.ui.v3.components.rememberMomentPaint
import com.dongbantest.app.ui.v3.theme.DbColors
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.mapbox.bindgen.Value
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.MultiLineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraBoundsOptions
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CoordinateBounds
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
import com.mapbox.maps.extension.style.expressions.dsl.generated.interpolate
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.get
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.scalebar.scalebar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 지도에 그릴 기록 하나의 경로. */
data class MapRoute(
    val tripId: Long,
    /** "#RRGGBB" */
    val color: String,
    /** 위치가 튄 곳에서 끊긴 구간들 */
    val segments: List<List<LocationPoint>>,
    /** 출발·정차·도착 표시. 비우면 경로 끝에 도착 점만 찍는다. */
    val events: List<TimelineEvent> = emptyList(),
)

/** 지도 위 한 순간(이야기·노래 핀). 자리는 그 시각에 가장 가까운 위치 기록. */
data class MapMoment(val pin: MomentPin, val latitude: Double, val longitude: Double)

/** 노래가 흐른 길: 그 곡이 재생되는 동안 지난 위치 기록. 경로 옆 점선으로 그린다. */
data class MapSongPath(val id: String, val points: List<LocationPoint>)

/**
 * Mapbox 지도에 기록 경로를 그린다. 공개 토큰은 디버그 앱 "지도 설정" 또는 local.properties 의
 * MAPBOX_ACCESS_TOKEN 에서 온다. 지도 스타일은 [STYLE_URL] 하나만 바꾸면 된다.
 *
 * - 처음부터 한국을 보여 주고(세계 지도로 시작하지 않음), 한반도 밖으로 멀리 나가지 않게 막는다.
 * - 오른쪽 아래 확대·축소 버튼.
 * - 경로는 번짐(glow) → 흰 테두리 → 색 선 세 겹으로 그려 밝은 지도 위에서 또렷하게 보인다.
 *
 * - 바탕 지도는 v3 예시 지도와 같은 색(땅 mapLand, 바다 mapSea)으로 칠하고, 가게·버스 정류장 이름처럼
 *   기록과 상관없는 표시는 숨긴다.
 *
 * @param onRouteClick 경로를 누르면 그 기록의 id 로 부른다. null 이면 누를 수 없다.
 * @param moments 이야기·노래 핀. 예시 지도(MomentMap)와 같은 모양으로 지도 위에 그린다.
 * @param songPaths 노래가 흐른 길(베리색 점선)
 * @param onMomentClick 핀을 누르면 그 id, 빈 곳을 누르면 null. 고른 핀이 화면 밖이면 그쪽으로 지도를 옮긴다.
 */
@Composable
fun RouteMap(
    routes: List<MapRoute>,
    modifier: Modifier = Modifier,
    onRouteClick: ((Long) -> Unit)? = null,
    zoomControls: Boolean = true,
    moments: List<MapMoment> = emptyList(),
    songPaths: List<MapSongPath> = emptyList(),
    selectedMoment: String? = null,
    onMomentClick: ((String?) -> Unit)? = null,
    momentTone: Color = Color.Unspecified,
    selectedRoute: Long? = null,
    onBlankClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // 토큰이 없으면 지도를 만들지 않고 안내 문구만 보인다. 토큰이 저장되면 재시작 없이 지도가 나타난다.
    val store = remember { (context.applicationContext as DongbanApplication).container.mapboxTokenStore }
    val source by store.source.collectAsState()
    val revision by store.revision.collectAsState()
    if (source == MapboxTokenSource.NONE) {
        Box(modifier.background(Color(0xFFF2F2F2)), contentAlignment = Alignment.Center) {
            Text(
                "지도를 보려면 Mapbox 토큰을 넣어 주세요 (debug 설정 또는 local.properties)",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B6B6B),
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    // 토큰이 바뀌면 지도를 새로 만든다.
    key(revision) {
        MapboxRouteMap(routes, modifier, onRouteClick, zoomControls, moments, songPaths, selectedMoment, onMomentClick, momentTone, selectedRoute, onBlankClick)
    }
}

@Composable
@SuppressLint("ClickableViewAccessibility") // 터치는 지켜보기만 하고 지도에 그대로 넘긴다
private fun MapboxRouteMap(
    routes: List<MapRoute>,
    modifier: Modifier,
    onRouteClick: ((Long) -> Unit)?,
    zoomControls: Boolean,
    moments: List<MapMoment>,
    songPaths: List<MapSongPath>,
    selectedMoment: String?,
    onMomentClick: ((String?) -> Unit)?,
    momentTone: Color,
    selectedRoute: Long?,
    onBlankClick: (() -> Unit)?,
) {
    val context = LocalContext.current
    val colors = DbTheme.colors
    // v3 화면은 기기 밀도 대신 창 크기로 정한 배율(LocalDensity)로 그린다. 지도도 같은 배율로 그려야
    // 지명·선 굵기가 화면 글자와 같은 비율이 되고, 아래 dp 계산(누르는 거리, 여백)이 Compose 버튼과 맞는다.
    val uiDensity = LocalDensity.current.density
    val mapView = remember {
        val options = MapInitOptions.getDefaultMapOptions(context).toBuilder().pixelRatio(uiDensity).build()
        // 스크롤되는 화면 안에서도 잘 그려지도록 TextureView 방식을 쓴다.
        // 첫 장면부터 한국: 스타일·경로가 오기 전에 세계 지도가 잠깐 보이지 않게 한다.
        MapView(context, MapInitOptions(context, mapOptions = options, cameraOptions = KOREA_CAMERA, textureView = true)).apply {
            UI_DENSITY[this] = uiDensity
            // 로고는 기기 밀도로 그려지는데 pixelRatio 를 바꾸면 정보 버튼(ⓘ) 기본 여백만 커져 로고와 떨어진다. 기기 밀도 기준 여백으로 되돌린다.
            attribution.marginLeft = ATTRIBUTION_LEFT_DP * resources.displayMetrics.density
            gestures.rotateEnabled = false
            gestures.pitchEnabled = false
            scalebar.enabled = false // 작은 지도에서 거리 눈금이 경로를 가려서 숨긴다
            compass.enabled = false // 회전을 막았으니 나침반도 필요 없다
            mapboxMap.setBounds(KOREA_BOUNDS)
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
            runCatching { tintBaseStyle(s, colors) }
            // 경로 레이어를 먼저 깔아 둔다(빈 데이터). 노래 길 레이어가 늘 그 위·점 아래에 놓이게 하기 위해서다.
            addRouteLayers(s)
            style = s
        }
    }

    // 경로 데이터(수만 개 위치 점)는 화면 스레드 밖에서 한 번만 줄이고 묶는다. 경로를 고를 때는 다시 만들지 않는다.
    val prepared by produceState<PreparedRoutes?>(null, routes) {
        value = withContext(Dispatchers.Default) { prepareRoutes(routes) }
    }
    val selectedNow by rememberUpdatedState(selectedRoute)
    LaunchedEffect(style, prepared) {
        val s = style ?: return@LaunchedEffect
        val p = prepared ?: return@LaunchedEffect
        drawRoutes(s, p)
        applyRouteSelection(s, selectedNow)
        moveCamera(mapView, p.fitPoints, zoomControls)
    }
    // 고르기는 레이어 속성(색·투명도·위 칸 필터)만 바꾼다: 지도 데이터를 다시 읽지 않아 바로 반응한다.
    LaunchedEffect(style, selectedRoute) {
        val s = style ?: return@LaunchedEffect
        applyRouteSelection(s, selectedRoute)
    }
    // 목록에서 고른 경로가 화면 밖으로 나가 있으면 그 경로가 다 보이게 옮긴다(이미 보이면 그대로 둔다).
    LaunchedEffect(style, prepared, selectedRoute) {
        if (style == null || selectedRoute == null) return@LaunchedEffect
        val route = prepared?.byTrip?.get(selectedRoute) ?: return@LaunchedEffect
        showRoute(mapView, route, zoomControls)
    }
    LaunchedEffect(style, songPaths, selectedMoment) {
        val s = style ?: return@LaunchedEffect
        drawSongPaths(s, songPaths, selectedMoment, colors.music.hex())
    }

    // 지도를 끌거나 확대하면 핀 자리를 다시 계산해 그린다.
    var cameraTick by remember { mutableIntStateOf(0) }
    DisposableEffect(mapView) {
        val sub = mapView.mapboxMap.subscribeCameraChanged { cameraTick++ }
        onDispose { sub.cancel() }
    }
    // 목록·시간 띠에서 고른 핀이 화면 밖이면 그 자리로 지도를 옮긴다.
    LaunchedEffect(style, selectedMoment) {
        if (style == null) return@LaunchedEffect
        val m = moments.firstOrNull { it.pin.id == selectedMoment } ?: return@LaunchedEffect
        val point = Point.fromLngLat(m.longitude, m.latitude)
        val p = mapView.mapboxMap.pixelForCoordinate(point)
        val margin = 48 * mapView.uiDensity
        if (p.x < margin || p.y < margin || p.x > mapView.width - margin || p.y > mapView.height - margin) {
            mapView.camera.easeTo(CameraOptions.Builder().center(point).build(), MapAnimationOptions.Builder().duration(400L).build())
        }
    }

    val clickHandler by rememberUpdatedState(onRouteClick)
    val blankHandler by rememberUpdatedState(onBlankClick)
    val momentHandler by rememberUpdatedState(onMomentClick)
    val momentsNow by rememberUpdatedState(moments)
    // 누를 수 있는 지도는 두 번 눌러 확대를 끈다: Mapbox 는 두 번 누르기인지 가리느라 한 번 누르기를
    // 0.3초 늦게 알리는데, 그동안 경로·핀이 반응하지 않아 느리게 느껴진다. 확대는 버튼과 두 손가락으로 한다.
    val tappable = onRouteClick != null || onBlankClick != null || onMomentClick != null
    LaunchedEffect(mapView, tappable) {
        mapView.gestures.doubleTapToZoomInEnabled = !tappable
        mapView.gestures.quickZoomEnabled = !tappable
    }
    DisposableEffect(mapView) {
        val map = mapView.mapboxMap
        fun onTap(x: Double, y: Double) {
            momentHandler?.let { pick ->
                val density = mapView.uiDensity
                // 이야기 핀이 노래 핀 위에 그려지므로, 거리가 비슷하면 이야기를 먼저 고른다.
                val hit = momentsNow.map { m ->
                    val p = map.pixelForCoordinate(Point.fromLngLat(m.longitude, m.latitude))
                    m to kotlin.math.hypot(p.x - x, p.y - y)
                }.minByOrNull { (m, d) -> d - if (m.pin.kind == MomentKind.Talk) 6 * density else 0f }
                pick(hit?.takeIf { it.second < MOMENT_TOUCH_DP * density }?.first?.pin?.id)
                return
            }
            if (clickHandler == null && blankHandler == null) return
            val box = ScreenBox(ScreenCoordinate(x - TOUCH_PX, y - TOUCH_PX), ScreenCoordinate(x + TOUCH_PX, y + TOUCH_PX))
            map.queryRenderedFeatures(
                RenderedQueryGeometry(box),
                RenderedQueryOptions(listOf(PICK_LAYER, PICK_CASING_LAYER, ROUTE_LAYER, ROUTE_CASING_LAYER), null),
            ) { result ->
                val tripId = result.value?.firstOrNull()?.queriedFeature?.feature
                    ?.getNumberProperty(PROP_TRIP)?.toLong()
                // 경로가 없는 빈 곳을 누르면 고른 경로를 푼다.
                mapView.post { if (tripId != null) clickHandler?.invoke(tripId) else blankHandler?.invoke() }
            }
        }
        // 손을 떼는 즉시 누르기로 본다(끌기·두 손가락·길게 누르기는 뺀다). 이벤트는 그대로 지도에 넘겨 끌기·확대는 Mapbox 가 한다.
        val slop = ViewConfiguration.get(mapView.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var isTap = false
        mapView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downAt = e.eventTime; isTap = true }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> isTap = false
                MotionEvent.ACTION_MOVE -> if (kotlin.math.hypot(e.x - downX, e.y - downY) > slop) isTap = false
                MotionEvent.ACTION_UP ->
                    if (isTap && e.eventTime - downAt < ViewConfiguration.getLongPressTimeout()) onTap(e.x.toDouble(), e.y.toDouble())
            }
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    BoxWithConstraints(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (moments.isNotEmpty() && style != null) {
            val paint = rememberMomentPaint(if (momentTone == Color.Unspecified) colors.accent else momentTone)
            // 누르기는 지도(MapView)의 터치 리스너가 받는다. 이 그림 칸은 손가락을 가로채지 않는다.
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION") cameraTick // 카메라가 움직이면 다시 그린다
                val map = mapView.mapboxMap
                val placed = moments.map { m ->
                    val p = map.pixelForCoordinate(Point.fromLngLat(m.longitude, m.latitude))
                    m.pin to Offset(p.x.toFloat(), p.y.toFloat())
                }
                drawMomentPins(placed, selectedMoment, paint)
            }
        }
        if (showZoomButtons(zoomControls, maxHeight.value)) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(ZOOM_MARGIN_DP.dp),
            ) {
                ZoomButton(DbIcons.Plus, "지도 확대") { zoomBy(mapView, +1.0) }
                ZoomButton(DbIcons.Minus, "지도 축소") { zoomBy(mapView, -1.0) }
            }
        }
    }
}

/** 다이어리 한 장의 200dp 지도처럼 낮은 지도에서는 버튼이 경로를 가리므로 띄우지 않는다(손가락 확대는 그대로). */
private fun showZoomButtons(zoomControls: Boolean, mapHeightDp: Float): Boolean =
    zoomControls && mapHeightDp >= SMALL_MAP_HEIGHT_DP

/** 지도 위 확대·축소 버튼. 보이는 원은 40dp, 누르는 영역은 48dp. */
@Composable
private fun ZoomButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(ZOOM_TOUCH_DP.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape)
                .border(1.dp, Color(0x14000000), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DbIcon(icon, 20.dp, Color(0xFF1A1A1A), contentDescription = label)
        }
    }
}

private fun zoomBy(view: MapView, delta: Double) {
    val current = view.mapboxMap.cameraState.zoom
    val target = (current + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
    view.camera.easeTo(
        CameraOptions.Builder().zoom(target).build(),
        MapAnimationOptions.Builder().duration(250L).build(),
    )
}

/** 화면 스레드 밖에서 미리 만든 지도 데이터. 경로가 바뀔 때만 새로 만든다. */
private class PreparedRoutes(
    val lines: FeatureCollection,
    val dots: FeatureCollection,
    /** 모든 경로가 보이게 카메라를 맞출 점(줄인 경로, 한국 안) */
    val fitPoints: List<Point>,
    /** 여행 id → 그 경로를 감싸는 네모와 점. 고른 경로가 화면 안에 다 있는지 볼 때 쓴다. */
    val byTrip: Map<Long, RouteBox>,
)

private class RouteBox(val southWest: Point, val northEast: Point, val points: List<Point>)

/**
 * 경로를 지도 데이터로 바꾼다. GPS 점은 1초마다 찍혀 기록 열몇 개면 3만 개가 넘는데,
 * 가장 가까이 볼 때(줌 17)에도 구분되지 않는 점([SIMPLIFY_TOLERANCE_DEG], 약 3m 안쪽으로 곧은 곳)은 뺀다.
 */
private fun prepareRoutes(routes: List<MapRoute>): PreparedRoutes {
    val lines = ArrayList<Feature>()
    val fit = ArrayList<Point>()
    val byTrip = HashMap<Long, RouteBox>()
    routes.forEach { route ->
        val segs = route.segments.filter { it.size >= 2 }.map { simplify(it, SIMPLIFY_TOLERANCE_DEG) }
        if (segs.isEmpty()) return@forEach
        lines += Feature.fromGeometry(MultiLineString.fromLineStrings(segs.map { LineString.fromLngLats(it) })).apply {
            addNumberProperty(PROP_TRIP, route.tripId)
            addStringProperty(PROP_COLOR, route.color)
        }
        // 한국 밖 좌표(예: 에뮬레이터 기본 위치인 미국)는 카메라 맞추기에서 뺀다.
        val inKorea = segs.flatten().filter { it.latitude() in KOREA_LAT_RANGE && it.longitude() in KOREA_LNG_RANGE }
        fit += inKorea
        if (inKorea.size >= 2) {
            byTrip[route.tripId] = RouteBox(
                Point.fromLngLat(inKorea.minOf { it.longitude() }, inKorea.minOf { it.latitude() }),
                Point.fromLngLat(inKorea.maxOf { it.longitude() }, inKorea.maxOf { it.latitude() }),
                inKorea,
            )
        }
    }
    val dots = routes.flatMap { route -> routeDots(route) }
        // 정차 점을 먼저, 출발·도착 점이 위에 오도록 나중에 그린다.
        .sortedBy { if (it.kind == DotKind.STOP) 0 else 1 }
        .map { dot ->
            Feature.fromGeometry(Point.fromLngLat(dot.lng, dot.lat)).apply {
                // 출발: 흰 점 + 경로 색 테두리, 도착: 경로 색 점 + 흰 테두리, 정차: 작은 흰 점 + 경로 색 테두리
                addStringProperty(PROP_FILL, if (dot.kind == DotKind.END) dot.color else "#FFFFFF")
                addStringProperty(PROP_STROKE, if (dot.kind == DotKind.END) "#FFFFFF" else dot.color)
                addNumberProperty(PROP_RADIUS, if (dot.kind == DotKind.STOP) 4.5 else 7.0)
                addNumberProperty(PROP_STROKE_WIDTH, if (dot.kind == DotKind.STOP) 2.0 else 3.0)
            }
        }
    return PreparedRoutes(FeatureCollection.fromFeatures(lines), FeatureCollection.fromFeatures(dots), fit, byTrip)
}

/**
 * 더글러스-포이커 방식으로 거의 곧은 구간의 가운데 점을 뺀다. 첫 점·끝 점은 늘 남는다.
 * 경도는 위도에 맞춰 줄여(cos) 가로·세로 거리를 같은 잣대로 잰다.
 */
private fun simplify(seg: List<LocationPoint>, tolerance: Double): List<Point> {
    val n = seg.size
    if (n <= 2) return seg.map { Point.fromLngLat(it.longitude, it.latitude) }
    val k = kotlin.math.cos(Math.toRadians(seg[0].latitude))
    val xs = DoubleArray(n) { seg[it].longitude * k }
    val ys = DoubleArray(n) { seg[it].latitude }
    val keep = BooleanArray(n).also { it[0] = true; it[n - 1] = true }
    val tol2 = tolerance * tolerance
    // (시작, 끝) 구간 쌍을 하나의 Long 에 담아 쌓는다(재귀 대신).
    val stack = ArrayDeque<Long>().apply { addLast((n - 1).toLong()) }
    while (stack.isNotEmpty()) {
        val pair = stack.removeLast()
        val a = (pair ushr 32).toInt()
        val b = (pair and 0xFFFFFFFFL).toInt()
        if (b - a < 2) continue
        val dx = xs[b] - xs[a]
        val dy = ys[b] - ys[a]
        val len2 = dx * dx + dy * dy
        var far = -1
        var farD = tol2
        for (i in a + 1 until b) {
            val t = if (len2 == 0.0) 0.0 else (((xs[i] - xs[a]) * dx + (ys[i] - ys[a]) * dy) / len2).coerceIn(0.0, 1.0)
            val ex = xs[a] + t * dx - xs[i]
            val ey = ys[a] + t * dy - ys[i]
            val d = ex * ex + ey * ey
            if (d > farD) { farD = d; far = i }
        }
        if (far > 0) {
            keep[far] = true
            stack.addLast((a.toLong() shl 32) or far.toLong())
            stack.addLast((far.toLong() shl 32) or b.toLong())
        }
    }
    return (0 until n).filter { keep[it] }.map { Point.fromLngLat(seg[it].longitude, seg[it].latitude) }
}

/** 미리 만든 경로·점 데이터를 지도에 넣는다(레이어는 [addRouteLayers] 가 이미 깔아 두었다). */
private fun drawRoutes(style: Style, routes: PreparedRoutes) {
    upsertSource(style, ROUTE_SOURCE, routes.lines)
    upsertSource(style, EVENT_SOURCE, routes.dots)
}

/**
 * 경로 레이어를 아래에서 위로 깐다: 모든 경로(번짐 → 흰 테두리 → 색 선) → 고른 경로만(같은 세 겹) → 점.
 * 고른 경로는 위 칸 세 겹이 필터로 따로 그려 늘 다른 경로 위에 온다. 고르기는 [applyRouteSelection] 이
 * 레이어 속성만 바꾸므로 지도 데이터를 다시 읽지 않는다.
 */
private fun addRouteLayers(style: Style) {
    val empty = FeatureCollection.fromFeatures(emptyList())
    upsertSource(style, ROUTE_SOURCE, empty)
    upsertSource(style, EVENT_SOURCE, empty)
    fun addLines(glow: String, casing: String, line: String, glowOpacity: Double) {
        if (!style.styleLayerExists(glow)) {
            style.addLayer(
                lineLayer(glow, ROUTE_SOURCE) {
                    lineColor(get(PROP_COLOR))
                    lineWidth(widthByZoom(3.0))
                    lineOpacity(glowOpacity)
                    lineBlur(widthByZoom(1.4))
                    lineCap(LineCap.ROUND)
                    lineJoin(LineJoin.ROUND)
                }
            )
        }
        if (!style.styleLayerExists(casing)) {
            style.addLayer(
                lineLayer(casing, ROUTE_SOURCE) {
                    lineColor("#FFFFFF")
                    lineWidth(widthByZoom(1.0, extra = 3.0))
                    lineCap(LineCap.ROUND)
                    lineJoin(LineJoin.ROUND)
                }
            )
        }
        if (!style.styleLayerExists(line)) {
            style.addLayer(
                lineLayer(line, ROUTE_SOURCE) {
                    lineColor(get(PROP_COLOR))
                    lineWidth(widthByZoom(1.0))
                    lineCap(LineCap.ROUND)
                    lineJoin(LineJoin.ROUND)
                }
            )
        }
    }
    addLines(ROUTE_GLOW_LAYER, ROUTE_CASING_LAYER, ROUTE_LAYER, GLOW_OPACITY)
    addLines(PICK_GLOW_LAYER, PICK_CASING_LAYER, PICK_LAYER, PICK_GLOW_OPACITY)
    if (!style.styleLayerExists(EVENT_LAYER)) {
        style.addLayer(
            circleLayer(EVENT_LAYER, EVENT_SOURCE) {
                circleColor(get(PROP_FILL))
                circleRadius(get(PROP_RADIUS))
                circleStrokeColor(get(PROP_STROKE))
                circleStrokeWidth(get(PROP_STROKE_WIDTH))
            }
        )
    }
    applyRouteSelection(style, null)
}

/**
 * 고른 경로만 위 칸에 진하게, 나머지는 아래 칸에서 회청색([RouteColors.DIMMED])으로 흐리게.
 * 흐린 경로는 투명도 대신 색을 바꾼다: 반투명 색이 지도와 섞이면 갈색·회갈색으로 탁해진다.
 */
private fun applyRouteSelection(style: Style, selected: Long?) {
    val dim = selected != null
    val opacity = Value.valueOf(if (dim) DIM_OPACITY else 1.0)
    style.setStyleLayerProperty(ROUTE_LAYER, "line-color", if (dim) Value.valueOf(RouteColors.DIMMED) else get(PROP_COLOR))
    style.setStyleLayerProperty(ROUTE_LAYER, "line-opacity", opacity)
    style.setStyleLayerProperty(ROUTE_CASING_LAYER, "line-opacity", opacity)
    style.setStyleLayerProperty(ROUTE_GLOW_LAYER, "line-opacity", Value.valueOf(if (dim) 0.0 else GLOW_OPACITY))
    val filter = if (selected == null) Expression.literal(false) else Expression.eq(get(PROP_TRIP), Expression.literal(selected.toDouble()))
    listOf(PICK_GLOW_LAYER, PICK_CASING_LAYER, PICK_LAYER).forEach { style.setStyleLayerProperty(it, "filter", filter) }
}

private enum class DotKind { START, STOP, END }

private data class RouteDot(val lat: Double, val lng: Double, val kind: DotKind, val color: String)

/**
 * 출발·정차·도착 점. 타임라인이 없으면(기록 탭의 전체 지도) 점을 찍지 않는다:
 * 여러 기록의 출발·도착점이 집 근처에 몰려 겹치고, 경로 선만으로 충분하기 때문이다.
 */
private fun routeDots(route: MapRoute): List<RouteDot> {
    if (route.events.isNotEmpty()) {
        // 다시 출발은 정차와 거의 같은 자리라 점을 생략한다.
        return route.events.filter { it.type != TimelineType.RESUME }.map { e ->
            val kind = when (e.type) {
                TimelineType.START -> DotKind.START
                TimelineType.END -> DotKind.END
                else -> DotKind.STOP
            }
            RouteDot(e.latitude, e.longitude, kind, route.color)
        }
    }
    return emptyList()
}

/**
 * 노래가 흐른 길: 경로 왼쪽 옆으로 띄운 베리색 점선. 고른 곡은 진하고 굵게, 나머지는 옅게.
 * 경로 선 위, 출발·도착 점 아래에 놓는다.
 */
private fun drawSongPaths(style: Style, paths: List<MapSongPath>, selected: String?, color: String) {
    val features = paths.filter { it.points.size >= 2 }.map { p ->
        val sel = p.id == selected
        Feature.fromGeometry(LineString.fromLngLats(p.points.map { Point.fromLngLat(it.longitude, it.latitude) })).apply {
            addNumberProperty(PROP_SONG_WIDTH, if (sel) 4.0 else 3.0)
            addNumberProperty(PROP_SONG_OPACITY, if (sel) 1.0 else 0.6)
        }
    }
    upsertSource(style, SONG_SOURCE, FeatureCollection.fromFeatures(features))
    if (!style.styleLayerExists(SONG_LAYER)) {
        val layer = lineLayer(SONG_LAYER, SONG_SOURCE) {
            lineColor(color)
            lineWidth(get(PROP_SONG_WIDTH))
            lineOpacity(get(PROP_SONG_OPACITY))
            // 0 길이 대시 + 둥근 끝 = 점. 간격은 선 굵기의 2배.
            lineDasharray(listOf(0.0, 2.0))
            lineCap(LineCap.ROUND)
            lineJoin(LineJoin.ROUND)
            // 경로(흰 테두리 포함) 바깥으로 비켜 그린다. 음수 = 진행 방향 왼쪽.
            lineOffset(interpolate {
                linear()
                zoom()
                stop { literal(5.0); literal(-5.0) }
                stop { literal(9.0); literal(-6.0) }
                stop { literal(13.0); literal(-8.0) }
                stop { literal(17.0); literal(-10.0) }
            })
        }
        if (style.styleLayerExists(EVENT_LAYER)) style.addLayerBelow(layer, EVENT_LAYER) else style.addLayer(layer)
    }
}

/**
 * Mapbox 밝은 지도를 v3 예시 지도처럼 칠한다: 땅은 mapLand, 바다·강은 mapSea.
 * 공원·건물·가게·정류장 같은 표시는 숨겨 경로와 핀이 먼저 눈에 띄게 한다. 지명·도로 이름은 남긴다.
 */
private fun tintBaseStyle(style: Style, c: DbColors) {
    val land = Value.valueOf(c.mapLand.hex())
    val sea = Value.valueOf(c.mapSea.hex())
    val hidden = Value.valueOf("none")
    style.styleLayers.forEach { layer ->
        val id = layer.id
        if (id.startsWith("dongban-")) return@forEach
        when {
            layer.type == "background" -> style.setStyleLayerProperty(id, "background-color", land)
            HIDDEN_BASE_LAYERS.any { id.contains(it) } -> style.setStyleLayerProperty(id, "visibility", hidden)
            id.startsWith("water") && layer.type == "fill" -> style.setStyleLayerProperty(id, "fill-color", sea)
            id.startsWith("water") && layer.type == "line" -> style.setStyleLayerProperty(id, "line-color", sea)
        }
    }
}

private fun Color.hex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)

/** 숨기는 바탕 레이어(Mapbox light-v11 이름의 일부) */
private val HIDDEN_BASE_LAYERS = listOf(
    "landuse", "landcover", "national-park", "hillshade", "building", "poi-label", "transit-label",
    "airport-label", "aerialway", "ferry", "road-number-shield", "road-exit-shield", "pitch-outline", "admin-1",
)

/** 줌에 따라 굵어지는 선 폭: 전국을 볼 때는 가늘게, 동네를 볼 때는 굵게. */
private fun widthByZoom(scale: Double, extra: Double = 0.0): Expression = interpolate {
    linear()
    zoom()
    stop { literal(5.0); literal(2.5 * scale + extra) }
    stop { literal(9.0); literal(4.0 * scale + extra) }
    stop { literal(13.0); literal(6.0 * scale + extra) }
    stop { literal(17.0); literal(9.0 * scale + extra) }
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
 * 모든 경로가 보이게 카메라를 맞춘다. 경로가 없으면 한국 전체를 보여준다.
 * 지도 크기가 정해진 뒤에 맞춘다(크기가 0일 때 맞추면 아주 멀리서 보인다).
 * 경로 둘레 여백은 지도 크기에 비례한다: 다이어리의 작은 지도(높이 200dp)에서 고정 여백을 크게 주면
 * 경로가 손톱만 하게 나오기 때문이다. 확대·축소 버튼이 있으면 오른쪽 여백에 버튼 폭을 더해
 * 출발·도착 점이 버튼 밑에 깔리지 않게 한다.
 */
private fun moveCamera(view: MapView, points: List<Point>, zoomControls: Boolean) {
    // 한국 밖 좌표는 [prepareRoutes] 에서 이미 뺐다. 남는 게 없으면 한국 전체를 보여 준다.
    // 그대로 맞추면 카메라가 경계 끝 바다로 밀려나 지도가 회색 한 면으로 보인다.
    view.doOnLayout {
        val map: MapboxMap = view.mapboxMap
        when {
            points.isEmpty() -> map.setCamera(KOREA_CAMERA)
            points.distinct().size == 1 -> map.setCamera(CameraOptions.Builder().center(points.first()).zoom(15.0).build())
            else -> {
                val pad = (minOf(view.width, view.height) * CAMERA_PADDING_RATIO).coerceAtLeast(CAMERA_PADDING_MIN_PX).toDouble()
                val density = view.uiDensity
                val right = if (showZoomButtons(zoomControls, view.height / density)) {
                    pad + (ZOOM_TOUCH_DP + ZOOM_MARGIN_DP * 2) * density
                } else {
                    pad
                }
                // 왼쪽 아래 Mapbox 로고에 출발 점이 깔리지 않게 아래 여백을 로고 높이만큼 더 준다.
                val bottom = pad + LOGO_INSET_DP * density
                map.cameraForCoordinates(
                    points,
                    CameraOptions.Builder().build(),
                    EdgeInsets(pad, pad, bottom, right),
                    MAX_FIT_ZOOM,
                    null,
                ) { camera -> map.setCamera(camera) }
            }
        }
    }
}

/**
 * 고른 경로가 화면 밖으로 삐져나가 있으면 그 경로에 맞춰 부드럽게 옮긴다. 다 보이면 그대로 둔다.
 * 지도를 돌리거나 기울이지 않으므로 경로를 감싸는 네모의 두 꼭짓점만 보면 된다.
 */
private fun showRoute(view: MapView, route: RouteBox, zoomControls: Boolean) {
    val map = view.mapboxMap
    val density = view.uiDensity
    val edge = 12 * density
    val sw = map.pixelForCoordinate(route.southWest)
    val ne = map.pixelForCoordinate(route.northEast)
    val visible = sw.x >= edge && ne.y >= edge && ne.x <= view.width - edge && sw.y <= view.height - edge
    if (visible) return
    val pad = (minOf(view.width, view.height) * CAMERA_PADDING_RATIO).coerceAtLeast(CAMERA_PADDING_MIN_PX).toDouble()
    val right = if (showZoomButtons(zoomControls, view.height / density)) pad + (ZOOM_TOUCH_DP + ZOOM_MARGIN_DP * 2) * density else pad
    map.cameraForCoordinates(
        route.points, CameraOptions.Builder().build(), EdgeInsets(pad, pad, pad + LOGO_INSET_DP * density, right), MAX_FIT_ZOOM, null,
    ) { camera -> view.camera.easeTo(camera, MapAnimationOptions.Builder().duration(450L).build()) }
}

/** 밝은 지도: v3 흰 바탕 디자인과 어울리고 경로 색이 잘 보인다. Mapbox Studio 스타일 주소로 바꿔도 된다. */
private val STYLE_URL = Style.LIGHT
private const val ROUTE_SOURCE = "dongban-routes"
private const val ROUTE_GLOW_LAYER = "dongban-routes-glow"
private const val ROUTE_CASING_LAYER = "dongban-routes-casing"
private const val ROUTE_LAYER = "dongban-routes-line"
private const val EVENT_SOURCE = "dongban-events"
private const val EVENT_LAYER = "dongban-events-circle"
private const val PROP_TRIP = "tripId"
private const val PROP_COLOR = "color"
/** 고른 경로만 따로 그리는 위 칸(아래 칸 세 겹과 같은 모양, 필터로 고른 여행만) */
private const val PICK_GLOW_LAYER = "dongban-routes-pick-glow"
private const val PICK_CASING_LAYER = "dongban-routes-pick-casing"
private const val PICK_LAYER = "dongban-routes-pick-line"
/** 번짐 투명도: 아무것도 안 골랐을 때 모든 경로, 골랐을 때 고른 경로 */
private const val GLOW_OPACITY = 0.16
private const val PICK_GLOW_OPACITY = 0.28
/** 경로 줄이기 허용 오차(도). 약 3m: 가장 가까이(줌 17) 볼 때 선 굵기(9px ≈ 8m)보다 작다. */
private const val SIMPLIFY_TOLERANCE_DEG = 0.00003
/** 다른 경로를 고른 동안 나머지 경로의 투명도 */
private const val DIM_OPACITY = 0.75
private const val PROP_FILL = "fill"
private const val PROP_STROKE = "stroke"
private const val PROP_RADIUS = "radius"
private const val PROP_STROKE_WIDTH = "strokeWidth"
private const val SONG_SOURCE = "dongban-songs"
private const val SONG_LAYER = "dongban-songs-line"
private const val PROP_SONG_WIDTH = "songWidth"
private const val PROP_SONG_OPACITY = "songOpacity"
/** 핀을 누른 것으로 보는 거리 */
private const val MOMENT_TOUCH_DP = 28f
private const val TOUCH_PX = 24.0
/** 이보다 낮은 지도(다이어리 한 장의 200dp 지도 등)에는 확대·축소 버튼을 띄우지 않는다 */
private const val SMALL_MAP_HEIGHT_DP = 280f
private const val ZOOM_TOUCH_DP = 48f
private const val ZOOM_MARGIN_DP = 12f
/** 지도 왼쪽 아래 Mapbox 로고·정보 버튼이 차지하는 높이 */
private const val LOGO_INSET_DP = 20f
/** 경로 둘레 여백 = 지도 짧은 변의 12% (최소 16px) */
private const val CAMERA_PADDING_RATIO = 0.12f
private const val CAMERA_PADDING_MIN_PX = 16f
private const val MIN_ZOOM = 5.0
private const val MAX_ZOOM = 17.0
/** 짧은 경로에 맞출 때 너무 가까이 들어가지 않게 */
private const val MAX_FIT_ZOOM = 15.5

/** 한반도 남쪽(제주~휴전선, 서해~울릉·독도)을 처음 보이는 장면으로 */
private val KOREA_CAMERA: CameraOptions = CameraOptions.Builder()
    .center(Point.fromLngLat(127.8, 36.2))
    .zoom(5.8)
    .build()

private val KOREA_LAT_RANGE = 32.0..39.6
private val KOREA_LNG_RANGE = 123.0..133.0

/** 지도를 한국 둘레로 묶는다. 이 밖으로는 끌어도 나가지 않는다. */
private val KOREA_BOUNDS: CameraBoundsOptions = CameraBoundsOptions.Builder()
    .bounds(
        CoordinateBounds(
            Point.fromLngLat(KOREA_LNG_RANGE.start, KOREA_LAT_RANGE.start),
            Point.fromLngLat(KOREA_LNG_RANGE.endInclusive, KOREA_LAT_RANGE.endInclusive),
        )
    )
    .minZoom(MIN_ZOOM)
    .maxZoom(MAX_ZOOM)
    .build()

/** 지도를 만들 때의 화면 배율(LocalDensity). 기기 밀도(displayMetrics)와 다를 수 있다. */
private val UI_DENSITY = java.util.WeakHashMap<MapView, Float>()

/** 이 지도의 dp → px 배율. 지도 안의 dp 계산은 모두 이 값으로 한다(Compose 화면과 같은 배율). */
private val MapView.uiDensity: Float get() = UI_DENSITY[this] ?: resources.displayMetrics.density

/** Mapbox 기본값: 정보 버튼은 로고 오른쪽 92dp 자리 */
private const val ATTRIBUTION_LEFT_DP = 92f
