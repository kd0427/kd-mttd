package com.mttd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mttd.domain.models.TimeTrackingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 오버레이 위치/투명도 + 앱 설정 저장소.
 *
 * 위치 값은 dp 가 아닌 px 단위로 저장 (`WindowManager.LayoutParams.x/y` 와 직접 매치).
 * 서로 다른 해상도로 이동해도 화면 안이면 유효한 좌표.
 */
class OverlayPrefs(private val context: Context) {

    /** 시세 출처. 값은 [com.mttd.data.prices.PriceSource.id]. */
    val priceSourceId: Flow<String> = context.dataStore.data.map { it[KEY_PRICE_SOURCE] ?: "etor" }

    suspend fun setPriceSourceId(id: String) {
        context.dataStore.edit { it[KEY_PRICE_SOURCE] = id }
    }

    /** 배지(아이콘 오버레이) 2번째 줄에 표시할 수익 지표. 값은 [com.mttd.ui.overlay.BadgeIncomeMetric.id]. */
    val badgeIncomeMetric: Flow<String> = context.dataStore.data.map {
        it[KEY_BADGE_METRIC] ?: com.mttd.ui.overlay.BadgeIncomeMetric.DEFAULT.id
    }

    suspend fun setBadgeIncomeMetric(id: String) {
        context.dataStore.edit { it[KEY_BADGE_METRIC] = id }
    }

