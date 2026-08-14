package com.mttd.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mttd.domain.models.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** 미니패널에서 선택적으로 표시할 수치 항목. */
enum class MiniPanelMetric(val id: String, val settingsLabel: String) {
    MAP_COUNT("map_count", "맵 횟수"),
    CURRENT_MAP_TIME("current_map_time", "현재 맵 시간"),
    TOTAL_VALUE("total_value", "총 수익"),
    TOTAL_TIME("total_time", "총 시간"),
    INCOME_PER_HOUR("income_per_hour", "시간당 수익"),
    CURRENT_MAP_VALUE("current_map_value", "이번 맵 수익"),
    ;

    companion object {
        val DEFAULT_IDS: Set<String> = entries.mapTo(linkedSetOf()) { it.id }
    }
}

/**
 * 배지 2번째 줄에 표시할 수익 지표. 설정 탭에서 선택 가능 (시간/카운트 계열은 후보에서 제외 —
 * 배지 자체가 경과 시간을 이미 1번째 줄에 보여주고, 카운트는 수익 0 일 때의 폴백으로만 쓰인다).
 */
enum class BadgeIncomeMetric(val id: String, val label: String, val perHour: Boolean) {
    INCOME_PER_HOUR("income_per_hour", "시간당 수익", perHour = true),
    NET_INCOME_PER_HOUR("net_income_per_hour", "시간당 실수령 (세금 제외)", perHour = true),
    TOTAL_VALUE("total_value", "누적 총수익", perHour = false),
    NET_TOTAL_VALUE("net_total_value", "누적 실수령 (세금 제외)", perHour = false),
    CURRENT_MAP_VALUE("current_map_value", "이번 맵 수익", perHour = false),
    ;

    fun value(session: SessionState): Double = when (this) {
        INCOME_PER_HOUR -> session.incomePerHour
        NET_INCOME_PER_HOUR -> session.netIncomePerHour
        TOTAL_VALUE -> session.totalValue
        NET_TOTAL_VALUE -> session.netTotalValue
        CURRENT_MAP_VALUE -> session.currentMapValue
    }

