package com.mttd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.mttd.MainActivity
import com.mttd.R
import com.mttd.TrackerApplication
import com.mttd.data.items.ItemInfoLookup
import com.mttd.data.log.LogLineFilter
import com.mttd.data.log.LogPoller
import com.mttd.data.log.MessageAssembler
import com.mttd.data.log.OffsetStore
import com.mttd.data.prefs.OverlayPrefs
import com.mttd.data.prices.PriceApi
import com.mttd.data.prices.PriceRepository
import com.mttd.domain.SessionAggregator
import com.mttd.domain.ValueCalculator
import com.mttd.domain.models.SessionState
import com.mttd.ui.overlay.OverlayHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 포그라운드 서비스 — 로그 폴러 호스트.
 *
 * 앱 UI 는 [LocalBinder] 로 바인딩해서 [status]/[lines] 를 관찰.
 */
class TrackerForegroundService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var poller: LogPoller? = null
    private var pollerBootstrap: kotlinx.coroutines.Job? = null
    private lateinit var offsetStore: OffsetStore
    private lateinit var itemInfo: ItemInfoLookup
    private lateinit var aggregator: SessionAggregator
    private lateinit var overlayPrefs: OverlayPrefs
    private lateinit var priceRepo: PriceRepository
    private lateinit var observedPrices: com.mttd.data.prices.ObservedPriceStore
    private lateinit var runRepo: com.mttd.data.runs.RunRepository
    private var overlay: OverlayHost? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onDisplayConfigurationChanged()
    }
    private val assembler = MessageAssembler()
    private val characterLoadoutTracker = com.mttd.domain.CharacterLoadoutTracker()

    /**
     * 마지막으로 관측된 캐릭터 장비/스킬/석판 스냅샷. 로그인 직후 한 번 오는 `GetPlayerData`
     * 전체 동기화를 파싱한 결과라, 앱을 켠 뒤 게임에 재접속(또는 로그인)해야 채워진다 —
     * [com.mttd.domain.CharacterLoadoutTracker] 클래스 주석 참조.
     */
    val loadoutState: StateFlow<com.mttd.data.export.CharacterLoadout?> get() = characterLoadoutTracker.loadout
    fun currentLoadout(): com.mttd.data.export.CharacterLoadout? = characterLoadoutTracker.latestLoadout
    fun currentLoadoutSyncedAtMs(): Long = characterLoadoutTracker.latestSyncAtMs

    val priceState get() = priceRepo.state
    fun priceRepository(): PriceRepository = priceRepo
    /** 경매장에서 직접 조회한 시세 (스냅샷보다 우선). */
    val observedPriceState get() = observedPrices.prices
    fun clearObservedPrices() = observedPrices.clear()
    /** 아이템 이름 조회. UI 가 따로 만들면 311 KB JSON 을 한 번 더 파싱하게 되므로 이걸 쓴다. */
    fun itemInfoLookup(): ItemInfoLookup = itemInfo

    /** 사용 가능한 새 버전. null 이면 최신이거나 아직 확인 전. */
    private val _availableUpdate =
        MutableStateFlow<com.mttd.data.update.UpdateChecker.Update?>(null)
    val availableUpdate: StateFlow<com.mttd.data.update.UpdateChecker.Update?> =
        _availableUpdate.asStateFlow()

    /**
     * 업데이트 확인. 서비스 시작 시 [checkForUpdateOnce] 로 1 회 자동 호출되고, 설정 탭의
     * "업데이트 확인" 버튼으로 수동으로도 부를 수 있다 (그래서 public + suspend).
     *
     * 새 버전은 UI가 [com.mttd.data.update.AppUpdateInstaller]를 통해 시스템 업데이트 화면으로 연다.
     */
    suspend fun checkForUpdate(): com.mttd.data.update.UpdateChecker.CheckResult {
        val result = com.mttd.data.update.UpdateChecker().check()
        _availableUpdate.value = (result as? com.mttd.data.update.UpdateChecker.CheckResult.UpdateAvailable)?.update
        return result
    }

    private fun checkForUpdateOnce() {
        lifecycleScope.launch { checkForUpdate() }
    }


    private val _status = MutableStateFlow(LogPoller.PollingStatus())
    val status: StateFlow<LogPoller.PollingStatus> = _status.asStateFlow()

    val sessionState: StateFlow<SessionState>
        get() = aggregator.state

    /** 진단 화면용 — 맵 안/밖 판정이 바뀐 이력. */
    val mapPresenceLog: StateFlow<List<SessionAggregator.MapPresenceEvent>>
        get() = aggregator.mapPresenceLog

    /** 진단 화면에 보여줄 마지막 원본 로그 10줄. 구독 타이밍과 무관하게 항상 유지한다. */
    private val _recentLines = MutableStateFlow<List<String>>(emptyList())
    val recentLines: StateFlow<List<String>> = _recentLines.asStateFlow()

    fun resetSession() {
        aggregator.resetSession()
        lifecycleScope.launch { runRepo.clear() }
    }
    fun togglePause() = aggregator.togglePause()

    /** "자산" 탭/거래소 HUD 의 수동 새로고침 버튼에서 호출. */
    fun refreshHoldings() = aggregator.refreshHoldings()

    /**
     * 잘못 집계된 회차 삭제. 세션 총합·아이템 합계도 남은 회차 기준으로 재계산된다.
     * 완료된 회차의 아이템 목록은 디스크에 있으므로 먼저 읽어와서 차감분을 넘긴다.
     */
    fun deleteRun(runId: Long) {
        lifecycleScope.launch {
            val items = runRepo.loadItems(runId)
            aggregator.deleteRun(runId, items)
            runRepo.delete(runId)
        }
    }

    /** 상세 팝업용 — 해당 회차의 아이템 목록을 디스크에서 읽는다. */
    suspend fun loadRunItems(runId: Long): List<com.mttd.domain.models.PickupSummary> {
        // 진행 중인 회차는 아직 디스크에 없으므로 메모리에서.
        aggregator.state.value.runs.firstOrNull { it.id == runId }
            ?.takeIf { it.inProgress }?.let { return it.items }
        return runRepo.loadItems(runId)
    }

    inner class LocalBinder : Binder() {
        val service: TrackerForegroundService get() = this@TrackerForegroundService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        offsetStore = OffsetStore(applicationContext)
        itemInfo = ItemInfoLookup(applicationContext)
        overlayPrefs = OverlayPrefs(applicationContext)
        priceRepo = PriceRepository(PriceApi(), onTtdItemsFetched = { items ->
            val extra = items.mapValues { (_, item) ->
                ItemInfoLookup.ItemInfo(name = item.name, type = item.type)
            }
            itemInfo.mergeGapFill(extra)
        })
        observedPrices = com.mttd.data.prices.ObservedPriceStore()
        runRepo = com.mttd.data.runs.RunRepository(applicationContext, itemInfo)
        aggregator = SessionAggregator(
            itemInfo = itemInfo,
            valueCalculator = ValueCalculator(priceRepo, observedPrices),
            observedPrices = observedPrices,
            mapNames = com.mttd.data.maps.MapNameLookup(applicationContext),
            // 완료된 회차의 아이템 목록은 디스크로. 메모리엔 요약만 남는다.
            onRunFinished = { run -> lifecycleScope.launch { runRepo.save(run) } },
        )
        lifecycleScope.launch {
            overlayPrefs.timeTrackingMode.collect { aggregator.setTimeTrackingMode(it) }
        }
        ensureNotificationChannel()
        TrackerApplication.instance.setTrackerService(this)
        startPriceRefreshLoop()
        checkForUpdateOnce()
        clearStoredRuns()
    }

    /**
     * 서비스가 새로 뜨면 이전 세션의 회차를 버리고 처음부터 시작한다.
     *
     * 예전에는 디스크에서 회차를 복원했는데, **수익만 살아나고 시간은 0 부터 시작**해서
     * 시간당 수익이 터무니없이 커졌다 (복원된 총수익 ÷ 방금 시작한 시간). 수익과 시간은
     * 짝이라 한쪽만 복원할 수 없고, 시간까지 저장하려면 회차마다 누적값을 따로 남겨야 한다.
     * 세션을 새로 시작하면 둘이 항상 맞는다.
     *
     * 지우지 않고 남겨두면 더 나쁘다 — `nextRunId` 가 1 부터 다시 시작하는데 디스크에는
     * 같은 id 가 남아 있어서 새 회차 저장이 PRIMARY KEY 충돌로 **조용히 실패**하고
     * ([com.mttd.data.runs.RunRepository.save] 가 예외를 삼킨다), 상세 팝업은 엉뚱한
     * 옛 회차의 아이템을 보여준다.
     *
     * 서비스가 죽는 경우: 최근 앱에서 밀기(`stopWithTask=true`), 앱 업데이트, 설정의
     * "앱 종료", 시스템 메모리 회수.
     *
     * **알려진 동작**: 위치(`inMap`) 도 같이 초기화된다. 맵 안에 서 있는 채로 서비스가
     * 새로 뜨면 "대기중" 으로 보이고, `inMap` 을 되살리는 경로가 맵 열기(`Spv3Open`) 뿐이라
     * 다음 맵을 열 때까지 MAP_ONLY 시간이 안 흐른다. 위치를 따로 저장하면 이을 수 있지만,
     * 맵 하나 분량이라 그대로 두기로 했다 (2026-08-14 결정).
     */
    private fun clearStoredRuns() {
        lifecycleScope.launch {
            runRepo.clear()
            Log.i(TAG, "cleared stored runs — starting a fresh session")
        }
    }

    /**
     * 시세를 서비스가 직접 가져온다.
     *
     * 예전엔 [com.mttd.ui.onboarding.OnboardingScreen] 의 LaunchedEffect 에서만 fetch 해서,
     * 앱 UI 를 한 번도 안 열면 가격 맵이 비어 있고 모든 픽업이 0 으로 계산됐다.
     * (오버레이만 띄우고 게임을 하면 수익이 계속 0)
     */
    private fun startPriceRefreshLoop() {
        lifecycleScope.launch {
            // 저장된 시세 출처 복원 (첫 fetch 전에)
            runCatching {
                priceRepo.restoreSource(
                    com.mttd.data.prices.PriceSource.fromId(overlayPrefs.priceSourceId.first())
                )
            }
            while (true) {
                val r = priceRepo.refreshLatest()
                val ok = r.isSuccess && priceRepo.state.value.itemsWithPrice > 1
                // 성공하면 TTL(1h) 주기로 재확인, 실패하면 1분 뒤 재시도.
                kotlinx.coroutines.delay(if (ok) PRICE_REFRESH_INTERVAL_MS else PRICE_RETRY_INTERVAL_MS)
            }
        }
    }

    fun ensureOverlay(): OverlayHost {
        if (overlay == null) {
            overlay = OverlayHost(
                context = this,
                lifecycleOwner = this,
                savedStateOwner = this,
                sessionState = aggregator.state,
                priceState = priceRepo.state,
                prefs = overlayPrefs,
            ).also { it.attach() }
        }
        return overlay!!
    }

    fun destroyOverlay() {
        overlay?.detach()
        overlay = null
    }

    fun overlayHost(): OverlayHost? = overlay

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForegroundOnce()
                val path = intent.getStringExtra(EXTRA_LOG_PATH)
                if (path != null) startPoller(path) else ensurePollerRunning()
                ensureOverlayIfEnabled()
            }
            ACTION_STOP -> {
                teardown()
                return START_NOT_STICKY
            }
            ACTION_SHOW_OVERLAY -> {
                startForegroundOnce()   // Overlay 만 켜는 경우도 서비스는 foreground 유지
                lifecycleScope.launch {
                    overlayPrefs.setGamePanelEnabled(true)
                    ensureOverlay()
                }
                ensurePollerRunning()
            }
            ACTION_HIDE_OVERLAY -> {
                lifecycleScope.launch { overlayPrefs.setGamePanelEnabled(false) }
                destroyOverlay()
            }
            ACTION_SHOW_HUD -> {
                ensureOverlay().showHud()
                ensurePollerRunning()
            }
            ACTION_HIDE_HUD -> {
                overlay?.hideHud()
            }
            // intent == null: START_STICKY 로 시스템이 서비스를 되살린 경우.
            // 예전엔 어느 분기에도 안 걸려서 foreground 승격도, 폴러 시작도 안 됐다.
            else -> {
                startForegroundOnce()
                ensurePollerRunning()
                ensureOverlayIfEnabled()
            }
        }
        return START_STICKY
    }

    /**
     * 폴러가 안 돌고 있으면 서비스가 **스스로** 게임 패키지를 찾아 폴링을 시작한다.
     *
     * 예전엔 폴러 시작 경로가 `MainActivity.onResume()` 하나뿐이라,
     * 오버레이만 띄우거나 시스템이 서비스를 재시작한 상태에서는 로그를 아예 안 읽었다.
     * → "가방 정렬하고 앱을 다녀와야 동작" 의 직접 원인.
     *
     * Shizuku 가 아직 준비 안 됐으면 준비될 때까지 재시도한다.
     */
    @Synchronized
    fun ensurePollerRunning() {
        if (poller != null || pollerBootstrap?.isActive == true) return
        pollerBootstrap = lifecycleScope.launch {
            val shizuku = TrackerApplication.instance.shizukuManager
            while (poller == null) {
                if (!shizuku.state.value.ready) {
                    // 권한 요청 다이얼로그는 띄우지 않는다 (3 초마다 반복될 수 있으므로).
                    // 권한 승인은 앱 UI 에서만 하고, 여기서는 바인딩만 재시도.
                    shizuku.bindIfPermitted()
                    kotlinx.coroutines.delay(POLLER_BOOTSTRAP_RETRY_MS)
                    continue
                }
                val path = resolveLogPath(shizuku.service)
                if (path == null) {
                    kotlinx.coroutines.delay(POLLER_BOOTSTRAP_RETRY_MS)
                    continue
                }
                startPoller(path)
            }
        }
    }

    private suspend fun resolveLogPath(userService: com.mttd.IUserService?): String? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val svc = userService ?: return@withContext null
            val pkg = try {
                svc.listInstalledGamePackages().lines().firstOrNull { it.isNotBlank() }
            } catch (t: Throwable) {
                Log.w(TAG, "listInstalledGamePackages failed", t); null
            } ?: return@withContext null
            "/sdcard/Android/data/$pkg/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"
        }

    private fun startPoller(path: String) {
        stopPoller()
        val shizuku = TrackerApplication.instance.shizukuManager
        val p = LogPoller(
            service = { shizuku.service },
            offsetStore = offsetStore,
            logPath = path,
        )
        poller = p
        // status/lines forwarding
        lifecycleScope.launch { p.status.collect { _status.value = it } }
        lifecycleScope.launch {
            p.lines.collect { line ->
                // SharedFlow replay는 진단 화면의 구독 교체 타이밍에 비어 보일 수 있다.
                // 상태로 마지막 10줄을 직접 유지해, 읽은 로그와 화면이 반드시 일치하게 한다.
                _recentLines.value = (_recentLines.value + line).takeLast(MAX_RECENT_DEBUG_LINES)
                // 라인 단위 관측 (맵 코드네임 등 assembler 밖 컨텍스트)
                aggregator.observeLine(line)
                characterLoadoutTracker.observeLine(line)
                // 필터 통과분만 assembler 에 공급
                if (!LogLineFilter.shouldExclude(line)) {
                    assembler.feed(line)?.let { msg -> aggregator.consume(msg) }
                }
            }
        }
        p.start()
        Log.i(TAG, "poller started: $path")
        // 준비가 다 끝났으면(오버레이 권한 있음) 굳이 버튼을 누르게 하지 않고 바로 띄운다.
        autoShowOverlay()
    }

    /**
     * Shizuku·폴러·오버레이 권한이 모두 갖춰졌으면 오버레이를 자동으로 표시.
     *
     * 사용자가 명시적으로 숨긴 상태(HUD 접기와 별개로 오버레이 자체를 끈 경우)는 존중해야 하므로
     * 이미 붙어 있으면 아무것도 하지 않는다 ([ensureOverlay] 가 멱등).
     */
    private fun autoShowOverlay() {
        if (overlay != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        ensureOverlayIfEnabled()
    }

    private fun ensureOverlayIfEnabled() {
        lifecycleScope.launch {
            if (!overlayPrefs.gamePanelEnabled.first()) return@launch
            if (!android.provider.Settings.canDrawOverlays(this@TrackerForegroundService)) return@launch
            ensureOverlay()
            Log.i(TAG, "overlay auto-shown")
        }
    }

    private fun stopPoller() {
        pollerBootstrap?.cancel()
        pollerBootstrap = null
        poller?.stop()
        poller = null
    }

    /** 폴러·오버레이·알림을 걷고 서비스를 내린다. 액티비티는 그대로 남는다. */
    private fun teardown() {
        stopPoller()
        destroyOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * mTTD 를 통째로 끈다 — 설정의 "앱 종료" 와 같은 결과다.
     *
     * 서비스만 내리면 액티비티와 최근 앱의 카드가 그대로 남아서, 오버레이만 사라지고
     * "종료했는데 앱은 아직 열려 있는" 상태가 된다. 태스크까지 걷어내야 완전히 닫힌다.
     *
     * 순서가 중요하다: 매니페스트가 `stopWithTask=true` 라 태스크를 먼저 없애면 서비스가
     * 그 자리에서 죽고, 뒤늦게 도착한 정리 요청이 서비스를 다시 띄운다. 그래서 여기서는
     * 인텐트를 거치지 않고 직접 정리한 뒤 태스크를 제거한다.
     *
     * 한 틱 미루는 이유: 이 호출은 오버레이 버튼의 터치 처리 도중에 들어오는데,
     * [destroyOverlay] 가 바로 그 뷰를 WindowManager 에서 떼어낸다. 터치 이벤트 전달이
     * 끝난 다음에 걷어내도록 메인 루퍼로 넘긴다.
     */
    fun exitApp() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            teardown()
            runCatching {
                getSystemService(android.app.ActivityManager::class.java)
                    ?.appTasks
                    ?.forEach { it.finishAndRemoveTask() }
            }.onFailure { Log.w(TAG, "finishAndRemoveTask failed", it) }
        }
    }

    override fun onDestroy() {
        stopPoller()
        destroyOverlay()
        TrackerApplication.instance.setTrackerService(null)
        super.onDestroy()
    }

    private fun startForegroundOnce() {
        val notif = buildNotification("로그 감시 중")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "KD mTTD 로그 감시",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "게임 로그 파일을 폴링하는 백그라운드 서비스" }
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "mTTD.Service"
        private const val CHANNEL_ID = "tli_log_watch"
        private const val NOTIF_ID = 42
        /** 시세 TTL 과 동일 (PriceRepository.TTL_MS = 1h). */
        private const val PRICE_REFRESH_INTERVAL_MS = 60L * 60 * 1000
        private const val PRICE_RETRY_INTERVAL_MS = 60L * 1000
        /** Shizuku 준비 / 게임 패키지 탐색 재시도 간격. */
        private const val POLLER_BOOTSTRAP_RETRY_MS = 3_000L
        private const val MAX_RECENT_DEBUG_LINES = 10
        const val ACTION_START = "com.mttd.action.START_LOG"
        const val ACTION_STOP = "com.mttd.action.STOP_LOG"
        const val ACTION_SHOW_OVERLAY = "com.mttd.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.mttd.action.HIDE_OVERLAY"
        const val ACTION_SHOW_HUD = "com.mttd.action.SHOW_HUD"
        const val ACTION_HIDE_HUD = "com.mttd.action.HIDE_HUD"
        const val EXTRA_LOG_PATH = "log_path"

        fun start(context: Context, logPath: String) {
            val i = Intent(context, TrackerForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LOG_PATH, logPath)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        /**
         * 로그 경로를 모르는 상태에서 서비스만 띄운다.
         * 서비스가 Shizuku 준비를 기다렸다가 스스로 게임 패키지를 찾아 폴링을 시작한다.
         */
        fun startSelfManaged(context: Context) {
            val i = Intent(context, TrackerForegroundService::class.java).apply {
                action = ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, TrackerForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(i)
        }

        fun showOverlay(context: Context) {
            val i = Intent(context, TrackerForegroundService::class.java).apply {
                action = ACTION_SHOW_OVERLAY
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun hideOverlay(context: Context) {
            val i = Intent(context, TrackerForegroundService::class.java).apply {
                action = ACTION_HIDE_OVERLAY
            }
            context.startService(i)
        }
    }
}
