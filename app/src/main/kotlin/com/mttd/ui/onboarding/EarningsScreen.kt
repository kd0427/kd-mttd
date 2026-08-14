package com.mttd.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mttd.TrackerApplication
import com.mttd.domain.models.MapRun
import com.mttd.domain.models.PickupSummary
import com.mttd.domain.models.SessionState
import com.mttd.ui.overlay.formatFire
import com.mttd.ui.theme.MttdColors
import kotlinx.coroutines.flow.MutableStateFlow

// 시안 팔레트와 같은 값. Canvas 의 onDraw 안에서도 써야 해서 MaterialTheme 을 읽을 수 없으므로
// top-level 상수로 둔다 — Theme.kt 의 primary / error / onPrimaryContainer 와 항상 맞춰야 한다.
// 시안에는 초록이 없어서, 수익의 부호는 블루↔빨강으로 구분하고 선택은 더 진한 블루로 띄운다.
private val POSITIVE = Color(0xFF1687F8)
private val NEGATIVE = Color(0xFFE5484D)
private val SELECTED = Color(0xFF0F6BC9)

/** 그래프 가로 격자선 기본 간격 (수익 단위). 회차 수익이 커지면 배수로 늘어난다. */
private const val GRID_STEP = 50.0
/** 수익이 [GRID_STEP] 미만일 때 쓰는 눈금 후보 (1-2-5 계열). */
private val SMALL_STEPS = doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0)

/** 축 라벨용 — 소수점 이하가 없으면 떼고, 천 단위 구분자로 정확한 수치를 보여준다. (50.00 → 50, 2000 → 2,000) */
private fun formatAxis(v: Double): String {
    return if (v == v.toLong().toDouble()) "%,d".format(v.toLong()) else "%,.1f".format(v)
}

/**
 * 회차가 하나도 없을 때의 빈 상태 블록.
 *
 * Compose 에는 점선 테두리 Modifier 가 없어서 [drawBehind] 로 직접 그린다.
 */
