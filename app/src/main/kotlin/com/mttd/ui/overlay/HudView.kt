package com.mttd.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mttd.domain.models.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** 목록에 한 번에 보일 줄 수. 이보다 많으면 목록 안에서 스크롤. */
private const val VISIBLE_ROWS = 4
/** 한 줄 높이(아이콘 14 dp) + 줄 간격 2 dp. */
private val ROW_HEIGHT = 16.dp
private val MAX_LIST_HEIGHT = ROW_HEIGHT * VISIBLE_ROWS
/** 거래소 화면(보유 아이템 목록)에서는 다른 통계가 없으니 더 많이 보여준다. */
private val HOLDINGS_LIST_HEIGHT = ROW_HEIGHT * 10

/** 미니패널의 `템` 버튼에서 여는, 현재 맵 획득 목록 전용 축약 패널. */
@Composable
fun ItemOverlay(sessionState: StateFlow<SessionState>) {
    val session by sessionState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A).copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFE2E8F0).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("획득 아이템", color = Color(0xFFCBD5E1), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                formatFire(session.currentMapValue),
                color = when {
                    session.currentMapValue < 0 -> Color(0xFFF87171)
                    session.currentMapValue > 0 -> Color(0xFF4ADE80)
                    else -> Color(0xFF94A3B8)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (session.recentPickups.isEmpty()) {
            Text("(없음)", color = Color(0xFF94A3B8), fontSize = 9.sp)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ROW_HEIGHT * 5)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                for (pickup in session.recentPickups) PickupRow(pickup)
            }
        }
    }
}

