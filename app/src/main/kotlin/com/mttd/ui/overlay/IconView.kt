package com.mttd.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
        /**
         * 처음 설치했을 때 켜져 있는 항목. 수익을 읽는 데 필요한 최소 셋만 둔다 —
         * 여섯 개를 다 켜면 칸이 좁아 라벨이 잘리고, 나머지는 설정에서 켜면 된다.
         */
        val DEFAULT_IDS: Set<String> = linkedSetOf(TOTAL_VALUE.id, TOTAL_TIME.id, INCOME_PER_HOUR.id)
    }
}

/**
 * 미니패널 오른쪽에 표시할 제어 버튼. 설정에서 하나씩 켜고 끌 수 있다.
 *
 * [width] 는 패널 폭 계산([IconOverlay] 의 `fixedWidth`)에 쓰인다 — 버튼을 끄면 그만큼이
 * 수치 칸으로 돌아간다. 값은 각 버튼 컴포저블의 `size` 와 반드시 같아야 한다.
 */
enum class MiniPanelControl(val id: String, val settingsLabel: String, val width: Dp) {
    PAUSE("pause", "일시정지", 24.dp),
    RESET("reset", "초기화", 24.dp),
    ITEMS("items", "템 목록", 30.dp),
    EXIT("exit", "종료", 24.dp),
    ;

