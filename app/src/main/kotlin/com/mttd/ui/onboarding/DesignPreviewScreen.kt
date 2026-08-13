package com.mttd.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mttd.domain.models.SessionState
import com.mttd.domain.models.TimeTrackingMode
import com.mttd.ui.overlay.HudOverlay
import com.mttd.ui.overlay.IconOverlay
import kotlinx.coroutines.flow.MutableStateFlow

private enum class PreviewState(val label: String) {
    PLAYING("진행"),
    PAUSED("중지"),
    WAITING("대기"),
    SETUP("로그·가방"),
}

/** 실제 게임·로그 연결 없이 앱의 주요 화면과 오버레이 상태를 확인하는 디자인 작업용 탭. */
@Composable
fun DesignPreviewScreen() {
    var selected by remember { mutableStateOf(PreviewState.PLAYING) }
    val sessionFlow = remember { MutableStateFlow(previewSession(PreviewState.PLAYING)) }

    fun setPreview(state: PreviewState) {
        selected = state
        sessionFlow.value = previewSession(state)
    }

    Text("디자인 미리보기", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        "샘플 데이터만 사용합니다. 실제 로그·게임·오버레이 권한에는 영향을 주지 않습니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("상태 미리보기", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (state in PreviewState.entries) {
                    if (selected == state) Button(onClick = { setPreview(state) }) { Text(state.label) }
                    else OutlinedButton(onClick = { setPreview(state) }) { Text(state.label) }
                }
            }
            Text(
                "요약 바와 상세 HUD는 아래에서 같은 상태로 함께 바뀝니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    PreviewSection("요약 바") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E293B), RoundedCornerShape(10.dp))
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Box(Modifier.width(516.dp).height(40.dp)) {
                IconOverlay(sessionState = sessionFlow)
            }
        }
    }

    PreviewSection("상세 HUD") {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(280.dp)) {
                HudOverlay(
                    sessionState = sessionFlow,
                    onCollapse = {},
                    onOpenSettings = {},
                    onTogglePause = {},
                    onRefreshHoldings = {},
                    onReset = {},
                )
            }
        }
    }

    PreviewSection("수익 화면 구성") {
        PreviewMetricCard("세션 수익", "1,284.6 🔥", "시간당 617.2 🔥")
        PreviewMetricCard("이번 맵", "79.58 🔥", "맵핑 횟수 12회")
    }
    PreviewSection("가치·설정 화면 구성") {
        PreviewMetricCard("시세", "최신 데이터", "아이템 2,418개 · 갱신 2분 전")
        PreviewMetricCard("오버레이", "표시 중", "시간 집계: 맵 입장 중만")
    }
}

@Composable
private fun PreviewSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun PreviewMetricCard(title: String, value: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun previewSession(state: PreviewState): SessionState {
    val now = System.currentTimeMillis()
    val ready = state != PreviewState.SETUP
    val inMap = state == PreviewState.PLAYING
    return SessionState(
        active = true,
        startedAtMs = now - 7_420_000L,
        baselineReady = ready,
        paused = state == PreviewState.PAUSED,
        timeTrackingMode = TimeTrackingMode.MAP_ONLY,
        inMap = inMap,
        mapElapsedAccumulatedMs = 438_000L,
        mapElapsedSinceMs = if (inMap) now - 5_000L else null,
        currentMapElapsedAccumulatedMs = 193_000L,
        currentMapElapsedSinceMs = if (inMap) now - 5_000L else null,
        mapsEntered = 12,
        totalValue = 1_284.6,
        currentMapValue = 79.58,
    )
}