@Composable
fun HudOverlay(
    sessionState: StateFlow<SessionState>,
    priceState: StateFlow<com.mttd.data.prices.PriceRepository.State>? = null,
    onCollapse: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onTogglePause: () -> Unit = {},
    onRefreshHoldings: () -> Unit = {},
    onReset: () -> Unit = {},
    /** mTTD 자체를 종료한다. 되돌릴 수 없다 — 한 번 탭으로 바로 실행된다. */
    onExitApp: () -> Unit = {},
    /** 창-이동 드래그 시작 — [OverlayHost] 가 현재 창 위치를 앵커로 캡처하는 용도. */
    onDragStart: () -> Unit = {},
    /** 창-이동 드래그 중 매 스텝 델타(px, 이전 이벤트 기준 증분). */
    onDragBy: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    /** 창-이동 드래그 종료 — 위치 영속화 트리거용. */
    onDragEnd: () -> Unit = {},
) {
    val session by sessionState.collectAsStateWithLifecycle()
    val prices = priceState?.collectAsStateWithLifecycle()?.value

    // 0 = 수익 화면, 1 = 보유 아이템(가치) 화면. 거래소를 드나들 때는 지금까지처럼 자동으로
    // 맞는 화면이 뜨고, 그 사이에는 좌우로 쓸어서 수동으로도 넘길 수 있다 — 거래소 밖에서
    // 가방 가치를 확인할 길이 없던 것을 메운다.
    var page by remember { mutableStateOf(if (session.inExchange) 1 else 0) }
    LaunchedEffect(session.inExchange) {
        page = if (session.inExchange) 1 else 0
    }
    val density = LocalDensity.current
    val swipeThresholdPx = remember(density) { with(density) { 40.dp.toPx() } }
    // 창-이동 롱프레스를 인식했을 때의 진동. 예전 View 기반 드래그(attachDragBehavior)가
    // 주던 피드백이라, Compose 로 옮기면서 같이 가져온다.
    val dragHaptics = LocalHapticFeedback.current
    // 창-이동이 시작되면 본문 목록의 세로 스크롤을 잠근다.
    //
    // 창-이동 제스처는 본문 Column(조상)에 걸려 있고 목록의 verticalScroll 은 그 자손이다.
    // Compose 의 Main 패스는 자손이 먼저 받으므로, 롱프레스가 성립해 창이 따라오기 시작해도
    // 세로로 8dp 쯤 끌면 목록이 자기 슬롭을 채워 먼저 consume 해 버린다 — 조상의 드래그 루프는
    // 소비된 이벤트를 보고 onDragCancel 로 빠진다. 햅틱까지 울린 뒤 창이 멈추고 목록만
    // 스크롤되는 회귀다(예전 View 기반 attachDragBehavior 는 터치를 통째로 가로채서 이런 일이
    // 없었다). 롱프레스가 성립한 뒤에는 스크롤을 아예 꺼서 조상이 이기게 한다.
    var windowDragging by remember { mutableStateOf(false) }

    // 시간이 실제로 흐를 때만 1 초 틱을 돌린다 (일시정지·집계 대기 중엔 정지).
    val ticking = session.active &&
        !session.paused &&
        session.baselineReady &&
        (session.timeTrackingMode != com.mttd.domain.models.TimeTrackingMode.MAP_ONLY || session.inMap)
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(ticking) {
        while (ticking) { delay(1000); tick++ }
    }
    val elapsed = remember(session.startedAtMs, session.active, session.endedAtMs, session.paused, session.pausedAccumulatedMs, session.pausedSinceMs, session.timeTrackingMode, session.inMap, session.mapElapsedAccumulatedMs, session.mapElapsedSinceMs, tick) {
        session.elapsedMs
    }
    val incomePerHour = remember(session.totalValue, session.active, session.endedAtMs, session.paused, session.timeTrackingMode, session.inMap, session.mapElapsedAccumulatedMs, session.mapElapsedSinceMs, tick) {
        session.incomePerHour
    }
    val netIncomePerHour = remember(session.netTotalValue, session.active, session.endedAtMs, session.paused, session.timeTrackingMode, session.inMap, session.mapElapsedAccumulatedMs, session.mapElapsedSinceMs, tick) {
        session.netIncomePerHour
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A).copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFE2E8F0).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 헤더의 빈 영역을 누르면 다시 요약 바로 접힌다. 헤더 안의 제어 버튼은
            // 각자 클릭 처리를 유지한다.
            //
            // 접기 전용 ✕ 버튼은 뺐다 — 바로 옆 종료(🚪)와 나란히 있으면 둘 다 "닫기" 로
            // 읽히는데 결과가 정반대다(하나는 패널만 접고, 하나는 세션을 버린다).
            // 접는 길은 이 헤더 탭과 미니패널 탭으로 이미 두 개 있다.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCollapse),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusText = when {
                    session.paused -> "● 중지중"
                    !session.baselineReady -> "● 로그·가방"
                    session.timeTrackingMode == com.mttd.domain.models.TimeTrackingMode.MAP_ONLY && !session.inMap -> "● 대기중"
                    else -> "● 진행중"
                }
                val statusColor = when {
                    session.paused -> Color(0xFFF87171)
                    !session.baselineReady -> Color(0xFFFB923C)
                    session.timeTrackingMode == com.mttd.domain.models.TimeTrackingMode.MAP_ONLY && !session.inMap -> Color(0xFF94A3B8)
                    else -> Color(0xFF4ADE80)
                }
                Text(statusText, color = statusColor, fontSize = 10.sp, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                // 화면 전환 탭. 스와이프만 두면 제스처가 눈에 안 보여서 화면이 둘이라는 것
                // 자체를 모른다 — 점 두 개(●○)로 표시해 봤지만 무슨 뜻인지 읽히지 않았다.
                HudPageTabs(page = page, onSelect = { page = it })
                Spacer(Modifier.fillMaxWidth().weight(1f))
                // 버튼 사이 간격은 16dp — 24dp 버튼 사이에 그만큼의 무반응 구간을 둬서
                // 옆 버튼 오터치를 막는다.
                //
                // 폭 예산. 패널 280dp - 좌우 여백 20dp = 260dp 안에
                //   가장 긴 상태 문구("● 로그·가방", 10sp 로 약 58dp)
                // + 여백 6dp + 전환 탭(9.5sp 두 개 + 사이 7dp, 약 45dp)
                // + 버튼 4 개(96dp) + 간격 셋(48dp) = 약 253dp
                // 를 넣어 7dp 가 남는다. 전환 탭을 읽히는 크기로 키우면서 확보한 폭이라,
                // 간격을 20dp 로 되돌리면 상태 문구가 밀린다. 여기에 뭘 더 넣으려면 기기에서
                // 상태 문구가 잘리지 않는지 반드시 확인할 것.
                HudMaterialIconButton(Icons.Filled.Settings, onOpenSettings)
                Spacer(Modifier.width(16.dp))
                // 일시정지는 거래소 여부로, 새로고침/초기화는 보고 있는 화면으로 가른다.
                // 둘을 묶어두면 거래소 밖에서 "가치" 로 쓸어 넘겼을 때 초기화 버튼이 뜬다 —
                // 보유 가치를 보면서 누르는 새로고침은 재계산이어야지 세션 폐기면 안 된다.
                if (!session.inExchange) {
                    // 거래소 안에서는 pause 가 자동 제어라 수동 토글 버튼이 필요 없다.
                    HudMaterialIconButton(
                        if (session.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        onTogglePause,
                    )
                    Spacer(Modifier.width(16.dp))
                }
                if (page == 1) {
                    HudMaterialIconButton(Icons.Filled.Refresh, onRefreshHoldings)
                } else {
                    // 새로고침(가치 화면, 무해)과 아이콘까지 같으면 안 된다. 거래소 안에서는
                    // 일시정지 버튼이 빠져서 둘이 헤더의 같은 자리에 오는데, 화면만 넘기면
                    // 같은 자리 같은 모양이 무해한 재계산에서 세션 폐기로 바뀌어 버린다.
                    HudDangerIconButton(Icons.Filled.Delete, "초기화", onReset)
                }
                Spacer(Modifier.width(16.dp))
                HudDangerIconButton(Icons.AutoMirrored.Filled.Logout, "mTTD 종료", onExitApp)


            }

            // 스와이프(화면 전환)와 창-이동(길게 눌러 드래그)은 헤더를 뺀 이 본문 영역에만
            // 건다. 헤더는 탭하면 접히는 영역이라(위 Row 의 clickable), 같은 자리에 롱프레스
            // 드래그를 얹으면 접기와 창-이동이 같은 포인터 스트림을 두고 겨루게 된다.
            //
            // 창-이동은 예전엔 OverlayHost 가 View.setOnTouchListener 로 처리했는데, 스와이프
            // pointerInput 이 카드를 덮으면 Compose 가 터치 DOWN 을 먼저 가져가 버려서 View
            // 리스너가 이벤트를 아예 못 받는다(펼친 HUD 가 안 움직이는 버그). 두 제스처를 같은
            // pointerInput 파이프라인에 순서대로 두면, 앞쪽이 소비했을 때 뒤쪽이 알아서 물러난다.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var dragTotal = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragTotal = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragTotal += dragAmount
                            },
                            onDragEnd = {
                                if (dragTotal <= -swipeThresholdPx) page = 1
                                else if (dragTotal >= swipeThresholdPx) page = 0
                            },
                        )
                    }
                    .pointerInput(onDragStart, onDragBy, onDragEnd) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                windowDragging = true
                                dragHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDragStart()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDragBy(dragAmount.x, dragAmount.y)
                            },
                            onDragEnd = { windowDragging = false; onDragEnd() },
                            onDragCancel = { windowDragging = false; onDragEnd() },
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
            if (page == 1) {
                HoldingsBody(session.holdings, scrollEnabled = !windowDragging)
            } else {
                when {
                    prices != null && prices.loading -> Text(
                        "💰 시세 정보 업데이트 중...",
                        color = Color(0xFF60A5FA),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // <= 1 : 100300 override 하나만 있는 "빈 시즌" 상태도 시세 없음으로 취급
                    prices != null && prices.itemsWithPrice <= 1 -> Text(
                        "💰 시세 정보 없음",
                        color = Color(0xFFCBD5E1),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    !session.baselineReady -> Text(
                        "게임에서 로그 오픈 후 가방 정렬을 눌러주세요",
                        color = Color(0xFFFBBF24),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                HudStat("경과", formatElapsed(elapsed))
                HudStat("총 수익", formatFire(session.totalValue) + " (${formatFire(session.netTotalValue)} TAX)")
                HudStat("시간당", formatFire(incomePerHour) + "/h (${formatFire(netIncomePerHour)}/h TAX)")
                HudStat("맵 진입", "${session.mapsEntered}")

                Spacer(Modifier.height(1.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "이번 맵",
                        color = Color(0xFFCBD5E1),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    // 이번 맵 수익 합계 (목록이 상위 N 개로 잘려도 합계는 전체 기준)
                    Text(
                        formatFire(session.currentMapValue),
                        color = when {
                            session.currentMapValue < 0 -> Color(0xFFF87171)
                            session.currentMapValue > 0 -> Color(0xFF4ADE80)
                            else -> Color(0xFF94A3B8)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (session.recentPickups.isEmpty()) {
                    Text("(없음)", color = Color(0xFF94A3B8), fontSize = 10.sp)
                } else {
                    // 창 높이는 WRAP_CONTENT 라 목록이 짧으면 HUD 도 같이 작아진다.
                    // 길어질 때만 이 상한에서 멈추고 목록 안에서 스크롤.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = MAX_LIST_HEIGHT)
                            .verticalScroll(rememberScrollState(), enabled = !windowDragging),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (p in session.recentPickups) {
                            PickupRow(p)
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * 거래소 진입 시 수익 화면 대신 보이는 몸체 — 현재 보유 아이템을 가치순으로.
 * [SessionAggregator.enterExchange] 가 진입 시점에 [SessionState.holdings] 를 채운다.
 */
@Composable
private fun HoldingsBody(
    holdings: List<com.mttd.domain.models.PickupSummary>,
    /** 창-이동 중에는 false — [HudOverlay] 의 windowDragging 주석 참조. */
    scrollEnabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "🏪 보유 아이템 가치",
            color = Color(0xFFFBBF24),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            formatFire(holdings.sumOf { it.value }),
            color = Color(0xFFE2E8F0),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
    Spacer(Modifier.height(2.dp))
    if (holdings.isEmpty()) {
        Text("(보유 아이템 없음)", color = Color(0xFF94A3B8), fontSize = 10.sp)
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = HOLDINGS_LIST_HEIGHT)
                .verticalScroll(rememberScrollState(), enabled = scrollEnabled),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (p in holdings) {
                PickupRow(p)
            }
        }
    }
}

/**
 * "이번 맵" 한 줄 — 아이콘 · 이름 · 수량 · 가치.
 *
 * 이름만 남은 폭을 먹고, 수량/가치는 오른쪽에 고정 폭으로 붙여 세로로 열이 맞게 한다.
 */
@Composable
private fun PickupRow(p: com.mttd.domain.models.PickupSummary) {
    val txtColor = when {
        p.value < 0 -> Color(0xFFF87171)  // 소비 = 빨강
        p.value > 0 -> Color(0xFFE2E8F0)  // 픽업 = 밝은 회백
        else -> Color(0xFFCBD5E1)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconRef = p.iconUrl
        if (!iconRef.isNullOrBlank()) {
            AsyncImage(
                model = iconRef,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            p.itemName ?: "Unknown",
            color = txtColor,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // 1 개 획득도 ×1 로 표시 (예전엔 quantity == 1 이면 아예 숨겨서 빠진 것처럼 보였다)
        Text(
            if (p.quantity != 0) "×${p.quantity}" else "",
            color = txtColor,
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 30.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (p.value != 0.0) formatFire(p.value) else "-",
            color = txtColor,
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 46.dp),
        )
    }
}

/**
 * "수익" / "가치" 화면 전환 탭. 좌우 스와이프와 같은 동작을 눌러서도 할 수 있게 한다.
 *
 * 헤더 안 상태 문구 옆에 얹으므로 알약 배경도 패딩도 없다 — 폭 예산이 4dp 밖에 안 남아서
 * (헤더 Row 의 주석 참조) 배경을 주면 버튼이 밀린다. 현재 화면은 색과 굵기로만 구분한다.
 *
 * 거래소에 들어가면 자동으로 "가치" 로 넘어가므로, 이 탭은 거래소 밖에서 가방 가치를
 * 확인하거나 거래소 안에서 수익을 다시 볼 때 쓴다.
 */
@Composable
private fun HudPageTabs(page: Int, onSelect: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudPageTab("수익", selected = page == 0) { onSelect(0) }
        HudPageTab("가치", selected = page == 1) { onSelect(1) }
    }
}

@Composable
private fun HudPageTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Text(
        label,
        color = if (selected) Color(0xFF93C5FD) else Color(0xFF64748B),
        fontSize = 9.5.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        ),
    )
}

/**
 * Material Icons 기반 버튼. 이모지 렌더링 이슈 (⏸이 삼성 이모지 폰트에서 노랗게 뜨는 문제) 회피.
 */
@Composable
private fun HudMaterialIconButton(icon: ImageVector, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFFF1F5F9),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun HudIconButton(
    symbol: String,
    onClick: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    bold: Boolean = false,
) {
    // Material TextButton 은 min touch target 48dp 강제 + ripple 색이 테마 dynamic color 따라
    // 노란 계열로 나올 수 있음. Box + clickable(no ripple) 로 대체해서 딱 24dp, 배경 없음 유지.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = Color(0xFFF1F5F9),
            fontSize = fontSize,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun HudStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            color = Color(0xFFF1F5F9),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

internal fun formatElapsed(ms: Long): String {
    if (ms <= 0) return "0s"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, sec)
        else -> "%d:%02d".format(m, sec)
    }
}

/**
 * 게임 통화 표시. 매우 작은 값(0.01 이하)부터 큰 값(수백만) 까지 대응.
 *
 * 예전엔 1만 이상을 k/M 으로 축약했는데, 회차별 수익 그래프 Y축과 마찬가지로 정확한 수치가
 * 더 보기 좋다는 피드백으로 축약 없이 천단위 구분자(%,.0f)로 그대로 보여준다.
 */
internal fun formatFire(v: Double): String {
    val abs = kotlin.math.abs(v)
    return when {
        abs == 0.0 -> "0"
        abs < 1.0 -> "%.4f".format(v)
        abs < 100.0 -> "%.2f".format(v)
        else -> "%,.0f".format(v)
    }
}
/**
 * 되돌릴 수 없는 동작(초기화·종료)용 버튼. 한 번 탭으로 바로 실행된다.
 *
 * 실행 시 진동을 준다 — 오터치로 눌렸을 때 사용자가 알아채는 유일한 신호다.
 * 빨간 tint 도 같은 이유로 유지한다: 거래소 모드의 새로고침(무해)과 초기화(세션 폐기)는
 * 아이콘이 같아서, 색이 둘을 구분하는 유일한 단서다.
 */
@Composable
private fun HudDangerIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color(0xFFF87171),
            modifier = Modifier.size(16.dp),
        )
    }
}