    companion object {
        /**
         * 처음 설치했을 때는 버튼이 하나도 없다. 미니패널은 수치를 읽는 자리고, 제어는
         * 탭해서 여는 상세 패널에 다 있다. 필요한 버튼만 설정에서 켜면 된다.
         */
        val DEFAULT_IDS: Set<String> = emptySet()
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

/**
 * 수치 한 칸의 기본 폭.
 *
 * 항목을 몇 개 켜든 이 폭을 유지한다. 예전엔 남는 폭을 항목 수로 나눠 갖게 해서, 세 개만
 * 켜면 칸이 64dp 까지 벌어지고 여섯 개면 50dp 로 좁아졌다 — 간격 자체는 5dp 로 같은데도
 * 숫자 사이 여백이 두 배 넘게 차이 나서 같은 패널이 다른 리듬으로 읽혔다.
 *
 * 값은 52dp — "12,845.0" 정도의 숫자가 11sp 로 잘리지 않는 선이면서, 412dp 폰에서 여섯 개를
 * 다 켜도(버튼 없이) 49.7dp 로만 좁아져 세 개일 때와 구분이 안 되는 값이다. 한 줄을 넘길
 * 때만 [IconOverlay] 의 나눗셈이 이겨서 칸이 균등하게 좁아진다 — 좁은 기기에서 항목·버튼을
 * 많이 켜면 그만큼 더 좁아지고, 이건 한 줄을 지키기로 한 대가다.
 */
private val PREFERRED_METRIC_WIDTH = 52.dp

/** 접힌 상태의 게임 위 미니패널. 탭하면 상세 패널이 열리고, 길게 누르면 위치를 옮긴다. */
@Composable
fun IconOverlay(
    sessionState: StateFlow<SessionState>,
    metricFlow: Flow<Set<String>> = flowOf(MiniPanelMetric.DEFAULT_IDS),
    controlFlow: Flow<Set<String>> = flowOf(MiniPanelControl.DEFAULT_IDS),
    /** true 면 수익을 경매장 세금(1/8) 뺀 실수령으로 보여준다. */
    netValueFlow: Flow<Boolean> = flowOf(false),
    /** 패널 최대 폭(dp)을 재는 함수. 값이 아니라 함수인 건 회전 때 다시 재기 위한 것. */
    maxPanelWidthDp: (() -> Float)? = null,
    onTogglePause: () -> Unit = {},
    onReset: () -> Unit = {},
    onToggleItems: () -> Unit = {},
    onExitApp: () -> Unit = {},
) {
    val session by sessionState.collectAsStateWithLifecycle()
    val selectedMetricIds by metricFlow.collectAsStateWithLifecycle(
        initialValue = MiniPanelMetric.DEFAULT_IDS,
    )
    val selectedControlIds by controlFlow.collectAsStateWithLifecycle(
        initialValue = MiniPanelControl.DEFAULT_IDS,
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
    //
    // 화면을 돌리면 가로 폭이 달라지므로 configuration 이 바뀔 때마다 다시 잰다. 그래서
    // 값이 아니라 재실행할 수 있는 람다를 받는다 — 예전엔 마운트 시점에 한 번 잰 폭을
    // 그대로 들고 있어서, 세로에서 켠 패널이 가로에서도 세로 폭을 최댓값으로 썼다.
    //
    // 단위가 px 가 아니라 dp 인 게 중요하다. 패널 축소 배율은 Compose 밀도로 걸리는데,
    // 상한을 물리 px 로 받으면 밀도가 줄어든 만큼 dp 상한이 커져서 "화면 폭을 꽉 채운다"
    // 는 결론이 배율과 무관하게 유지된다 — 항목을 많이 켜 상한에 걸린 패널은 배율을
    // 낮춰도 글자만 작아지고 폭은 그대로였다. dp 로 받으면 상한이 배율과 함께 줄어든다.
    val configuration = LocalConfiguration.current
    val maxPanelWidth = remember(configuration) {
        maxPanelWidthDp?.invoke()?.dp
            ?: (configuration.screenWidthDp.dp - 40.dp).coerceAtLeast(260.dp)
    }
    val selectedControls = remember(selectedControlIds) {
        MiniPanelControl.entries.filter { it.id in selectedControlIds }
    }
    val gap = 5.dp
    val statusToMetricGap = 1.dp
    val controlGap = 9.dp
    // 버튼을 끄면 그 폭이 그대로 수치 칸으로 돌아가야 하므로 고정 폭을 켜진 버튼으로 계산한다.
    // 마지막 수치와 첫 버튼 사이의 gap 도 여기 포함된다 — 예전 식은 이걸 빠뜨려서 5dp 를
    // 덜 잡고 있었다. 버튼이 하나도 없으면 그 gap 자체가 없다.
    val controlsWidth = if (selectedControls.isEmpty()) 0.dp else {
        gap + selectedControls.fold(0.dp) { acc, c -> acc + c.width } +
            controlGap * (selectedControls.size - 1)
    }
    // panelPadding: 바깥 Box 의 좌우 패딩(5dp x 2). maxPanelWidth 는 패널 **바깥** 폭 기준이라
    // 이걸 빼놓으면 상한에 걸리는 조합에서 패널이 화면 여백을 10dp 넘어선다.
    val panelPadding = 10.dp
    val fixedWidth = panelPadding + 48.dp + statusToMetricGap + controlsWidth +
        gap * (selectedMetrics.size - 1)
    val metricWidth = ((maxPanelWidth - fixedWidth).value / selectedMetrics.size)
        .coerceAtLeast(1f)
        // 평소엔 항목을 몇 개 켜든 이 폭을 그대로 쓴다. 패널은 내용만큼 짧아진다.
        // 한 줄에 안 들어갈 때만 위 나눗셈이 이겨서 칸이 균등하게 좁아진다.
        .coerceAtMost(PREFERRED_METRIC_WIDTH.value)
        .dp
    // 위 계산은 화면 폭을 기준으로 칸을 좁히지만, 창이 실제로 내주는 폭은 그보다 좁을 수
    // 있다 — 그러면 오른쪽 버튼부터 화면 밖으로 잘려 나가고, 잘린 버튼은 존재 자체를 알
    // 수 없다. 남는 폭에 맞춰 패널을 통째로 한 번 더 줄여 전부 보이게 한다.
    //
    // 설정에 저장된 배율은 건드리지 않는다. 항목을 다시 줄이면 여기서 알아서 원래 크기로
    // 돌아온다 — 사용자가 고른 값과 화면 사정 때문에 깎인 값은 서로 다른 얘기다.
    BoxWithConstraints {
        val neededWidth = fixedWidth + metricWidth * selectedMetrics.size
        // 창이 내주는 제약은 "지금 창의 크기" 라서, 한 번 줄이고 나면 다음 계산은 줄어든
        // 창을 상한으로 본다. 그대로 쓰면 배율을 올렸다 내릴 때마다 패널이 조금씩 더
        // 작아진다 (100%→80% 에서 785px 이어야 할 것이 668px 로 굳었다). 이 화면에서 본
        // 가장 넓은 폭을 물리 px 로 기억해 기준으로 삼는다 — 축소는 밀도만 바꾸므로 이
        // 값은 배율과 무관하고, 화면이 바뀌면(회전) 처음부터 다시 잰다.
        val densityNow = LocalDensity.current.density
        val widestPx = remember(configuration) { FloatArray(1) }
        val seenPx = maxWidth.value * densityNow
        if (seenPx.isFinite() && seenPx > widestPx[0]) widestPx[0] = seenPx
        val availableWidth = if (widestPx[0] > 0f) (widestPx[0] / densityNow).dp else Dp.Infinity
        val fitScale =
            if (availableWidth.value.isFinite() && neededWidth > availableWidth && neededWidth > 0.dp)
                availableWidth / neededWidth
            else 1f
        val outerDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides
                if (fitScale >= 1f) outerDensity
                else Density(outerDensity.density * fitScale, outerDensity.fontScale),
        ) {
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
                        // HudView 와 같은 순서 — 끊김이면 나머지 판정은 굳은 값이라 먼저 알려야 한다.
                        session.logStalled -> "끊김"
                        session.gameAway -> "게임 꺼짐"
                        session.paused -> "중지중"
                        !session.baselineReady -> "로그·가방"
                        session.timeTrackingMode == com.mttd.domain.models.TimeTrackingMode.MAP_ONLY && !session.inMap -> "대기중"
                        else -> "진행중"
                    }
                    val statusColor = when (status) {
                        "진행중" -> Color(0xFF4ADE80)
                        "끊김" -> Color(0xFFEF4444)
                        "게임 꺼짐" -> Color(0xFF94A3B8)
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
                    if (selectedControls.isNotEmpty()) Spacer(Modifier.width(gap))
                    selectedControls.forEachIndexed { index, control ->
                        when (control) {
                            MiniPanelControl.PAUSE -> SummaryPauseButton(
                                paused = session.paused,
                                // 거래소 안에서는 일시정지가 자동 제어다. 상세 HUD 는 아예 버튼을
                                // 숨기지만(HudView 헤더), 여기서는 폭 예산이 버튼 개수로 미리
                                // 계산돼 있어 빼면 칸이 어긋난다 — 자리는 두고 흐리게만 만든다.
                                enabled = !session.inExchange,
                                onClick = onTogglePause,
                            )
                            MiniPanelControl.RESET -> SummaryResetButton(onReset)
                            MiniPanelControl.ITEMS -> SummaryItemButton(onToggleItems)
                            MiniPanelControl.EXIT -> SummaryExitButton(onExitApp)
                        }
                        if (index < selectedControls.lastIndex) Spacer(Modifier.width(controlGap))
                    }
                }
            }
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

/**
 * 초기화·종료는 여기서만 더블탭이다. 일반패널은 버튼 사이를 20dp 로 벌려서 한 번 탭으로
 * 내렸지만, 미니패널은 그럴 폭이 없어 버튼끼리 9dp 로 붙어 있다 — 제스처가 유일한 오터치
 * 방어선이다. 두 패널을 통일하려다 이걸 한 번 탭으로 바꾸면 세션이 스치기만 해도 날아간다.
 */
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
            imageVector = Icons.Filled.Delete,
            contentDescription = "두 번 눌러 초기화",
            tint = Color(0xFFF87171),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SummaryPauseButton(paused: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(0xFF1E293B), RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = if (paused) "재생" else "일시정지",
            tint = when {
                !enabled -> Color(0xFF475569)
                paused -> Color(0xFF4ADE80)
                else -> Color(0xFFFBBF24)
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * mTTD 를 통째로 끈다. 초기화와 같은 위험도라 배경·색·제스처를 똑같이 맞췄다 — 다른 톤을
 * 주면 둘 중 하나가 덜 위험해 보인다. 구분은 아이콘이 한다.
 */
@Composable
private fun SummaryExitButton(onExitApp: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(0xFF3F1D2E), RoundedCornerShape(7.dp))
            .pointerInput(onExitApp) {
                detectTapGestures(
                    onDoubleTap = { onExitApp() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Logout,
            contentDescription = "두 번 눌러 mTTD 종료",
            tint = Color(0xFFF87171),
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
