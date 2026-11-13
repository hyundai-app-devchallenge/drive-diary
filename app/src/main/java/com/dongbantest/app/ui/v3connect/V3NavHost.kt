package com.dongbantest.app.ui.v3connect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivediary.talkrec.TalkRec
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.data.repository.RecordingConsentStore
import com.dongbantest.app.location.LocationReader
import com.dongbantest.app.ui.ArrivalRequest
import com.dongbantest.app.ui.components.RouteColors
import com.dongbantest.app.ui.components.RouteMap
import com.dongbantest.app.ui.v3.components.DbDialog
import com.dongbantest.app.ui.v3.components.DbTab
import com.dongbantest.app.ui.v3.components.DbTextButton
import com.dongbantest.app.ui.v3.components.OutlineButton
import com.dongbantest.app.ui.v3.components.PrimaryButton
import com.dongbantest.app.ui.v3.components.Text
import com.dongbantest.app.ui.v3.screens.ArrivalScreen
import com.dongbantest.app.ui.v3.screens.ConsentScreen
import com.dongbantest.app.ui.v3.screens.ConsentUiState
import com.dongbantest.app.ui.v3.screens.DiaryReadyScreen
import com.dongbantest.app.ui.v3.screens.DiaryScreen
import com.dongbantest.app.ui.v3.screens.DiaryUiState
import com.dongbantest.app.ui.v3.screens.DrivingEvents
import com.dongbantest.app.ui.v3.screens.DrivingLayout
import com.dongbantest.app.ui.v3.screens.HomeScreen
import com.dongbantest.app.ui.v3.screens.MakingDiaryScreen
import com.dongbantest.app.ui.v3.screens.RecordMapScreen
import com.dongbantest.app.ui.v3.screens.StopNoticeScreen
import com.dongbantest.app.ui.v3.screens.StopNoticeUiState
import com.dongbantest.app.ui.v3.screens.TripEmptyScreen
import com.dongbantest.app.ui.v3.screens.TripSetupScreen
import com.dongbantest.app.ui.v3.screens.TripSetupUiState
import com.dongbantest.app.ui.v3.screens.VoiceGuideScreen
import com.dongbantest.app.ui.v3.screens.VoiceGuideUiState
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing
import com.dongbantest.app.viewmodel.AppViewModelProvider
import com.dongbantest.app.viewmodel.DiaryListViewModel
import com.dongbantest.app.viewmodel.HomeViewModel
import com.dongbantest.app.viewmodel.TripViewModel
import kotlinx.coroutines.delay
import com.dongbantest.app.ui.components.MapRoute as RealMapRoute

/**
 * 확정안 v3 화면으로 앱 전체를 잇는 길잡이.
 * 예전 화면용 길잡이(DongbanNavHost)는 지우지 않고 그대로 두었다.
 *
 * 흐름: 홈(S01) → 여행 설정(S02) → 녹음 동의(S03) → 음성 안내(S04) → 여행 중(S05–S10)
 *      → 도착(S11) → 만드는 중(S12) → 완성(S13) → 다이어리(S14) / 기록 지도(R01)
 * 주행 중에 기록을 열면 정차 안내(P01) → 정차하면(P02) 이어서 연다.
 */
private object V3Routes {
    const val HOME = "home"
    const val SETUP = "setup"
    const val TRIP = "trip/{tripId}?guide={guide}"
    const val TRIP_EMPTY = "tripEmpty"
    const val ARRIVAL = "arrival/{tripId}?name={name}"
    const val MAKING = "making/{tripId}"
    const val READY = "ready/{tripId}"
    const val RECORDS = "records"
    const val DIARY = "diary/{tripId}"
    const val STOP = "stop?target={target}"

    fun trip(id: Long, guide: Boolean = false) = "trip/$id?guide=$guide"
    fun arrival(id: Long, name: String) = "arrival/$id?name=${Uri.encode(name)}"
    fun making(id: Long) = "making/$id"
    fun ready(id: Long) = "ready/$id"
    fun diary(id: Long) = "diary/$id"
    fun stop(target: String) = "stop?target=${Uri.encode(target)}"
}