    /** 게임 위 미니패널에서 보여 줄 수치 목록. 최소 한 항목은 항상 유지한다. */
    val miniPanelMetrics: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[KEY_MINI_PANEL_METRICS]
            ?.takeIf { it.isNotEmpty() }
            ?: com.mttd.ui.overlay.MiniPanelMetric.DEFAULT_IDS
    }

    suspend fun setMiniPanelMetrics(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { it[KEY_MINI_PANEL_METRICS] = ids }
    }

    /**
     * 미니패널에 표시할 제어 버튼. 수치와 달리 **하나도 안 고를 수 있다** — 버튼을 다 끄면
     * 수치만 남는 패널이 되고, 제어는 탭해서 여는 상세 패널에서 하면 된다.
     *
     * 기본값이 빈 집합이라 "저장한 적 없음"과 "일부러 다 껐음"이 같은 결과다. DataStore 의
     * 빈 Set 이 null 로 읽히든 빈 Set 으로 읽히든 상관없다. 기본값을 비어 있지 않게 바꾸면
     * 그때는 둘을 구분할 방법이 따로 필요하다.
     */
    val miniPanelControls: Flow<Set<String>> = context.dataStore.data.map {
        it[KEY_MINI_PANEL_CONTROLS] ?: com.mttd.ui.overlay.MiniPanelControl.DEFAULT_IDS
    }

    suspend fun setMiniPanelControls(ids: Set<String>) {
        context.dataStore.edit { it[KEY_MINI_PANEL_CONTROLS] = ids }
    }

    /**
     * 미니패널의 수익을 세후(경매장 세금 1/8 차감)로 보여줄지. 기본은 세전(시장가) —
     * 지금까지의 동작이고, 상세 패널(HUD)이 세전·세후를 나란히 보여주는 기준도 세전이다.
     */
    val miniPanelNetValue: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_MINI_PANEL_NET_VALUE] ?: false
    }

    suspend fun setMiniPanelNetValue(v: Boolean) {
        context.dataStore.edit { it[KEY_MINI_PANEL_NET_VALUE] = v }
    }

    /** HUD 총 시간/시간당 수익의 집계 기준. */
    val timeTrackingMode: Flow<TimeTrackingMode> = context.dataStore.data.map {
        TimeTrackingMode.fromId(it[KEY_TIME_TRACKING_MODE] ?: TimeTrackingMode.MAP_ONLY.id)
    }

    suspend fun setTimeTrackingMode(mode: TimeTrackingMode) {
        context.dataStore.edit { it[KEY_TIME_TRACKING_MODE] = mode.id }
    }

    val iconX: Flow<Int> = context.dataStore.data.map { it[KEY_ICON_X] ?: 60 }
    val iconY: Flow<Int> = context.dataStore.data.map { it[KEY_ICON_Y] ?: 300 }
    val hudX: Flow<Int> = context.dataStore.data.map { it[KEY_HUD_X] ?: 60 }
    val hudY: Flow<Int> = context.dataStore.data.map { it[KEY_HUD_Y] ?: 400 }
    val itemPanelX: Flow<Int> = context.dataStore.data.map { it[KEY_ITEM_PANEL_X] ?: POSITION_UNSET }
    val itemPanelY: Flow<Int> = context.dataStore.data.map { it[KEY_ITEM_PANEL_Y] ?: POSITION_UNSET }
    val hudAlpha: Flow<Float> = context.dataStore.data.map { it[KEY_HUD_ALPHA] ?: 0.85f }
    val hudVisible: Flow<Boolean> = context.dataStore.data.map { it[KEY_HUD_VISIBLE] ?: false }

    /**
     * 미니패널(과 거기서 여는 템 목록 패널)의 표시 배율.
     *
     * 화면 크기가 제각각인 기기에서 각자 맞게 줄여 쓰라는 설정이다. 값은 Compose 밀도에
     * 곱해져 dp·sp 를 한꺼번에 줄이므로, 글자·아이콘·여백·버튼이 **같은 비율로** 작아진다 —
     * 패널을 통째로 축소한 그림이 되지, 글자만 작아져 헐렁해지지 않는다.
     *
     * 템 패널이 미니패널 배율을 따르는 건 둘이 나란히 붙어 뜨기 때문이다. 배율이 다르면
     * 같은 화면 안에서 글자 크기가 제각각으로 보인다.
     */
    val miniPanelScale: Flow<Float> = context.dataStore.data.map {
        (it[KEY_MINI_PANEL_SCALE] ?: 1f).coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE)
    }

    /** 상세 패널의 표시 배율. 미니패널과 따로 조절한다. 자세한 건 [miniPanelScale]. */
    val hudScale: Flow<Float> = context.dataStore.data.map {
        (it[KEY_HUD_SCALE] ?: 1f).coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE)
    }

    suspend fun setMiniPanelScale(v: Float) {
        context.dataStore.edit { it[KEY_MINI_PANEL_SCALE] = v.coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE) }
    }

    suspend fun setHudScale(v: Float) {
        context.dataStore.edit { it[KEY_HUD_SCALE] = v.coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE) }
    }
    /** 게임 위 패널 표시 여부. 기본값은 표시 상태. */
    val gamePanelEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_GAME_PANEL_ENABLED] ?: true }
    val miniPanelCardAlignmentApplied: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_MINI_PANEL_CARD_ALIGNMENT_APPLIED] ?: false
    }

    /** 최초 설정 가이드(마법사)를 완료했거나 건너뛴 적 있으면 true. */
    val wizardCompleted: Flow<Boolean> = context.dataStore.data.map { it[KEY_WIZARD_COMPLETED] ?: false }

    suspend fun setWizardCompleted(v: Boolean) {
        context.dataStore.edit { it[KEY_WIZARD_COMPLETED] = v }
    }

    suspend fun setIconPosition(x: Int, y: Int) {
        context.dataStore.edit {
            it[KEY_ICON_X] = x
            it[KEY_ICON_Y] = y
        }
    }

    suspend fun setHudPosition(x: Int, y: Int) {
        context.dataStore.edit {
            it[KEY_HUD_X] = x
            it[KEY_HUD_Y] = y
        }
    }

    suspend fun setItemPanelPosition(x: Int, y: Int) {
        context.dataStore.edit {
            it[KEY_ITEM_PANEL_X] = x
            it[KEY_ITEM_PANEL_Y] = y
        }
    }

    suspend fun setHudAlpha(a: Float) {
        context.dataStore.edit { it[KEY_HUD_ALPHA] = a.coerceIn(0.2f, 1f) }
    }

    suspend fun setHudVisible(v: Boolean) {
        context.dataStore.edit { it[KEY_HUD_VISIBLE] = v }
    }

    suspend fun setGamePanelEnabled(v: Boolean) {
        context.dataStore.edit { it[KEY_GAME_PANEL_ENABLED] = v }
    }

    suspend fun markMiniPanelCardAlignmentApplied() {
        context.dataStore.edit { it[KEY_MINI_PANEL_CARD_ALIGNMENT_APPLIED] = true }
    }

    companion object {
        private val Context.dataStore by preferencesDataStore(name = "overlay_prefs")
        private val KEY_ICON_X = intPreferencesKey("icon_x")
        private val KEY_ICON_Y = intPreferencesKey("icon_y")
        private val KEY_HUD_X = intPreferencesKey("hud_x")
        private val KEY_HUD_Y = intPreferencesKey("hud_y")
        private val KEY_ITEM_PANEL_X = intPreferencesKey("item_panel_x")
        private val KEY_ITEM_PANEL_Y = intPreferencesKey("item_panel_y")
        private val KEY_HUD_ALPHA = floatPreferencesKey("hud_alpha")
        private val KEY_HUD_VISIBLE = booleanPreferencesKey("hud_visible")
        private val KEY_GAME_PANEL_ENABLED = booleanPreferencesKey("game_panel_enabled")
        private val KEY_MINI_PANEL_CARD_ALIGNMENT_APPLIED = booleanPreferencesKey("mini_panel_card_alignment_applied")
        private val KEY_WIZARD_COMPLETED = booleanPreferencesKey("wizard_completed")
        private val KEY_PRICE_SOURCE = androidx.datastore.preferences.core.stringPreferencesKey("price_source")
        private val KEY_BADGE_METRIC = androidx.datastore.preferences.core.stringPreferencesKey("badge_income_metric")
        private val KEY_MINI_PANEL_METRICS = stringSetPreferencesKey("mini_panel_metrics")
        private val KEY_MINI_PANEL_CONTROLS = stringSetPreferencesKey("mini_panel_controls")
        private val KEY_MINI_PANEL_NET_VALUE = booleanPreferencesKey("mini_panel_net_value")
        private val KEY_TIME_TRACKING_MODE = androidx.datastore.preferences.core.stringPreferencesKey("time_tracking_mode")
        private val KEY_MINI_PANEL_SCALE = floatPreferencesKey("mini_panel_scale")
        private val KEY_HUD_SCALE = floatPreferencesKey("hud_scale")
        const val POSITION_UNSET = Int.MIN_VALUE

        /**
         * 패널 배율의 하한. 더 내려가면 24dp 버튼이 손가락으로 누르기 힘든 크기가 된다 —
         * 미니패널 탭은 상세 패널을 여는 유일한 입구라 여기서 못 누르면 막힌다.
         */
        const val MIN_PANEL_SCALE = 0.6f
        const val MAX_PANEL_SCALE = 1f
    }
}
