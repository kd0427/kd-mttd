package com.mttd.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mttd.data.prices.PriceRepository
import com.mttd.domain.models.MapRun
import com.mttd.domain.models.PickupSummary
import com.mttd.domain.models.SessionState
import com.mttd.domain.models.TimeTrackingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * 게임 위 패널을 Android Studio Design 창에서 바로 보기 위한 미리보기 모음.
 *
 * 이 패널들은 평소 `WindowManager` 로 게임 화면 위에 붙기 때문에, 확인하려면 기기에
 * 설치하고 Shizuku 를 붙이고 게임까지 켜야 했다. 여기 있는 것들은 가짜 세션 값만 넣어
 * 렌더링하므로 그 과정이 전부 필요 없다.
 *
 * 미리보기 함수는 아무도 호출하지 않으므로 R8 이 릴리스 빌드에서 통째로 걷어낸다
 * (릴리스 APK 를 뜯어 확인함). 렌더러(ui.tooling)도 debugImplementation 이라 릴리스엔 없다.
 *
 * `src/main` 에 두는 이유: `src/debug` 는 디렉터리가 새로 생기면 Android Studio 가 소스 루트로
 * 인식하기까지 Gradle 싱크가 한 번 필요하다. 여기 두면 그 과정 없이 바로 보인다.
 *
 * **쓰는 법**: 이 파일을 열면 오른쪽 위 `Split` / `Design` 버튼으로 미리보기가 뜬다.
 * 코드를 고치면 `Build & Refresh` 로 갱신된다.
 */

/** 실제로 파밍 중인 것처럼 보이는 값. 숫자가 0 이면 레이아웃 폭을 가늠할 수 없다. */
private fun sampleSession(
    paused: Boolean = false,
    baselineReady: Boolean = true,
) = SessionState(
    active = true,
    startedAtMs = System.currentTimeMillis() - 45 * 60_000,
    baselineReady = baselineReady,
    paused = paused,
    inMap = true,
    timeTrackingMode = TimeTrackingMode.MAP_ONLY,
    mapsEntered = 12,
    pickupCount = 214,
    totalValue = 1_284.5,
    netTotalValue = 1_123.9,
    currentMapValue = 96.25,
    netCurrentMapValue = 84.2,
    currentMapName = "[비경] 얼어붙은 심연",
    mapElapsedAccumulatedMs = 42 * 60_000,
    currentMapElapsedAccumulatedMs = 2 * 60_000 + 45_000,
    recentPickups = samplePickups(),
    holdings = samplePickups(),
    runs = List(8) { i ->
        MapRun(
            id = i.toLong(),
            index = i + 1,
            startedAtMs = 0,
            endedAtMs = 1,
            mapName = "맵 ${i + 1}",
            items = listOf(samplePickups().first()),
        )
    },
)

private fun samplePickups() = listOf(
    PickupSummary(
        timestampMs = 0, action = "PickItems", itemId = "100300",
        itemName = "최초의 불꽃 결정", itemType = "통화",
        quantity = 42, unitPrice = 1f, value = 42.0, netValue = 42.0,
    ),
    PickupSummary(
        timestampMs = 0, action = "PickItems", itemId = "400008",
        itemName = "딥 스페이스 비콘", itemType = "기타",
        quantity = -3, unitPrice = 8.5f, value = -25.5, netValue = -25.5,
    ),
    PickupSummary(
        timestampMs = 0, action = "PickItems", itemId = "500123",
        itemName = "차원의 나침반", itemType = "기타",
        quantity = 7, unitPrice = 11.2f, value = 78.4, netValue = 68.6,
    ),
)

private val samplePrices = MutableStateFlow(
    PriceRepository.State(
        seasonId = "1501",
        totalItems = 1677,
        itemsWithPrice = 1677,
        lastUpdatedMs = System.currentTimeMillis() - 17 * 60_000,
    )
)

/** 게임 화면처럼 어두운 바탕 위에 올려야 실제로 어떻게 보이는지 알 수 있다. */
@Composable
private fun OnGame(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xFF2A2118))
            .padding(12.dp),
    ) { content() }
}

// ── 미니패널 (요약 바) ────────────────────────────────────────────────