private val tripIdArg = listOf(navArgument("tripId") { type = NavType.LongType })
private fun NavBackStackEntry.tripId(): Long = arguments?.getLong("tripId") ?: 0L

/** 탭이나 큰 이동은 홈 위에 한 화면만 쌓이게 한다 (뒤로 가기 → 홈). */
private fun NavHostController.go(route: String) = navigate(route) {
    popUpTo(V3Routes.HOME)
    launchSingleTop = true
}

private fun Context.hasMic(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** 녹음 시간 글자처럼 시간이 흐르면 바뀌는 값을 위해 10초마다 현재 시각을 준다. */
@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(10_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun V3NavHost(
    navController: NavHostController = rememberNavController(),
    arrivalRequest: ArrivalRequest? = null,
    onArrivalHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    // 앱 전체에서 쓰는 값(진행 중 여행, 주행 중 여부, 지난 여행)은 홈 ViewModel 하나에서 읽는다.
    val homeVm: HomeViewModel = viewModel(factory = AppViewModelProvider.Home)
    val home by homeVm.uiState.collectAsStateWithLifecycle()
    val restricted = home.safety?.isRestricted == true
    // talk-rec 연동(I5b): debug 설정의 "주행 중 제목 보이기"를 여행 중 화면에 적용한다.
    val summaryStore = remember { (context.applicationContext as DongbanApplication).container.summarySettingsStore }
    val summarySettings by summaryStore.settings.collectAsStateWithLifecycle()

    // 홈 진행률을 위해 15초마다 내비에 남은 거리를 묻는다.
    LaunchedEffect(Unit) {
        while (true) {
            homeVm.refreshRoute()
            delay(15_000)
        }
    }

    // 여행이 자동 종료되면(앱 안에서든 알림을 눌러서든) S11 도착 화면으로 간다.
    LaunchedEffect(arrivalRequest) {
        val request = arrivalRequest ?: return@LaunchedEffect
        navController.go(V3Routes.arrival(request.tripId, request.destinationName))
        onArrivalHandled()
    }

    val onTab: (DbTab) -> Unit = { tab ->
        when (tab) {
            DbTab.Home -> navController.navigate(V3Routes.HOME) { popUpTo(V3Routes.HOME) { inclusive = true } }
            DbTab.Trip -> navController.go(home.ongoingTrip?.let { V3Routes.trip(it.id) } ?: V3Routes.TRIP_EMPTY)
            DbTab.Record -> navController.go(if (restricted) V3Routes.stop(V3Routes.RECORDS) else V3Routes.RECORDS)
        }
    }
    val openDiary: (Long) -> Unit = { id ->
        navController.go(if (restricted) V3Routes.stop(V3Routes.diary(id)) else V3Routes.diary(id))
    }

    NavHost(navController = navController, startDestination = V3Routes.HOME, modifier = Modifier.background(Color.White)) {

        /* S01 홈 */
        composable(V3Routes.HOME) {
            HomeScreen(
                state = home.toV3(),
                onTab = onTab,
                onStartTrip = { navController.navigate(V3Routes.SETUP) },
                onOpenPastTrip = { id -> id.toLongOrNull()?.let(openDiary) },
                onOpenLiveTrip = { home.ongoingTrip?.let { navController.go(V3Routes.trip(it.id)) } },
                onPastLocked = { navController.go(V3Routes.stop(V3Routes.RECORDS)) },
            )
        }

        /* S02 여행 설정 → S03 녹음 동의 → 여행 시작 */
        composable(V3Routes.SETUP) {
            var tripTitle by rememberSaveable { mutableStateOf("") }
            // 목적지는 직접 쓰지 않고 내비에 찍힌 곳을 쓴다. 아직 안 찍었으면 null.
            val naviDestination = home.route?.destinationName?.takeIf { it.isNotBlank() }
            var companion by rememberSaveable { mutableStateOf("가족") }
            var headcount by rememberSaveable { mutableIntStateOf(2) }
            var askConsent by rememberSaveable { mutableStateOf(false) }
            val homeBackground = home.toV3()

            fun start(consent: Boolean, sendToAi: Boolean = false) { // talk-rec 연동: 외부 AI 전송 동의 추가
                val dest = naviDestination ?: "어딘가 멋진 곳"
                // 제목을 비워 두면 목적지로 "○○ 여행"을 만든다.
                val title = tripTitle.trim().ifBlank { naviDestination?.let { "$it 여행" } ?: "우리의 여행" }
                // v3 는 이름 대신 관계와 인원만 받는다. 다이어리 "함께한 사람" 칸에는 나 + 관계 번호로 남긴다.
                val people = listOf("나") + (1 until headcount).map { "$companion$it" }
                homeVm.startTrip(title = title, destination = dest, companions = people) { id ->
                    RecordingConsentStore(context).set(id, consent, sendToAi)
                    navController.go(V3Routes.trip(id, guide = true))
                }
            }

            if (askConsent) {
                BackHandler { askConsent = false }
                ConsentScreen(
                    state = ConsentUiState(home = homeBackground),
                    onTab = onTab,
                    // talk-rec 연동: 동의 세 가지 선택(기록 / 외부 AI 전송 / 녹음 없이) 한 번에 받는다
                    onStart = { record, ai -> start(consent = record, sendToAi = ai) },
                )
            } else {
                TripSetupScreen(
                    state = TripSetupUiState(
                        tripTitle = tripTitle,
                        naviDestination = naviDestination,
                        selectedCompanion = companion,
                        headcount = headcount,
                        home = homeBackground,
                    ),
                    onTab = onTab,
                    onTripTitleChange = { tripTitle = it },
                    onCompanionSelect = { companion = it },
                    onHeadcountChange = { headcount = it.coerceIn(1, 9) },
                    onStart = { askConsent = true },
                    onLater = { navController.popBackStack() },
                )
            }
        }

        /* S04 음성 안내 + S05–S10 여행 중 */
        composable(
            V3Routes.TRIP,
            arguments = tripIdArg + navArgument("guide") { type = NavType.BoolType; defaultValue = false },
        ) { entry ->
            val tripId = entry.tripId()
            val vm: TripViewModel = viewModel(factory = AppViewModelProvider.trip(tripId))
            val state by vm.uiState.collectAsStateWithLifecycle()
            val now = rememberNow()
            val capture by TalkRec.state.collectAsStateWithLifecycle() // talk-rec: 상시 캡처 상태(녹음 칩)
            var showGuide by rememberSaveable { mutableStateOf(entry.arguments?.getBoolean("guide") == true) }
            var confirmEnd by rememberSaveable { mutableStateOf(false) }

            // 여행 화면에 들어오면 위치·마이크 권한을 확인하고, GPS 기록과 차량 신호 기록, 녹음을 시작한다.
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                if (LocationReader.hasPermission(context)) vm.startLocationTracking()
                else vm.showMessage("위치 권한이 없어 주행 경로를 기록하지 않아요.")
                // 음성 안내(S04)가 떠 있는 동안에는 녹음하지 않는다. "알겠어요, 출발"을 누르면 시작한다.
                if (!showGuide) vm.onScreenShown(context.hasMic())
            }
            LaunchedEffect(tripId) {
                vm.startVehicleSignalTracking()
                val missing = LocationReader.missingPermissions(context) +
                    listOfNotNull(Manifest.permission.RECORD_AUDIO.takeIf { !context.hasMic() })
                if (missing.isEmpty()) vm.startLocationTracking()
                if (missing.isNotEmpty()) permissions.launch(missing)
            }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!showGuide) vm.onScreenShown(context.hasMic()) }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onScreenHidden() }

            // 음성 명령 결과 알림은 4초 뒤에 사라진다.
            LaunchedEffect(state.voiceFeedback) {
                if (state.voiceFeedback != null) {
                    delay(4_000)
                    vm.consumeVoiceFeedback()
                }
            }

            // "이 순간 담기": 누른 뒤 2초간 막고 완료 문구를 보인다.
            var momentDone by remember { mutableStateOf(false) }
            LaunchedEffect(momentDone) {
                if (momentDone) {
                    delay(2_000)
                    momentDone = false
                }
            }
            val driving = state.toDriving(now, capture)
                .copy(showTitlesWhileDriving = summarySettings.showTitlesWhileDriving, momentDone = momentDone)
            val events = DrivingEvents(
                onTab = onTab,
                onMoment = {
                    if (!momentDone) {
                        momentDone = true
                        vm.startRecording()
                        vm.showMessage("지금 나눈 대화를 담았어요")
                    }
                },
                onVoiceExample = vm::onVoiceCommand,
                onToastSay = vm::onVoiceCommand,
                // 진행 알약을 누르면 여행을 직접 끝낼 수 있다(정차 중일 때만). 도착 자동 종료가 안 될 때를 위한 길.
                onTripPill = { if (!restricted) confirmEnd = true },
            )
            WithMessages(state.message, vm::consumeMessage) {
                when {
                    showGuide -> VoiceGuideScreen(
                        state = VoiceGuideUiState(background = driving),
                        events = events,
                        onStart = {
                            showGuide = false
                            vm.askCurrentQuestion()
                            vm.onScreenShown(context.hasMic())
                        },
                    )
                    confirmEnd -> DrivingLayout(driving, events, overlay = {
                        EndTripDialog(
                            onEnd = {
                                confirmEnd = false
                                val name = state.ribbon?.destinationName ?: state.data?.trip?.destination.orEmpty()
                                vm.endTrip { navController.go(V3Routes.arrival(tripId, name)) }
                            },
                            onCancel = { confirmEnd = false },
                        )
                    })
                    else -> DrivingLayout(driving, events)
                }
            }
        }

        /* T01 진행 중인 여행 없음 */
        composable(V3Routes.TRIP_EMPTY) {
            // 여행이 시작되면(다른 화면에서) 바로 여행 화면으로 넘어간다.
            LaunchedEffect(home.ongoingTrip?.id) {
                home.ongoingTrip?.let { navController.go(V3Routes.trip(it.id)) }
            }
            TripEmptyScreen(onTab = onTab, onStartTrip = { navController.navigate(V3Routes.SETUP) })
        }

        /* S11 도착 */
        composable(
            V3Routes.ARRIVAL,
            arguments = tripIdArg + navArgument("name") { type = NavType.StringType; defaultValue = "" },
        ) { entry ->
            val tripId = entry.tripId()
            val vm: TripViewModel = viewModel(factory = AppViewModelProvider.trip(tripId))
            val state by vm.uiState.collectAsStateWithLifecycle()
            val now = rememberNow()
            val capture by TalkRec.state.collectAsStateWithLifecycle() // talk-rec: 상시 캡처 상태
            ArrivalScreen(
                state = state.toArrival(entry.arguments?.getString("name").orEmpty(), now, capture),
                events = DrivingEvents(onTab = onTab),
                onConfirm = { navController.go(V3Routes.making(tripId)) },
                onTimeline = { navController.go(V3Routes.diary(tripId)) },
                onNotYet = { vm.keepTraveling { navController.go(V3Routes.trip(tripId)) } },
            )
        }

        /* S12 다이어리 만드는 중 → 자동으로 S13 */
        composable(V3Routes.MAKING, arguments = tripIdArg) { entry ->
            val tripId = entry.tripId()
            val vm: TripViewModel = viewModel(factory = AppViewModelProvider.trip(tripId))
            val state by vm.uiState.collectAsStateWithLifecycle()
            var step by rememberSaveable { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                while (step < 3) {
                    delay(900)
                    step++
                }
                navController.go(V3Routes.ready(tripId))
            }
            MakingDiaryScreen(state = state.toMaking(step), onTab = onTab)
        }

        /* S13 다이어리 완성 */
        composable(V3Routes.READY, arguments = tripIdArg) { entry ->
            val tripId = entry.tripId()
            val vm: TripViewModel = viewModel(factory = AppViewModelProvider.trip(tripId))
            val state by vm.uiState.collectAsStateWithLifecycle()
            DiaryReadyScreen(
                state = state.toReady(),
                onTab = onTab,
                onOpenDiary = { openDiary(tripId) },
                onLater = { onTab(DbTab.Home) },
            )
        }

        /* R01 기록 지도 */
        composable(V3Routes.RECORDS) {
            val vm: DiaryListViewModel = viewModel(factory = AppViewModelProvider.DiaryList)
            val state by vm.uiState.collectAsStateWithLifecycle()
            if (state.isRestricted) {
                // 기록 지도를 보는 중에 출발하면 정차 안내(P01)로 바꾼다.
                StopNoticeScreen(
                    state = StopNoticeUiState(stopped = false, drivingHome = true, homeBackground = home.toV3().copy(driving = true)),
                    events = DrivingEvents(onTab = onTab),
                    onClose = { onTab(DbTab.Home) },
                )
                return@composable
            }
            RecordMapScreen(
                state = recordMapState(state.trips, state.ongoingTrip, state.route),
                onTab = onTab,
                // 기록 지도에서 연 다이어리는 뒤로 가기로 기록 지도에 돌아온다.
                onOpenRecord = { id -> id.toLongOrNull()?.let { navController.navigate(V3Routes.diary(it)) { launchSingleTop = true } } },
                onOpenLiveTrip = { state.ongoingTrip?.let { navController.go(V3Routes.trip(it.id)) } },
                realMap = { m ->
                    RouteMap(
                        routes = state.routes.map { (id, segments) -> RealMapRoute(id, RouteColors.forTrip(id), segments) },
                        modifier = m,
                        onRouteClick = { navController.navigate(V3Routes.diary(it)) { launchSingleTop = true } },
                    )
                },
            )
        }

        /* S14 다이어리 한 장 */
        composable(V3Routes.DIARY, arguments = tripIdArg) { entry ->
            val tripId = entry.tripId()
            val vm: TripViewModel = viewModel(factory = AppViewModelProvider.trip(tripId))
            val state by vm.uiState.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onScreenHidden() }
            val data = state.data
            if (data == null) {
                Box(Modifier.fillMaxSize().background(Color.White))
                return@composable
            }
            var editStep by rememberSaveable { mutableIntStateOf(0) } // 0 닫힘, 1 편집 메뉴, 2 지우기 확인
            val talks = remember(data) { data.talks() }
            val playing = talks.firstOrNull { it.path != null && it.path == state.playingPath }?.talk?.time
            Box(Modifier.fillMaxSize()) {
            DiaryScreen(
                state = DiaryUiState(
                    record = data.toRecord(),
                    live = data.trip.isOngoing && !restricted,
                    playingTime = playing,
                    recordingText = "여행 기록 중",
                ),
                onTab = onTab,
                onBack = { if (!navController.popBackStack()) onTab(DbTab.Record) },
                onNextPage = {
                    // 다음 장: 기록 순서(최신순)에서 이 기록 다음 것. 마지막이면 처음으로.
                    val ids = home.pastTrips.map { it.id }
                    if (ids.isNotEmpty()) {
                        val next = ids[(ids.indexOf(tripId) + 1).mod(ids.size)]
                        navController.navigate(V3Routes.diary(next)) { popUpTo(V3Routes.DIARY) { inclusive = true } }
                    }
                },
                onPlay = { talk -> talks.firstOrNull { it.talk == talk }?.path?.let(vm::togglePlay) },
                map = { m ->
                    RouteMap(
                        routes = listOf(RealMapRoute(tripId, RouteColors.forTrip(tripId), data.drive.path, data.drive.timeline)),
                        modifier = m,
                    )
                },
                // 끝난 여행만 편집할 수 있다. 진행 중인 여행은 편집 버튼 자리에 녹음 칩이 나온다.
                onEdit = if (data.trip.isOngoing) null else { { editStep = 1 } },
            )
            when (editStep) {
                1 -> EditDiaryDialog(
                    onDelete = { editStep = 2 },
                    onClose = { editStep = 0 },
                )
                2 -> DeleteTripDialog(
                    title = data.trip.title,
                    onDelete = {
                        editStep = 0
                        vm.deleteTrip { navController.go(V3Routes.RECORDS) }
                    },
                    onCancel = { editStep = 0 },
                )
            }
            }
        }

        /* P01·P02 정차 안내 */
        composable(
            V3Routes.STOP,
            arguments = listOf(navArgument("target") { type = NavType.StringType; defaultValue = V3Routes.RECORDS }),
        ) { entry ->
            val target = entry.arguments?.getString("target") ?: V3Routes.RECORDS
            val diaryTitle = target.removePrefix("diary/").toLongOrNull()
                ?.let { id -> home.pastTrips.firstOrNull { it.id == id }?.title }
            StopNoticeScreen(
                state = StopNoticeUiState(
                    stopped = !restricted,
                    readyBody = diaryTitle?.let { "안전하게 정차했어요. $it 다이어리를 열게요." } ?: "안전하게 정차했어요. 기록 지도를 열게요.",
                    drivingHome = true,
                    homeBackground = home.toV3().copy(driving = true),
                ),
                events = DrivingEvents(onTab = onTab),
                onContinue = { navController.go(target) },
                onClose = { if (!navController.popBackStack()) onTab(DbTab.Home) },
            )
        }
    }
}