    companion object {
        val DEFAULT = INCOME_PER_HOUR
        fun fromId(id: String): BadgeIncomeMetric = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** 접힌 상태의 게임 위 미니패널. 탭하면 상세 패널이 열리고, 길게 누르면 위치를 옮긴다. */
@Composable
fun IconOverlay(
    sessionState: StateFlow<SessionState>,
    metricFlow: Flow<Set<String>> = flowOf(MiniPanelMetric.DEFAULT_IDS),
    /** true 면 수익을 경매장 세금(1/8) 뺀 실수령으로 보여준다. */
    netValueFlow: Flow<Boolean> = flowOf(false),
    maxPanelWidthPx: Int? = null,
    onTogglePause: () -> Unit = {},
    onReset: () -> Unit = {},
    onToggleItems: () -> Unit = {},
) {
    val session by sessionState.collectAsStateWithLifecycle()
    val selectedMetricIds by metricFlow.collectAsStateWithLifecycle(
        initialValue = MiniPanelMetric.DEFAULT_IDS,
    )
    val showNet by netValueFlow.collectAsStateWithLifecycle(initialValue = false)

    // 경과 시간을 흘려보내기 위한 1 초 틱.
    // 예전엔 `while (true)` 라 일시정지·집계 대기 상태에서도 영원히 깨어나
    // 오버레이를 매초 재구성/재드로우했다. 실제로 시간이 흐를 때만 돌린다.
    val ticking = session.active &&
        !session.paused &&
        session.baselineReady &&
        (session.timeTrackingMode != com.mttd.domain.models.TimeTrackingMode.MAP_ONLY || session.inMap)
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(ticking) {
        while (ticking) { delay(1000); tick++ }
    }

    val elapsed = remember(session.startedAtMs, session.active, session.endedAtMs, session.paused, session.timeTrackingMode, session.inMap, session.mapElapsedAccumulatedMs, session.mapElapsedSinceMs, tick) {
        session.elapsedMs
    }
    val perHour = remember(session.totalValue, session.netTotalValue, showNet, session.active, session.endedAtMs, session.paused, session.timeTrackingMode, session.inMap, session.mapElapsedAccumulatedMs, session.mapElapsedSinceMs, tick) {
        if (showNet) session.netIncomePerHour else session.incomePerHour
    }
    // 세전/세후 전환은 표시 값만 바꾼다 — 집계는 항상 두 값을 다 들고 있다.
    val totalValue = if (showNet) session.netTotalValue else session.totalValue
    val currentMapValue = if (showNet) session.netCurrentMapValue else session.currentMapValue
    val currentMapElapsed = remember(
        session.currentMapElapsedAccumulatedMs,
        session.currentMapElapsedSinceMs,
        session.inMap,
        tick,
    ) {
        session.currentMapElapsedMs
    }
    val selectedMetrics = remember(selectedMetricIds) {
        MiniPanelMetric.entries.filter { it.id in selectedMetricIds }
            .ifEmpty { MiniPanelMetric.entries.toList() }
    }
    // 앱 본문의 카드와 똑같이 좌우 20dp 여백을 둔 폭을 최댓값으로만 사용한다.
    // 항목을 빼면 패널은 내용만큼 짧아지고, 길어질 때만 카드 폭에서 멈춘다.
    val density = LocalDensity.current
    val maxPanelWidth = maxPanelWidthPx?.let { pixels ->
        // 서비스 오버레이의 Compose 밀도는 앱 본문과 다를 수 있다.
        // 실제 px를 여기서 dp로 환산해야 카드와 정확히 같은 최대 폭이 된다.
        with(density) { pixels.toDp() }
    } ?: (LocalConfiguration.current.screenWidthDp.dp - 40.dp).coerceAtLeast(260.dp)
    val gap = 5.dp
    val statusToMetricGap = 1.dp
    val controlGap = 9.dp
    val fixedWidth = 48.dp + statusToMetricGap + 24.dp + controlGap + 24.dp + controlGap + 30.dp +
        gap * (selectedMetrics.size - 1)
    val metricWidth = ((maxPanelWidth - fixedWidth).value / selectedMetrics.size)
        .coerceAtLeast(1f)
        // 모든 항목을 고르면 카드 최대 폭까지 균등하게 채운다.
        // 항목이 적을 때만 기본 폭에서 멈춰 패널이 함께 짧아진다.
        .coerceAtMost(64f)
        .dp
    Box(
        modifier = Modifier.wrapContentSize()
            .background(Color(0xFF0F172A).copy(alpha = 0.9f), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFE2E8F0).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 5.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val primaryColor = when {
                session.paused -> Color(0xFFFBBF24)
                session.inMap -> Color(0xFF4ADE80)
                else -> Color(0xFFCBD5E1)
            }
            val status = when {
                session.paused -> "중지중"
                !session.baselineReady -> "로그·가방"
                session.timeTrackingMode == com.mttd.domain.models.TimeTrackingMode.MAP_ONLY && !session.inMap -> "대기중"
                else -> "진행중"
            }
            val statusColor = when (status) {
                "진행중" -> Color(0xFF4ADE80)
                "중지중" -> Color(0xFFF87171)
                "로그·가방" -> Color(0xFFFB923C)
                else -> Color(0xFF94A3B8) // 대기중
            }
            SummaryStatus(
                status = status,
                color = statusColor,
            )
            Spacer(Modifier.width(statusToMetricGap))
            selectedMetrics.forEachIndexed { index, metric ->
                when (metric) {
                    MiniPanelMetric.MAP_COUNT ->
                        SummaryMetric("맵핑 횟수", "${session.mapsEntered}회", primaryColor, metricWidth)
                    MiniPanelMetric.CURRENT_MAP_TIME ->
                        SummaryMetric("현재 맵", formatElapsedIcon(currentMapElapsed), primaryColor, metricWidth)
                    // 라벨은 세전/세후 어느 쪽이든 그대로 둔다. 칸이 최소 34dp/7sp 라
                    // "세후" 를 붙이면 잘리고, 세 지표 중 일부만 붙이면 더 헷갈린다.
                    // 어느 쪽인지 확인이 필요하면 탭해서 상세 패널을 보면 둘 다 나온다.
                    MiniPanelMetric.TOTAL_VALUE ->
                        SummaryMetric("총 수익", formatFire(totalValue), valueColor(totalValue, showNet), metricWidth)
                    MiniPanelMetric.TOTAL_TIME ->
                        SummaryMetric("총 시간", if (elapsed > 0) formatElapsedIcon(elapsed) else "대기", primaryColor, metricWidth)
                    MiniPanelMetric.INCOME_PER_HOUR ->
                        SummaryMetric("시간당", formatFire(perHour) + "/h", valueColor(perHour, showNet), metricWidth)
                    MiniPanelMetric.CURRENT_MAP_VALUE ->
                        SummaryMetric("이번 맵", formatFire(currentMapValue), valueColor(currentMapValue, showNet), metricWidth)
                }
                if (index < selectedMetrics.lastIndex) Spacer(Modifier.width(gap))
            }
            Spacer(Modifier.width(gap))
            SummaryPauseButton(
                paused = session.paused,
                onClick = onTogglePause,
            )
            Spacer(Modifier.width(controlGap))
            SummaryResetButton(onReset)
            Spacer(Modifier.width(controlGap))
            SummaryItemButton(onToggleItems)
        }
    }
}

@Composable
private fun SummaryStatus(status: String, color: Color) {
    Column(modifier = Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 상태가 한눈에 들어오도록 신호등처럼 색 원을 함께 표시한다.
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(color, RoundedCornerShape(50)),
            )
            Text(
                status,
                color = color,
                fontSize = if (status == "로그·가방") 8.sp else 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, color: Color, width: androidx.compose.ui.unit.Dp) {
    Column(modifier = Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            color = Color(0xFF94A3B8),
            fontSize = if (width < 38.dp) 7.sp else 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            color = color,
            fontSize = if (width < 38.dp || value.length > 9) 9.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SummaryResetButton(onReset: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(0xFF3F1D2E), RoundedCornerShape(7.dp))
            .pointerInput(onReset) {
                detectTapGestures(
                    onDoubleTap = { onReset() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "두 번 눌러 초기화",
            tint = Color(0xFFF87171),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SummaryPauseButton(paused: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(0xFF1E293B), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = if (paused) "재생" else "일시정지",
            tint = if (paused) Color(0xFF4ADE80) else Color(0xFFFBBF24),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SummaryItemButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 24.dp)
            .background(Color(0xFF1E3A5F), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("템", color = Color(0xFFBFDBFE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 수익 숫자 색. 두 가지를 한 채널에 싣는다.
 *
 * - **부호**가 우선이다. 음수는 어느 모드든 빨강 — "이번 맵" 은 맵을 열 때 나침반·비콘을
 *   소비해서 초반엔 거의 항상 음수라, 여기서 손실을 못 읽으면 실질적인 손해다.
 * - **모드**는 양수일 때만 나타낸다. 세전은 초록, 세후는 파랑([HudOverlay] 가 이미 쓰는
 *   톤이라 어두운 패널 위 가독성이 검증돼 있다). 라벨을 붙일 공간이 없어 색으로 구분한다.
 */
private fun valueColor(value: Double, showNet: Boolean): Color = when {
    value < 0 -> Color(0xFFF87171)
    value > 0 -> if (showNet) Color(0xFF60A5FA) else Color(0xFF4ADE80)
    else -> Color(0xFFCBD5E1)
}

/** 아이콘용 짧은 경과 포맷: 1h 미만은 mm:ss, 이상은 h:mm. */
private fun formatElapsedIcon(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "%d:%02d".format(h, m)
        else -> "%d:%02d".format(m, sec)
    }
}