// DEFAULT_IDS 는 처음 설치했을 때의 세 항목이라, "다 켠 상태" 는 여기서 직접 만든다.
@Preview(name = "미니패널 · 항목 6개", widthDp = 412, showBackground = true)
@Composable
private fun PreviewMiniPanelAll() = OnGame {
    IconOverlay(
        sessionState = MutableStateFlow(sampleSession()),
        metricFlow = flowOf(MiniPanelMetric.entries.mapTo(linkedSetOf()) { it.id }),
    )
}

@Preview(name = "미니패널 · 기본값 (항목 3개, 버튼 없음)", widthDp = 412, showBackground = true)
@Composable
private fun PreviewMiniPanelDefault() = OnGame {
    IconOverlay(sessionState = MutableStateFlow(sampleSession()))
}

@Preview(name = "미니패널 · 항목 3개", widthDp = 412, showBackground = true)
@Composable
private fun PreviewMiniPanelFew() = OnGame {
    IconOverlay(
        sessionState = MutableStateFlow(sampleSession()),
        metricFlow = flowOf(
            setOf(
                MiniPanelMetric.TOTAL_VALUE.id,
                MiniPanelMetric.INCOME_PER_HOUR.id,
                MiniPanelMetric.CURRENT_MAP_TIME.id,
            )
        ),
    )
}

@Preview(name = "미니패널 · 세후 표시", widthDp = 412, showBackground = true)
@Composable
private fun PreviewMiniPanelNet() = OnGame {
    IconOverlay(
        sessionState = MutableStateFlow(sampleSession()),
        metricFlow = flowOf(MiniPanelMetric.DEFAULT_IDS),
        netValueFlow = flowOf(true),
    )
}

@Preview(name = "미니패널 · 일시정지", widthDp = 412, showBackground = true)
@Composable
private fun PreviewMiniPanelPaused() = OnGame {
    IconOverlay(
        sessionState = MutableStateFlow(sampleSession(paused = true)),
        metricFlow = flowOf(MiniPanelMetric.DEFAULT_IDS),
        // 일시정지 버튼이 재생 모양으로 바뀌는 것도 같이 보려고 이 프리뷰만 버튼을 켠다.
        controlFlow = flowOf(setOf(MiniPanelControl.PAUSE.id)),
    )
}

@Preview(name = "미니패널 · 가방 정렬 대기", widthDp = 412, showBackground = true)
@Composable
private fun PreviewMiniPanelWaiting() = OnGame {
    IconOverlay(
        sessionState = MutableStateFlow(sampleSession(baselineReady = false)),
        metricFlow = flowOf(MiniPanelMetric.DEFAULT_IDS),
    )
}

// 버튼 개수는 수치 칸 폭을 직접 바꾼다. 버튼이 가장 많을 때 템 버튼이 잘리지 않는지 본다
// (버튼이 하나도 없는 쪽은 위의 "기본값" 프리뷰가 그대로 보여준다).
@Preview(name = "미니패널 · 버튼 4개", widthDp = 412, showBackground = true)
@Composable
private fun PreviewMiniPanelAllControls() = OnGame {
    IconOverlay(
        sessionState = MutableStateFlow(sampleSession()),
        metricFlow = flowOf(MiniPanelMetric.entries.mapTo(linkedSetOf()) { it.id }),
        controlFlow = flowOf(MiniPanelControl.entries.mapTo(linkedSetOf()) { it.id }),
    )
}

// ── 상세 패널 ─────────────────────────────────────────────────────────

@Preview(name = "상세 패널", widthDp = 320, showBackground = true)
@Composable
private fun PreviewHud() = OnGame {
    HudOverlay(
        sessionState = MutableStateFlow(sampleSession()),
        priceState = samplePrices,
        onCollapse = {},
    )
}

@Preview(name = "상세 패널 · 거래소", widthDp = 320, showBackground = true)
@Composable
private fun PreviewHudExchange() = OnGame {
    HudOverlay(
        sessionState = MutableStateFlow(sampleSession().copy(inExchange = true, paused = true)),
        priceState = samplePrices,
        onCollapse = {},
    )
}

// ── 아이템 패널 ───────────────────────────────────────────────────────

@Preview(name = "아이템 패널", widthDp = 220, showBackground = true)
@Composable
private fun PreviewItems() = OnGame {
    ItemOverlay(MutableStateFlow(sampleSession()))
}