/** 여행을 직접 끝낼 때 묻는 창 (v3 대화상자 모양). */
@Composable
private fun EndTripDialog(onEnd: () -> Unit, onCancel: () -> Unit) {
    val c = DbTheme.colors
    val t = DbTheme.type
    DbDialog("여행을 마칠까요?") {
        Text("여행을 마칠까요?", style = t.titleLg, color = c.ink)
        Text("지금까지의 대화와 달린 길이 한 권의 다이어리로 정리돼요.", style = t.body, color = c.ink2)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PrimaryButton("여행 마치기", onEnd, capWide = false)
            DbTextButton("계속 여행하기", onCancel)
        }
    }
}

/** S14 "편집": 지금은 기록 지우기만 있다. 사진 붙이기·메모 고치기는 나중에 여기에 더한다. */
@Composable
private fun EditDiaryDialog(onDelete: () -> Unit, onClose: () -> Unit) {
    val c = DbTheme.colors
    val t = DbTheme.type
    DbDialog("기록 편집") {
        Text("기록 편집", style = t.titleLg, color = c.ink)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlineButton("이 기록 지우기", onDelete, capWide = false)
            DbTextButton("닫기", onClose)
        }
    }
}

/** 기록을 지우기 전에 한 번 더 묻는다. 지우면 되돌릴 수 없다. */
@Composable
private fun DeleteTripDialog(title: String, onDelete: () -> Unit, onCancel: () -> Unit) {
    val c = DbTheme.colors
    val t = DbTheme.type
    DbDialog("이 기록을 지울까요?", alert = true) {
        Text("이 기록을 지울까요?", style = t.titleLg, color = c.ink)
        Text("${title}의 경로, 목소리, 사진이 모두 지워지고 되돌릴 수 없어요.", style = t.body, color = c.ink2)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PrimaryButton("지우기", onDelete, capWide = false)
            DbTextButton("취소", onCancel)
        }
    }
}

/** ViewModel 의 1회성 메시지를 아래쪽 알림(Snackbar)으로 보여 준다. */
@Composable
private fun WithMessages(message: String?, onConsumed: () -> Unit, content: @Composable () -> Unit) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            host.showSnackbar(message)
            onConsumed()
        }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
}
