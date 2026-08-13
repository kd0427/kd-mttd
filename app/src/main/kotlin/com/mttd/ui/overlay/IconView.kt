package com.mttd.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mttd.domain.models.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * 배지 2번째 줄에 표시할 수익 지표. 설정 탭에서 선택 가능 (시간/카운트 계열은 후보에서 제외 —
 * 배지 자체가 경과 시간을 이미 1번째 줄에 보여주고, 카운트는 수익 0 일 때의 폴백으로만 쓰인다).
 */
enum class BadgeIncomeMetric(val id: String, val label: String, val perHour: Boolean) {
    INCOME_PER_HOUR("income_per_hour", "시간당 수익", perHour = true),
    NET_INCOME_PER_HOUR("net_income_per_hour", "시간당 실수령 (TAX 제외)", perHour = true),
    TOTAL_VALUE("total_value", "누적 총수익", perHour = false),
    NET_TOTAL_VALUE("net_total_value", "누적 실수령 (TAX 제외)", perHour = false),
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

/** 접힌 상태의 가로 요약 바. 탭하면 상세 HUD가 열리고, 길게 누르면 위치를 옮긴다. */
@Composable
fun IconOverlay(
    sessionState: StateFlow<SessionState>,
    @Suppress("UNUSED_PARAMETER")
    metricFlow: Flow<String> = flowOf(BadgeIncomeMetric.DEFAULT.id),
) {
    val session by sessionState.collectAsStateWithLifecycle()

    // 경과 시간을 흘려보내기 위한 1 초 틱.
    // 예전엔 `while (true)` 라 일시정지·집계 대기 상태에서도 영원히 깨어나
    // 오버레이를 매초 재구성/재드로우했다. 실제로 시간이 흐를 때만 돌린다.
    val ticking = session.active && !session.paused && session.baselineReady
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(ticking) {
        while (ticking) { delay(1000); tick++ }
    }

    val elapsed = remember(session.startedAtMs, session.active, session.endedAtMs, session.paused, tick) {
        session.elapsedMs
    }
    val perHour = remember(session.totalValue, session.active, session.endedAtMs, session.paused, tick) {
        session.incomePerHour
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A).copy(alpha = 0.9f), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFE2E8F0).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val primaryColor = when {
                session.paused -> Color(0xFFFBBF24)
                elapsed > 0 -> Color(0xFF4ADE80)
                else -> Color(0xFFCBD5E1)
            }
            val status = when {
                session.paused -> "❚❚"
                session.active -> "●"
                else -> "○"
            }
            Text(
                status,
                color = primaryColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            SummaryMetric("경과", if (elapsed > 0) formatElapsedIcon(elapsed) else "대기", primaryColor)
            SummaryMetric("총", formatFire(session.totalValue), valueColor(session.totalValue))
            SummaryMetric("시간당", formatFire(perHour) + "/h", valueColor(perHour))
            SummaryMetric("이번 맵", formatFire(session.currentMapValue), valueColor(session.currentMapValue))
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, color: Color) {
    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 8.sp, maxLines = 1)
        Text(
            value,
            color = color,
            fontSize = if (value.length > 9) 9.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private fun valueColor(value: Double): Color = when {
    value < 0 -> Color(0xFFF87171)
    value > 0 -> Color(0xFF4ADE80)
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