@Composable
private fun EmptyRunsBlock() {
    val radius = 12.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(radius))
            .drawBehind {
                val r = radius.toPx()
                drawRoundRect(
                    color = MttdColors.DashBorder,
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.BarChart,
            contentDescription = null,
            tint = MttdColors.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "아직 기록된 회차가 없습니다. 맵을 열면 회차가 시작됩니다.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 회차별 수익 막대 그래프.
 *
 * - 막대 하나 = 맵 1회차. 높이는 |수익| 비례, 색은 부호(블루/빨강).
 * - 막대를 누르면 [onSelect] 로 회차 선택 → 상세 목록 표시.
 * - 회차가 많아지면 가로 스크롤.
 */
@Composable
fun RunBarChart(
    runs: List<MapRun>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) {
        EmptyRunsBlock()
        return
    }

    val maxPositive = runs.filter { it.totalValue > 0 }.maxOfOrNull { it.totalValue } ?: 0.0
    val maxNegative = runs.filter { it.totalValue < 0 }.maxOfOrNull { -it.totalValue } ?: 0.0
    val hasNegative = maxNegative > 0.0
    val maxAbs = kotlin.math.max(maxPositive, maxNegative).coerceAtLeast(1e-9)
    // 양수/음수 영역 높이를 실제 최댓값 비율대로 나눈다. 예전엔 음수가 하나라도 있으면
    // 무조건 38%(1 - 0.62)를 고정 배정해서, 음수 값이 아무리 작아도 그 영역이 불필요하게
    // 크게 보였다. 한쪽이 극단적으로 작아도 완전히 안 보이진 않게 최소 비율은 남겨둔다.
    val zeroFrac = if (hasNegative) {
        (maxPositive / (maxPositive + maxNegative)).toFloat().coerceIn(0.08f, 0.92f)
    } else 1f
    val density = LocalDensity.current
    // 막대 그래프와 아래 회차 번호 라벨이 같이 스크롤되도록 하나의 상태를 공유한다.
    val chartScrollState = rememberScrollState()
    val barW = 28.dp
    val gap = 6.dp
    val chartH = 150.dp
    val totalW = (barW + gap) * runs.size
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val gridLabelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 기본 눈금은 50 단위. 다만
    //  - 회차 수익이 커지면 선이 촘촘해지므로 100, 150 … 으로 배수를 올리고
    //  - 반대로 수익이 50 보다 작으면 50 단위 선이 **한 줄도 안 그려지므로**
    //    1-2-5 계열로 내려가 항상 기준선이 보이게 한다.
    val step = remember(maxAbs) {
        if (maxAbs >= GRID_STEP) {
            var s = GRID_STEP
            while (maxAbs / s > 6) s += GRID_STEP
            s
        } else {
            SMALL_STEPS.firstOrNull { maxAbs / it <= 4 } ?: GRID_STEP
        }
    }

    Column(modifier = modifier) {
        Row {
            // Y 축 눈금 라벨 (고정, 그래프와 함께 스크롤되지 않음) — 양수 영역 기준.
            // k 축약 없이 천단위 구분자로 정확한 수치를 보여주므로 자릿수가 늘어날 걸 감안해 폭을 넉넉히.
            Box(modifier = Modifier.height(chartH).width(52.dp)) {
                if (maxPositive > 0.0) {
                    var v = step
                    while (v <= maxPositive * 1.02) {
                        val frac = (v / maxPositive).toFloat().coerceIn(0f, 1f) * 0.95f
                        Text(
                            formatAxis(v),
                            modifier = Modifier.align(Alignment.TopEnd)
                                .padding(
                                    top = ((chartH * (zeroFrac - frac * zeroFrac)) - 6.dp).coerceAtLeast(0.dp),
                                    end = 4.dp,
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = gridLabelColor,
                            maxLines = 1,
                        )
                        v += step
                    }
                }
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(chartScrollState)
                    .height(chartH),
            ) {
                Canvas(
                    modifier = Modifier
                        .width(totalW)
                        .height(chartH)
                        .pointerInput(runs.size, maxAbs) {
                            detectTapGestures { offset ->
                                val slot = with(density) { (barW + gap).toPx() }
                                val i = (offset.x / slot).toInt()
                                runs.getOrNull(i)?.let { onSelect(it.id) }
                            }
                        }
                ) {
                    val slotPx = with(density) { (barW + gap).toPx() }
                    val barPx = with(density) { barW.toPx() }
                    // 0 기준선: 양수는 위로, 음수는 아래로. 높이 배분은 zeroFrac(실제 최댓값 비율) 기준.
                    val zeroY = size.height * zeroFrac
                    val usableUp = zeroY
                    val usableDown = size.height - zeroY

                    // 가로 격자선 — 양수/음수 각자의 최댓값 기준으로 따로 그린다
                    // (한쪽이 다른 쪽보다 훨씬 작으면 그만큼 격자선도 적게 그려짐).
                    if (maxPositive > 0.0) {
                        var v = step
                        while (v <= maxPositive * 1.02) {
                            val frac = (v / maxPositive).toFloat().coerceIn(0f, 1f) * 0.95f
                            val up = zeroY - frac * usableUp
                            drawLine(gridColor, Offset(0f, up), Offset(size.width, up), strokeWidth = 1f)
                            v += step
                        }
                    }
                    if (hasNegative) {
                        var v = step
                        while (v <= maxNegative * 1.02) {
                            val frac = (v / maxNegative).toFloat().coerceIn(0f, 1f) * 0.95f
                            val down = zeroY + frac * usableDown
                            drawLine(gridColor, Offset(0f, down), Offset(size.width, down), strokeWidth = 1f)
                            v += step
                        }
                    }

                    // 0 기준선 (격자보다 진하게)
                    drawLine(
                        color = MttdColors.TextMuted,
                        start = Offset(0f, zeroY),
                        end = Offset(size.width, zeroY),
                        strokeWidth = 2f,
                    )

                    runs.forEachIndexed { i, run ->
                        val positive = run.totalValue >= 0
                        // maxPositive/maxNegative 는 해당 부호의 회차가 하나도 없으면 0.0 —
                        // totalValue==0.0 인 회차가 그 부호 쪽으로 분류되면 0.0/0.0 = NaN 이 되어
                        // drawRect 에 NaN 크기가 들어가던 버그. 옛 maxAbs 처럼 coerceAtLeast 로 방지.
                        val ownMax = (if (positive) maxPositive else maxNegative).coerceAtLeast(1e-9)
                        val ratio = (kotlin.math.abs(run.totalValue) / ownMax).toFloat().coerceIn(0f, 1f)
                        val h = ratio * (if (positive) usableUp else usableDown) * 0.95f
                        val x = i * slotPx
                        val top = if (positive) zeroY - h else zeroY
                        val base = if (run.totalValue < 0) NEGATIVE else POSITIVE
                        drawRect(
                            color = if (run.id == selectedId) SELECTED else base,
                            topLeft = Offset(x, top),
                            size = Size(barPx, h.coerceAtLeast(2f)),
                        )
                    }
                }
            }
        }
        // 회차 번호 라벨 (막대와 같은 폭으로 정렬, 같은 스크롤 상태 공유)
        Row(modifier = Modifier.horizontalScroll(chartScrollState).padding(start = 52.dp)) {
            runs.forEach { run ->
                Text(
                    "${run.index}",
                    // padding 이 먼저(바깥) 와야 이 라벨의 전체 폭이 barW+gap 이 되어 막대 슬롯
                    // 폭(slotPx)과 맞는다. width 를 먼저 적용하면 padding 이 barW 안쪽으로
                    // 먹혀버려서(전체 폭이 barW 로만 잡힘) 라벨이 뒤로 갈수록 막대에서 밀렸었다.
                    modifier = Modifier.padding(end = gap).width(barW),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (run.id == selectedId) SELECTED
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (run.id == selectedId) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 회차 상세 팝업 — 획득 아이템 목록 + 시세, 삭제.
 *
 * 아이템 목록은 메모리에 들고 있지 않고 **팝업을 열 때 디스크에서 읽는다**
 * ([loadItems]). 회차가 쌓여도 프로세스 메모리가 늘지 않게 하는 쪽의 짝.
 */
@Composable
fun RunDetailDialog(
    run: MapRun,
    loadItems: suspend (Long) -> List<PickupSummary>,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirming by remember(run.id) { mutableStateOf(false) }
    var items by remember(run.id) { mutableStateOf<List<PickupSummary>?>(null) }
    LaunchedEffect(run.id) { items = loadItems(run.id) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${run.index}회차" + (run.mapName?.let { " · $it" } ?: "") +
                                if (run.inProgress) " (진행 중)" else "",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "수익 ${formatFire(run.totalValue)} · ${run.itemCount}종 · " +
                                com.mttd.ui.overlay.formatElapsed(run.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 삭제는 헤더에. 자주 쓰는 닫기는 아래쪽 넓은 버튼으로.
                    TextButton(onClick = { confirming = true }) {
                        Text("삭제", color = NEGATIVE)
                    }
                }

                HorizontalDivider()

                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val list = items
                    when {
                        list == null -> Text("불러오는 중...", style = MaterialTheme.typography.bodySmall)
                        list.isEmpty() -> Text("(획득 없음)", style = MaterialTheme.typography.bodySmall)
                        else -> {
                            ItemRowHeader()
                            for (p in list) ItemRow(p)
                        }
                    }
                }

                HorizontalDivider()

                if (confirming) {
                    Text(
                        "이 회차를 지우면 세션 총 수익·아이템 합계에서도 빠집니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NEGATIVE,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { confirming = false },
                            modifier = Modifier.weight(1f),
                        ) { Text("취소") }
                        Button(
                            onClick = { confirming = false; onDelete(run.id); onDismiss() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = NEGATIVE),
                        ) { Text("삭제 확인") }
                    }
                } else {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("닫기") }
                }
            }
        }
    }
}

/**
 * 세션 전체 아이템 집계 — 이름 / 총 개수 / 총 가치.
 *
 * 합계는 [com.mttd.domain.SessionAggregator] 가 증분으로 유지한 것을 그대로 쓴다.
 * 여기서 회차를 매번 훑으면 픽업 1 건마다 O(회차 × 아이템) 이 된다.
 */
@Composable
fun TotalItemsCard(merged: List<PickupSummary>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("전체 획득 아이템", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    "${merged.size}종 · ${formatFire(merged.sumOf { it.value })}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MttdColors.DividerSoft)
            if (merged.isEmpty()) {
                Text("(아직 없음)", fontSize = 13.sp, color = MttdColors.TextMuted)
            } else {
                ItemRowHeader()
                for (p in merged) ItemRow(p)
            }
        }
    }
}

/**
 * "자산" 탭 — 현재 가방에 있는 아이템을 가치 내림차순으로 표시.
 *
 * 거래소(경매장) 진입 시 [com.mttd.domain.SessionAggregator.enterExchange] 가 자동으로
 * 갱신하고, 여기서는 수동 새로고침 버튼만 노출한다. 슬롯 상태(`slotLastCount`)는 항상
 * 최신이므로 "새로고침" 은 서버 재조회가 아니라 그 시점 스냅샷을 다시 계산하는 것.
 */
@Composable
fun ValueScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val session by (service?.sessionState ?: MutableStateFlow(SessionState()))
        .collectAsStateWithLifecycle()

    val totalValue = session.holdings.sumOf { it.value }

    // 시안의 자산 탭처럼 요약과 목록을 분리해, 보유 아이템이 아직 없을 때도
    // 화면이 비어 보이지 않도록 한다.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text("보유 아이템 가치", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${session.holdings.size}종 · ${formatFire(totalValue)}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 시안의 새로고침은 사각 버튼이 아니라 블루 톤 캡슐이다.
                OutlinedButton(
                    onClick = { service?.refreshHoldings() },
                    enabled = service != null,
                    modifier = Modifier.height(36.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    border = BorderStroke(1.dp, MttdColors.BlueBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("새로고침", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            Text(
                formatFire(totalValue),
                fontSize = 40.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1).sp,
                color = MaterialTheme.colorScheme.primary,
            )
            if (session.inExchange) {
                Text(
                    "🏪 거래소 화면이 열려있어 집계가 일시정지되었습니다.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            HorizontalDivider(color = MttdColors.DividerSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MttdColors.BlueFaint,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    "거래소에 들어가거나 새로고침을 누르면 보유 아이템이 표시됩니다.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        if (session.holdings.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 시안의 빈 상태 아이콘 박스 — 글리프 대신 테두리 있는 사각 박스에 아이콘을 담는다.
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(20.dp))
                        .border(1.dp, MttdColors.DividerSoft, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Inventory2,
                        contentDescription = null,
                        tint = MttdColors.TextMuted,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("표시할 아이템이 없습니다", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "거래소에 한 번 진입하면 보유 아이템과\n개별 시세가 이 목록에 표시됩니다.",
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Button(
                    onClick = { service?.refreshHoldings() },
                    enabled = service != null,
                    modifier = Modifier.height(42.dp),
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("지금 새로고침", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("보유 아이템 목록", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = MttdColors.DividerSoft)
                ItemRowHeader()
                for (p in session.holdings) ItemRow(p)
            }
        }
    }

    // 시안 하단 캡션.
    Text(
        "아이템 목록은 시세 갱신 시각 기준으로 정렬됩니다",
        fontSize = 11.sp,
        color = MttdColors.TextMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )

    Text(
        "아이템 목록은 시세 갱신 시각 기준으로 정렬됩니다.",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
    )
}

@Composable
private fun ItemRowHeader() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(24.dp))
        Text("아이템", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        Text("개수", modifier = Modifier.widthIn(min = 44.dp), textAlign = TextAlign.End,
             style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(10.dp))
        Text("단가", modifier = Modifier.widthIn(min = 60.dp), textAlign = TextAlign.End,
             style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(10.dp))
        Text("가치", modifier = Modifier.widthIn(min = 66.dp), textAlign = TextAlign.End,
             style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ItemRow(p: PickupSummary) {
    val color = when {
        p.value < 0 -> NEGATIVE
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val iconRef = p.iconUrl
        if (!iconRef.isNullOrBlank()) {
            AsyncImage(
                model = iconRef,
                contentDescription = null,
                modifier = Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(6.dp))
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Text(
            p.itemName ?: p.itemId ?: "Unknown",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        Text(
            "×${p.quantity}",
            modifier = Modifier.widthIn(min = 44.dp),
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (p.unitPrice > 0f) formatFire(p.unitPrice.toDouble()) else "미상",
            modifier = Modifier.widthIn(min = 60.dp),
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (p.value != 0.0) formatFire(p.value) else "-",
            modifier = Modifier.widthIn(min = 66.dp),
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}
