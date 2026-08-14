package com.mttd.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mttd.data.prefs.OverlayPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import com.mttd.domain.models.SessionState

/**
 * WindowManager 를 통해 게임 위에 뜨는 두 오버레이 뷰(HUD + Icon) 를 관리.
 *
 * 라이프사이클:
 * - [attach] : 최초 마운트. Icon 만 붙임.
 * - [showHud]/[hideHud] : HUD 토글.
 * - [detach] : 두 뷰 모두 제거.
 *
 * Compose 뷰가 정상 작동하려면 아래 3종 owner 세팅 필수:
 *   - LifecycleOwner   (Service 가 LifecycleService)
 *   - ViewModelStoreOwner (여기서 자체 store)
 *   - SavedStateRegistryOwner (Service 가 제공)
 */
class OverlayHost(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateOwner: SavedStateRegistryOwner,
    private val sessionState: StateFlow<SessionState>,
    private val priceState: StateFlow<com.mttd.data.prices.PriceRepository.State>,
    private val prefs: OverlayPrefs,
) : ViewModelStoreOwner {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var iconView: ComposeView? = null
    private var hudView: ComposeView? = null
    private var itemView: ComposeView? = null

    // 접힌 상태도 핵심 수치를 읽을 수 있는 가로 요약 바.
    private val iconParams = defaultParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }
    // 높이는 WRAP_CONTENT — 고정 높이면 해상도/글꼴 배율에 따라 아래가 잘리고,
    // 반대로 목록이 비었을 땐 빈 공간이 크게 남는다.
    // 목록이 길어질 때의 상한은 HudOverlay 안에서 heightIn(max) 로 잡고 거기서만 스크롤.
    private val hudParams = defaultParams(dip(280), WindowManager.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.TOP or Gravity.START
        // HUD 는 터치 가능 (버튼용). Icon 이 게임 입력을 뺏지 않도록 flags 조정은 필요 시.
    }
    private val itemParams = defaultParams(dip(190), WindowManager.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    // ViewModelStore
    private val store = androidx.lifecycle.ViewModelStore()
    override val viewModelStore: androidx.lifecycle.ViewModelStore get() = store

    fun attach() {
        if (iconView != null || hudView != null) return
        var savedIconX = runBlocking { prefs.iconX.first() }
        val savedIconY = runBlocking { prefs.iconY.first() }
        // 기존 설치본도 한 번은 앱 카드의 왼쪽 시작선(20dp)에 맞춘다.
        if (!runBlocking { prefs.miniPanelCardAlignmentApplied.first() }) {
            savedIconX = dip(20)
            runBlocking {
                prefs.setIconPosition(savedIconX, savedIconY)
                prefs.markMiniPanelCardAlignmentApplied()
            }
        }
        val savedHudX = runBlocking { prefs.hudX.first() }
        val savedHudY = runBlocking { prefs.hudY.first() }
        val hudVisible = runBlocking { prefs.hudVisible.first() }
        val alpha = runBlocking { prefs.hudAlpha.first() }

        iconParams.x = savedIconX
        iconParams.y = savedIconY
        hudParams.x = savedHudX
        hudParams.y = savedHudY
        hudParams.alpha = alpha

        mountIcon()

        if (hudVisible) showHud()
    }

    /** 접힌 요약 바를 마운트한다. 상세 HUD와 동시에 존재하지 않는다. */
    private fun mountIcon() {
        if (iconView != null) return
        val icon = buildComposeView {
            IconOverlay(
                sessionState = sessionState,
                metricFlow = prefs.miniPanelMetrics,
                controlFlow = prefs.miniPanelControls,
                netValueFlow = prefs.miniPanelNetValue,
                maxPanelWidthPx = (systemDisplayWidthPx() - dip(40)).coerceAtLeast(dip(260)),
                onTogglePause = {
                    com.mttd.TrackerApplication.instance.trackerService.value?.togglePause()
                },
                onReset = {
                    com.mttd.TrackerApplication.instance.trackerService.value?.resetSession()
                },
                onToggleItems = { toggleItemPanel() },
                onExitApp = {
                    com.mttd.TrackerApplication.instance.trackerService.value?.exitApp()
                },
            )
        }
        attachDragBehavior(
            view = icon,
            params = iconParams,
            onTap = { toggleHud() },
            onDone = { x, y -> ownScope.launch { prefs.setIconPosition(x, y) } },
        )
        try {
            wm.addView(icon, iconParams)
            iconView = icon
        } catch (t: Throwable) {
            Log.e(TAG, "addView icon failed", t)
        }
    }

    fun detach() {
        iconView?.let { safeRemove(it) }
        hudView?.let { safeRemove(it) }
        itemView?.let { safeRemove(it) }
        iconView = null
        hudView = null
        itemView = null
    }

    fun showHud() {
        if (hudView != null) return
        hideItemPanel()
        // 상세를 볼 때는 요약 바를 제거해 둘 중 하나만 보이게 한다.
        iconView?.let { safeRemove(it) }
        iconView = null
        val hud = buildComposeView {
            HudOverlay(
                sessionState = sessionState,
                priceState = priceState,
                onCollapse = { hideHud() },
                onExitApp = {
                    com.mttd.TrackerApplication.instance.trackerService.value?.exitApp()
                },
                onOpenSettings = {
                    val i = android.content.Intent(context, com.mttd.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                },
                onTogglePause = {
                    com.mttd.TrackerApplication.instance.trackerService.value?.togglePause()
                },
                onRefreshHoldings = {
                    com.mttd.TrackerApplication.instance.trackerService.value?.refreshHoldings()
                },
                onReset = {
                    com.mttd.TrackerApplication.instance.trackerService.value?.resetSession()
                },
            )
        }
        attachDragBehavior(
            view = hud,
            params = hudParams,
            onTap = null,       // HUD 는 Compose 버튼(접기 아이콘)이 자체적으로 클릭 처리
            onDone = { x, y -> ownScope.launch { prefs.setHudPosition(x, y) } },
        )
        try {
            wm.addView(hud, hudParams)
            hudView = hud
            ownScope.launch { prefs.setHudVisible(true) }
        } catch (t: Throwable) {
            Log.e(TAG, "addView hud failed", t)
            mountIcon()
        }
    }

    fun hideHud() {
        hudView?.let { safeRemove(it) }
        hudView = null
        ownScope.launch { prefs.setHudVisible(false) }
        mountIcon()
    }

    fun toggleHud() {
        if (hudView == null) showHud() else hideHud()
    }

    /** `템` 버튼은 아이템 패널을 열고 닫는다. */
    private fun toggleItemPanel() {
        if (itemView == null) showItemPanel() else hideItemPanel()
    }

    private fun showItemPanel() {
        if (itemView != null) return
        val savedX = runBlocking { prefs.itemPanelX.first() }
        val savedY = runBlocking { prefs.itemPanelY.first() }
        if (savedX == OverlayPrefs.POSITION_UNSET || savedY == OverlayPrefs.POSITION_UNSET) {
            itemParams.x = (iconParams.x + dip(220)).coerceAtMost(systemDisplayWidthPx() - itemParams.width)
            itemParams.y = iconParams.y
        } else {
            itemParams.x = savedX
            itemParams.y = savedY
        }
        val item = buildComposeView { ItemOverlay(sessionState) }
        attachDragBehavior(
            view = item,
            params = itemParams,
            onTap = null,
            onDone = { x, y -> ownScope.launch { prefs.setItemPanelPosition(x, y) } },
        )
        try {
            wm.addView(item, itemParams)
            itemView = item
        } catch (t: Throwable) {
            Log.e(TAG, "addView item panel failed", t)
        }
    }

    private fun hideItemPanel() {
        itemView?.let { safeRemove(it) }
        itemView = null
    }

    fun setHudAlpha(alpha: Float) {
        hudParams.alpha = alpha.coerceIn(0.2f, 1f)
        hudView?.let { wm.updateViewLayout(it, hudParams) }
        ownScope.launch { prefs.setHudAlpha(alpha) }
    }

    /** 회전 시 Compose가 새 화면 폭으로 다시 측정되도록 미니패널을 마운트한다. */
    fun onDisplayConfigurationChanged() {
        if (iconView == null) return
        iconView?.let { safeRemove(it) }
        iconView = null
        mountIcon()
    }

    private fun buildComposeView(
        content: @androidx.compose.runtime.Composable () -> Unit,
    ): ComposeView {
        val v = ComposeView(context)
        v.setViewTreeLifecycleOwner(lifecycleOwner)
        v.setViewTreeViewModelStoreOwner(this)
        v.setViewTreeSavedStateRegistryOwner(savedStateOwner)
        v.setContent { content() }
        return v
    }

    private fun safeRemove(v: View) {
        try { wm.removeView(v) } catch (_: Throwable) {}
    }

    private fun defaultParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )

    /**
     * 롱프레스로 드래그 시작, 짧은 탭은 [onTap] 발동.
     *
     * 흐름:
     * - ACTION_DOWN: 롱프레스 타이머 예약 (400ms 후).
     * - ACTION_MOVE:
     *   - 롱프레스 이전: TOUCH_SLOP 초과 이동이면 롱프레스 취소 (스크롤/오탭 방지).
     *   - 롱프레스 이후: 위치 갱신.
     * - ACTION_UP:
     *   - 롱프레스 상태에서 드래그 했다면 위치 저장.
     *   - 이동 없이 짧게 뗐다면 [onTap] 호출.
     *
     * [onTap] 이 null 이면 Compose 로 클릭 이벤트가 propagate 됨 (HUD 내부 버튼용).
     */
    private fun attachDragBehavior(
        view: View,
        params: WindowManager.LayoutParams,
        onTap: (() -> Unit)? = null,
        onDone: (Int, Int) -> Unit,
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var startTouchX = 0f
        var startTouchY = 0f
        var startParamsX = 0
        var startParamsY = 0
        var longPressed = false
        var moved = false
        val slop2 = TOUCH_SLOP * TOUCH_SLOP

        var longPressRunnable: Runnable? = null

        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = ev.rawX
                    startTouchY = ev.rawY
                    startParamsX = params.x
                    startParamsY = params.y
                    moved = false
                    longPressed = false
                    longPressRunnable = Runnable {
                        longPressed = true
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                    handler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startTouchX
                    val dy = ev.rawY - startTouchY
                    if (!moved && (dx * dx + dy * dy) > slop2) {
                        moved = true
                        // 롱프레스 전에 움직였으면 = 드래그 아닌 스크롤/실수 → 롱프레스 취소
                        if (!longPressed) {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                        }
                    }
                    if (longPressed) {
                        params.x = startParamsX + dx.toInt()
                        params.y = startParamsY + dy.toInt()
                        try { wm.updateViewLayout(v, params) } catch (_: Throwable) {}
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = null
                    when {
                        longPressed -> {
                            onDone(params.x, params.y)
                            true
                        }
                        !moved && onTap != null -> {
                            onTap()
                            true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    private fun dip(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /** 앱 호환 폭과 무관한 Android 시스템의 실제 디스플레이 가로 px. */
    private fun systemDisplayWidthPx(): Int =
        android.content.res.Resources.getSystem().displayMetrics.widthPixels


    companion object {
        private const val TAG = "mTTD.Overlay"
        private const val TOUCH_SLOP = 20f
        private const val LONG_PRESS_MS = 400L
    }
}
