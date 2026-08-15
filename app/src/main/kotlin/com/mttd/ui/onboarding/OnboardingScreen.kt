package com.mttd.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mttd.IUserService
import com.mttd.ui.theme.MttdColors
import com.mttd.TrackerApplication
import com.mttd.data.log.LogPoller
import com.mttd.data.shizuku.ShizukuState
import com.mttd.domain.models.SessionState
import com.mttd.service.TrackerForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 상단 탭. "수익"(이번 세션에 번 것)과 "자산"(지금 가방에 있는 것)은 흐름과 잔고의 관계라,
 * 나란히 놓으면 서로를 설명한다 — 예전 이름 "가치" 는 무엇의 가치인지가 드러나지 않았다.
 */
private enum class MainTab(val label: String) {
    EARNINGS("수익"),
    VALUE("자산"),
    SETTINGS("설정"),
}

/**
 * 앱 메인 화면 — **수익 집계**와 **옵션 설정**을 탭으로 분리.
 *
 * - 수익 탭: 세션 집계(경과·총 수익·시간당·픽업 목록) + 현재 시세 요약 한 줄
 * - 설정 탭: Shizuku · 오버레이 · 시세 출처 · 로그 프로브 · 로그 tail
 */
@Composable
fun OnboardingScreen(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    userService: () -> IUserService?,
    onReopenWizard: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(MainTab.EARNINGS) }
    val context = LocalContext.current
    // "업데이트 확인" 을 눌러서 새 버전을 찾았을 때만 배너를 펼친다. 서비스가 시작할 때
    // 자동으로 하는 확인에서는 예전처럼 접힌 채로 둔다 — 앱을 열 때마다 큰 카드가 펼쳐져
    // 있으면 성가시다. 눌러서 찾을 때마다 다시 펼쳐야 하므로 Boolean 이 아니라 카운터다.
    var expandUpdateSignal by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // 일부 기기(멀티윈도우/프리폼 모드 등)가 인셋을 음수로 내려주는 경우가 있어
        // Modifier.padding() 이 "Padding must be non-negative" 로 즉시 크래시한다 — 방어적으로 clamp.
        val layoutDirection = LocalLayoutDirection.current
        val safePadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection).coerceAtLeast(0.dp),
            top = padding.calculateTopPadding().coerceAtLeast(0.dp),
            end = padding.calculateEndPadding(layoutDirection).coerceAtLeast(0.dp),
            bottom = padding.calculateBottomPadding().coerceAtLeast(0.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(safePadding),
        ) {
            // 상단 브랜딩 영역은 흰 면, 본문은 옅은 블루그레이 바탕. 시안의 기본 골격이다.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            ) {
                // 시안은 업데이트 버튼 줄을 먼저 놓고 로고 블록을 30px 끌어올려 겹친다.
                // Compose 에는 음수 패딩이 없으므로 같은 Box 안에서 로고는 상단 중앙,
                // 버튼은 상단 우측으로 배치해 같은 결과를 만든다.
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "v${com.mttd.BuildConfig.VERSION_NAME.substringBefore('-')}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Image(
                            painter = painterResource(com.mttd.R.drawable.ic_mttd_header),
                            contentDescription = "고인물 mTTD 앱 아이콘",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .shadow(6.dp, RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp)),
                        )
                        Text(
                            text = "고인물 mTTD",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        UpdateCheckButton(onUpdateFound = { expandUpdateSignal++ })
                    }
                }

                // 탭 트랙. 시안은 테두리 없이 인셋 배경만 쓴다.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MainTab.entries.forEach { item ->
                        MainSegment(
                            label = item.label,
                            selected = tab == item,
                            onClick = { tab = item },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // 탭마다 독립된 스크롤 위치를 갖도록 tab 이 바뀌면 새 ScrollState 로 교체 —
            // 하나의 ScrollState 를 공유하면 한 탭에서 내려놓은 스크롤 위치가 다른 탭에도
            // 그대로 남아 위쪽 카드들이 화면 밖으로 밀려 안 보이는 문제가 있었다.
            val scrollState = remember(tab) { androidx.compose.foundation.ScrollState(0) }
            // 배너를 펼칠 땐 이미 아래로 스크롤한 상태일 수 있다. 맨 위로 올려줘야
            // "확인 버튼을 눌렀더니 바로 보인다" 가 성립한다.
            LaunchedEffect(expandUpdateSignal) {
                if (expandUpdateSignal > 0) scrollState.animateScrollTo(0)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 헤더가 아니라 여기 둔다 — 헤더는 스크롤되지 않아서, 릴리스 노트가 긴 채로
                // 펼치면 탭과 본문을 화면 밖으로 밀어내고 되돌릴 방법이 없다.
                UpdateBanner(expandSignal = expandUpdateSignal)
                when (tab) {
                    MainTab.EARNINGS -> {
                        if (!state.ready) {
                            NotReadyNotice(onGoToSettings = { tab = MainTab.SETTINGS })
                        }
                        EarningsSummaryCard()
                        RunHistorySection()
                        PriceSummaryLine()
                    }
                    MainTab.VALUE -> {
                        ValueScreen()
                    }
                    MainTab.SETTINGS -> {
                        ShizukuStatusCard(state = state, onRequestPermission = onRequestPermission)
                        // 오버레이 권한은 Shizuku 와 무관(userService 안 씀)해서 게이트 밖으로 뺐다 —
                        // 안 그러면 재부팅으로 Shizuku 가 죽었을 때 이미 켜둔 오버레이 설정을
                        // 확인/조정할 카드 자체가 통째로 사라져 보인다.
                        OverlayCard()
                        if (state.ready) {
                            PriceCard()
                            LoadoutExportCard()
                            AdvancedSection(userService = userService)
                        } else {
                            Text(
                                text = "Shizuku 준비 완료 후 나머지 설정이 활성화됩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onReopenWizard) { Text("설정 가이드 다시 보기") }
                        ExitCard()
                    }
                }
            }
        }
    }
}

/** 시안의 상단 탭. 선택된 탭만 블루 캡슐로 띄우고 그림자로 살짝 들어 올린다. */
@Composable
private fun MainSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .then(if (selected) Modifier.shadow(4.dp, RoundedCornerShape(11.dp)) else Modifier)
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(11.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 새 버전 알림 배너. 두 탭 모두 위에 뜬다.
 *
 * 확인은 서비스 시작 시 1회 자동으로 하고, 설정의 "업데이트 확인" 버튼으로도 부를 수 있다.
 * APK는 앱 안에서 받은 뒤 Android 시스템 업데이트 화면으로 넘긴다.
 *
 * @param expandSignal 0보다 커지면 배너를 펼친다. 사용자가 직접 "업데이트 확인" 을 눌러
 *                     새 버전을 찾은 경우에만 증가하므로, 시작 시 자동 확인에서는 접힌 채로
 *                     남는다. 눌렀다 접었다를 반복해도 매번 다시 펼치려면 Boolean 이 아니라
 *                     값이 바뀌는 카운터여야 한다.
 */
@Composable
private fun UpdateBanner(expandSignal: Int = 0) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val update by (service?.availableUpdate
        ?: MutableStateFlow<com.mttd.data.update.UpdateChecker.Update?>(null))
        .collectAsStateWithLifecycle()

    val u = update ?: return
    var dismissed by remember(u.versionName) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var installing by remember(u.versionName) { mutableStateOf(false) }
    var installMessage by remember(u.versionName) { mutableStateOf<String?>(null) }
    // 기본은 접힘 — 시작할 때 자동으로 확인한 결과는 한 줄로만 알리고, 자세한 내용은 탭해서 보게.
    var expanded by remember(u.versionName) { mutableStateOf(false) }

    // 사용자가 직접 "업데이트 확인" 을 눌러 새 버전을 찾았을 때. "나중에" 로 닫아뒀던 것도
    // 같이 되살린다 — 버튼에는 새 버전이 떴는데 카드가 안 보이면 앞뒤가 안 맞는다.
    // early return 보다 앞에 둬야 닫힌 상태에서도 이 신호를 받을 수 있다.
    LaunchedEffect(expandSignal) {
        if (expandSignal > 0) {
            dismissed = false
            expanded = true
        }
    }
    if (dismissed) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = androidx.compose.material3.CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
            ),
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⬆️ 새 버전 ${u.versionName}",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "접기" else "펼치기",
                )
            }
            if (expanded) {
                Text(
                    "현재 ${com.mttd.BuildConfig.VERSION_NAME}" +
                        if (u.apkSizeBytes > 0) "  ·  APK %.1f MB".format(u.apkSizeBytes / 1024.0 / 1024.0) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (u.notes.isNotBlank()) {
                    Text(parseSimpleMarkdown(u.notes), style = MaterialTheme.typography.bodySmall, maxLines = 6)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (u.apkUrl != null) {
                        Button(
                            enabled = !installing,
                            onClick = {
                                scope.launch {
                                    installing = true
                                    installMessage = when (
                                        val result = com.mttd.data.update.AppUpdateInstaller
                                            .downloadAndInstall(context.applicationContext, u.apkUrl)
                                    ) {
                                        com.mttd.data.update.AppUpdateInstaller.Result.InstallerOpened ->
                                            "시스템 업데이트 화면을 열었습니다. '업데이트'를 눌러 적용하세요."
                                        com.mttd.data.update.AppUpdateInstaller.Result.PermissionSettingsOpened ->
                                            "설정에서 '이 출처 허용'을 켠 뒤 다시 눌러주세요."
                                        is com.mttd.data.update.AppUpdateInstaller.Result.Failed -> result.message
                                    }
                                    installing = false
                                }
                            },
                        ) {
                            if (installing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("준비 중...")
                            } else {
                                Text("앱에서 업데이트")
                            }
                        }
                    } else {
                        Button(onClick = {
                            val i = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(u.releaseUrl),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                        }) { Text("릴리스 열기") }
                    }
                    OutlinedButton(onClick = { dismissed = true }) { Text("나중에") }
                }
                installMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * "**굵게**" 만 지원하는 아주 단순한 마크다운 파서.
 *
 * GitHub 릴리스 노트는 "- ✨ **제목**: 설명" 형식만 쓰므로 이 정도면 충분하고,
 * 마크다운 라이브러리를 새로 추가할 이유는 없다.
 */
private fun parseSimpleMarkdown(text: String): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("**", i)
            if (start == -1) {
                append(text.substring(i))
                break
            }
            append(text.substring(i, start))
            val end = text.indexOf("**", start + 2)
            if (end == -1) {
                append(text.substring(start))
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(start + 2, end))
            }
            i = end + 2
        }
    }

@Composable
private fun NotReadyNotice(onGoToSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MttdColors.WarnLine),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Shizuku 연결 필요", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "로그를 읽으려면 Shizuku를 준비해 주세요.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 시안은 경고 카드 안에서도 액션은 브랜드 블루로 둔다 — 오렌지는 상태 표시 전용.
            Button(
                onClick = onGoToSettings,
                modifier = Modifier.height(34.dp),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text("설정", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 수익 탭 맨 위 — 수익 숫자만. 세션 제어/상태는 설정 탭. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EarningsSummaryCard() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val session by (service?.sessionState ?: MutableStateFlow(SessionState()))
        .collectAsStateWithLifecycle()

    val ticking = session.active && !session.paused && session.baselineReady
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(ticking) { while (ticking) { kotlinx.coroutines.delay(1000); tick++ } }
    val elapsed = remember(session.startedAtMs, session.paused, tick) { session.elapsedMs }
    val perHour = remember(session.totalValue, session.paused, tick) { session.incomePerHour }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 좁은 화면에서 숫자 + 버튼 2개가 한 줄에 안 들어가면 버튼 묶음이 통째로
            // 다음 줄로 넘어간다. 버튼 묶음을 하나의 FlowRow 항목으로 묶고 SpaceBetween 을
            // 써야 (넓은 화면에서) 예전처럼 카드 오른쪽 끝에 붙는다 — 버튼 두 개를 각각
            // 별개 항목으로 두면 줄바꿈 안 될 때도 텍스트 바로 옆에 붙어버려서 안 예뻤다.
            var confirmReset by remember { mutableStateOf(false) }
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column {
                    Text(
                        com.mttd.ui.overlay.formatFire(session.totalValue),
                        fontSize = 40.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1).sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "총 수익" +
                            if (session.unpricedPickups > 0) "  ·  미가격 ${session.unpricedPickups}건" else "",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        onClick = { service?.togglePause() },
                        enabled = service != null,
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = if (session.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(if (session.paused) "재개" else "일시정지")
                    }
                    OutlinedButton(
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        onClick = { confirmReset = true },
                        enabled = service != null,
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MttdColors.DangerLine),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("리셋")
                    }
                }
                if (confirmReset) {
                    // 같은 자리에서 버튼만 바뀌는 인라인 확인은 빠르게 두 번 탭하면
                    // 바뀐 자리의 "확인"이 그대로 눌려버릴 수 있어 별도 팝업으로 분리.
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text("세션을 리셋할까요?") },
                        text = { Text("지금까지 집계된 수익·회차 기록이 모두 사라집니다.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmReset = false
                                service?.resetSession()
                            }) { Text("리셋", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmReset = false }) { Text("취소") }
                        },
                    )
                }
            }
            if (session.paused) {
                Text(
                    "일시정지 중 — 경과 시간과 집계가 멈춰 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            // 시안의 inset 4분할. 구분선은 항목 사이에만 들어가고 칸 높이를 꽉 채운다
            // (IntrinsicSize.Min 이라야 VerticalDivider 가 가장 높은 칸에 맞춰 늘어난다).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp))
                    .border(1.dp, MttdColors.DividerSoft, RoundedCornerShape(12.dp)),
            ) {
                MiniStat("시간당", com.mttd.ui.overlay.formatFire(perHour), "/h", Modifier.weight(1f))
                StatDivider()
                MiniStat("시간", formatElapsed(elapsed), "", Modifier.weight(1f))
                StatDivider()
                MiniStat("맵", "${session.mapsEntered}", "회", Modifier.weight(1f))
                StatDivider()
                MiniStat("획득", "${session.pickupCount}", "개", Modifier.weight(1f))
            }
            if (!session.baselineReady) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎒", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "로그 오픈 후 가방을 정렬해주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 시안은 값과 단위를 분리해 단위를 작고 흐리게 깐다.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = (-0.3).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    unit,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 4분할 통계 칸 사이의 세로 구분선. 칸 높이를 꽉 채운다. */
@Composable
private fun StatDivider() {
    VerticalDivider(
        modifier = Modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * 회차별 수익 그래프 + 선택 회차 상세 + 전체 아이템 집계.
 *
 * 그래프의 막대를 누르면 그 회차의 획득 아이템·시세를 볼 수 있고,
 * 잘못 집계된 회차는 상세에서 삭제할 수 있다 (세션 총합도 같이 재계산).
 */
@Composable
private fun RunHistorySection() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val svc = service
    val session by (svc?.sessionState ?: MutableStateFlow(SessionState()))
        .collectAsStateWithLifecycle()

    // 막대를 누르면 팝업. 선택한 회차가 삭제되면 자동으로 닫힌다.
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = session.runs.firstOrNull { it.id == selectedId }

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
                Text("회차별 수익", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "막대를 누르면 상세",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${session.runs.size}회차",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RunBarChart(
                runs = session.runs,
                selectedId = selected?.id,
                onSelect = { selectedId = it },
            )
        }
    }

    TotalItemsCard(merged = session.sessionItems)

    selected?.let { run ->
        RunDetailDialog(
            run = run,
            loadItems = { id -> svc?.loadRunItems(id) ?: emptyList() },
            onDelete = { id -> svc?.deleteRun(id) },
            onDismiss = { selectedId = null },
        )
    }
}

/** 수익 탭 하단에 붙는 시세 요약 한 줄. 자세한 설정은 설정 탭. */
@Composable
private fun PriceSummaryLine() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val priceState by (service?.priceState ?: MutableStateFlow(com.mttd.data.prices.PriceRepository.State()))
        .collectAsStateWithLifecycle()

    val loaded = !priceState.loading && priceState.itemsWithPrice > 1
    val body = when {
        priceState.loading -> "불러오는 중..."
        priceState.itemsWithPrice <= 1 -> "시세 없음 — 설정 탭에서 새로고침"
        else -> "${priceState.source.label} · ${priceState.seasonId ?: "-"} · " +
                "가격 ${priceState.itemsWithPrice}개"
    }
    // 시안은 갱신 시각을 본문에 붙이지 않고 오른쪽 끝으로 뺀다 — 본문이 길어져도 잘리는 건
    // 가운데뿐이고 "언제 받은 값인지"는 항상 보인다.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
            .border(1.dp, MttdColors.BlueBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "시세",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            body,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.2.sp,
            color = MttdColors.BlueText,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (loaded) {
            Text(
                formatUpdatedAgo(priceState.lastUpdatedMs),
                fontSize = 11.sp,
                color = MttdColors.BlueText,
            )
        }
    }
}

@Composable
private fun ShizukuStatusCard(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    // 다 준비된 상태에선 매번 4줄 상세를 보여줄 필요가 없어 한 줄 요약으로 접어둔다.
    // 뭔가 문제가 있을 땐(하나라도 false) 바로 뭘 고쳐야 하는지 보여야 하니 항상 펼쳐둔다.
    val collapsible = state.ready
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (collapsible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "✅ Shizuku 준비 완료",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "접기" else "펼치기",
                    )
                }
            } else {
                Text("Shizuku 상태", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (expanded || !collapsible) {
                StatusRow("설치됨", state.installed)
                StatusRow("Shizuku 실행", state.binderAlive)
                StatusRow("권한 허용", state.permission)
                StatusRow("로그 연결", state.userServiceBound)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f).height(56.dp),
                        onClick = onRequestPermission,
                        enabled = state.installed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            when {
                                !state.installed -> "Shizuku 미설치"
                                !state.binderAlive -> "Shizuku 실행 후 재확인"
                                !state.permission -> "권한 요청"
                                !state.userServiceBound -> "로그 연결 다시 시도"
                                else -> "이미 준비됨"
                            }
                        )
                    }
                    OutlinedButton(
                        modifier = Modifier.height(56.dp),
                        onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    ) { Text("APK 다운로드") }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    val accent = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (ok) "✓" else "×", color = accent, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            if (ok) "완료" else "미완료",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
}

/**
 * 로그 파일 프로브 / 폴링 raw 상태 — 일반 유저에겐 의미 없는 개발자 진단 정보라
 * 기본 접힘 상태로 숨겨두고, 필요할 때만 펼쳐서 본다.
 */
@Composable
private fun AdvancedSection(userService: () -> IUserService?) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 설정 탭의 다른 항목이 전부 카드라 헤더도 카드로 감싼다 — 맨 줄로 두면 이 항목만
        // 카드 사이에 떠 있는 것처럼 보인다.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("문제 해결 도구", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "게임 로그 연결 상태를 확인합니다.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "접기" else "펼치기",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            ProbeCard(userService = userService)
            MapPresenceCard()
            LogTailCard(userService = userService)
        }
    }
}

@Composable
private fun ProbeCard(userService: () -> IUserService?) {
    var installedGames by remember { mutableStateOf("(조회 중...)") }
    var logSize by remember { mutableStateOf("(조회 중...)") }
    var logPath by remember { mutableStateOf("(대기)") }

    LaunchedEffect(Unit) {
        val svc = userService() ?: run {
            logSize = "UserService 없음"
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val packages = try {
                svc.listInstalledGamePackages().lines().filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
            installedGames = if (packages.isEmpty())
                "감지된 게임 없음"
            else packages.joinToString(", ")

            val path = packages.firstOrNull()?.let {
                "/sdcard/Android/data/$it/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"
            }
            if (path == null) {
                logPath = "(게임 미감지)"
                logSize = "-"
                return@withContext
            }
            logPath = path
            val size = try { svc.getFileSize(path) } catch (e: Exception) { -1L }
            logSize = if (size < 0) "파일 없음 또는 접근 불가"
                      else "%,d bytes (%.2f MB)".format(size, size / 1024.0 / 1024.0)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("로그 파일 프로브", fontWeight = FontWeight.SemiBold)
            LabelValue("감지된 게임", installedGames)
            LabelValue("로그 경로", logPath)
            LabelValue("파일 크기", logSize)
        }
    }
}

/**
 * 맵 안/밖 판정이 바뀐 이력.
 *
 * 원본 로그 10줄로는 이걸 못 잡는다 — 게임을 내리고 앱을 열기까지 수천 줄이 더 쌓여서
 * 정작 필요한 줄이 밀려난다. 전환 순간만 원인과 함께 붙잡아 두면 게임을 내린 뒤에도
 * "어느 신호가 시계를 멈췄는지" 를 그대로 확인할 수 있다.
 */
@Composable
private fun MapPresenceCard() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val events by (service?.mapPresenceLog
        ?: MutableStateFlow(emptyList<com.mttd.domain.SessionAggregator.MapPresenceEvent>()))
        .collectAsStateWithLifecycle()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("맵 전환 기록", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "맵 안/밖 판정이 바뀐 순간과 그 원인입니다. 최신이 위입니다.\n" +
                        "시각은 게임 로그에 찍힌 시각입니다 — 폴러는 최대 5초에 한 번 읽으므로 " +
                        "앱이 그 줄을 본 시각과는 다릅니다.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MttdColors.DividerSoft)
            if (events.isEmpty()) {
                Text("(아직 없음)", fontSize = 13.sp, color = MttdColors.TextMuted)
            } else {
                val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA) }
                for (e in events.asReversed()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            // 게임 로그 시각이 있으면 그걸 쓴다. 없으면 앱이 처리한 시각으로 대체하고
                            // 물음표를 붙여 어느 쪽인지 알 수 있게 한다.
                            e.logTime ?: (fmt.format(java.util.Date(e.atMs)) + "?"),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (e.inMap) "맵 안" else "맵 밖",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (e.inMap) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            e.reason,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogTailCard(userService: () -> IUserService?) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()

    // 감지된 로그 경로 (게임 자동 감지)
    var detectedPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val svc = userService() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val pkg = try {
                svc.listInstalledGamePackages().lines().firstOrNull { it.isNotBlank() }
            } catch (_: Exception) { null }
            detectedPath = pkg?.let {
                "/sdcard/Android/data/$it/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("로그 폴링 (M1)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

            val svc = service
            val status by (svc?.status ?: MutableStateFlow(LogPoller.PollingStatus()))
                .collectAsStateWithLifecycle()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val path = detectedPath ?: return@Button
                        TrackerForegroundService.start(context, path)
                    },
                    enabled = !status.active && detectedPath != null,
                ) { Text("시작") }
                OutlinedButton(
                    onClick = { TrackerForegroundService.stop(context) },
                    enabled = status.active,
                ) { Text("중지") }
            }

            LabelValue("폴링 활성", if (status.active) "예" else "아니오")
            LabelValue("현재 간격", "${status.intervalMs} ms")
            LabelValue("파일 크기", if (status.fileSize < 0) "-" else "%,d bytes".format(status.fileSize))
            LabelValue("현재 offset", "%,d bytes".format(status.offset))
            LabelValue(
                "총 read / 라인",
                "%,d bytes / %,d 라인".format(status.totalBytesRead, status.totalLinesEmitted)
            )
            status.lastError?.let { LabelValue("최근 오류", it) }

            // "N초 전" 이 흐르려면 주기적 재구성이 필요하다. 폴러 상태는 값이 안 바뀌면
            // StateFlow 가 새로 흘리지 않아서(deep idle 이면 30초에 한 번도 안 바뀐다)
            // 표시가 그대로 멈춰 버린다. 이 카드가 열려 있는 동안만 1 초 틱을 돈다.
            var tick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) { delay(1000); tick++ }
            }
            val lastGrowth = status.lastGrowthAtMs
            LabelValue(
                "마지막 로그 수신",
                remember(status, tick) {
                    when {
                        !status.active -> "폴링 중지됨"
                        lastGrowth == 0L -> "아직 한 줄도 못 받음"
                        else -> formatUpdatedAgo(lastGrowth)
                    }
                },
            )
            LabelValue(
                "게임 상태(추정)",
                remember(status, tick) {
                    when {
                        !status.active -> "판단 불가"
                        lastGrowth == 0L -> "로그 안 옴"
                        status.gameLikelyRunning() -> "실행 중"
                        else -> "종료 또는 백그라운드"
                    }
                },
            )

            HorizontalDivider()
            Text("최근 라인 (10개)", fontWeight = FontWeight.SemiBold)
            // 이 목록은 새 로그가 올 때마다 저절로 바뀐다(StateFlow 구독). 당겨올 게 없어서
            // 새로고침 버튼은 의미가 없다 — 안 바뀌면 새 줄이 안 오는 것이고, 그 이유는
            // 바로 위 두 줄로 판단한다.
            BulletLine(
                "게임을 백그라운드로 보내면 로그가 멈춥니다. " +
                    "이 화면을 보는 동안 안 바뀌는 것은 정상일 수 있습니다.",
            )
            val recentLines by (svc?.recentLines ?: MutableStateFlow(emptyList<String>()))
                .collectAsStateWithLifecycle()
            RecentLines(recentLines)
        }
    }
}

@Composable
private fun RecentLines(lines: List<String>) {
    if (lines.isEmpty()) {
        Text("(아직 없음)", style = MaterialTheme.typography.bodySmall)
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color(0xFF0F172A).copy(alpha = 0.85f),
                    RoundedCornerShape(8.dp),
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (line in lines) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE2E8F0),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OverlayCard() {
    val context = LocalContext.current
    var canDraw by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    val prefs = remember(context) { com.mttd.data.prefs.OverlayPrefs(context.applicationContext) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val timeTrackingMode by prefs.timeTrackingMode.collectAsStateWithLifecycle(
        initialValue = com.mttd.domain.models.TimeTrackingMode.MAP_ONLY,
    )
    val miniPanelMetrics by prefs.miniPanelMetrics.collectAsStateWithLifecycle(
        initialValue = com.mttd.ui.overlay.MiniPanelMetric.DEFAULT_IDS,
    )
    var showMinimumMetricDialog by remember { mutableStateOf(false) }
    val miniPanelControls by prefs.miniPanelControls.collectAsStateWithLifecycle(
        initialValue = com.mttd.ui.overlay.MiniPanelControl.DEFAULT_IDS,
    )
    val panelEnabled by prefs.gamePanelEnabled.collectAsStateWithLifecycle(initialValue = true)
    val miniPanelNetValue by prefs.miniPanelNetValue.collectAsStateWithLifecycle(initialValue = false)

    // 앱이 다시 포그라운드로 올 때 권한 상태 재확인
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val v = android.provider.Settings.canDrawOverlays(context)
            if (v != canDraw) canDraw = v
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("게임 화면 표시", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "게임을 하는 동안 수익 패널을 화면 위에 표시합니다.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusRow("게임 위에 표시 권한", canDraw)

            if (!canDraw) {
                Button(onClick = {
                    val i = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }) { Text("게임 위 표시 허용") }
            } else {
                // 시안은 켜짐/꺼짐을 색 전체(테두리·배경·문구·트랙)로 구분한다.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (panelEnabled) MaterialTheme.colorScheme.primaryContainer
                            else MttdColors.DangerBg,
                            RoundedCornerShape(13.dp),
                        )
                        .border(
                            1.dp,
                            if (panelEnabled) MttdColors.BlueStrong else MttdColors.DangerLine,
                            RoundedCornerShape(13.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("게임 위 패널", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (panelEnabled) "현재 켜져 있습니다" else "현재 꺼져 있습니다",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (panelEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    Switch(
                        checked = panelEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                com.mttd.service.TrackerForegroundService.showOverlay(context)
                            } else {
                                com.mttd.service.TrackerForegroundService.hideOverlay(context)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                            uncheckedTrackColor = MttdColors.ToggleOff,
                            uncheckedBorderColor = MttdColors.ToggleOff,
                        ),
                    )
                }
                // 시안은 한 문장을 불릿 두 줄로 나눠 각각 짧게 읽히게 한다.
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    BulletLine("미니패널을 탭하면 상세 패널이 열립니다.")
                    BulletLine("길게 누른 채 드래그하면 위치를 옮길 수 있습니다.")
                }
            }

        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("미니패널 표시 항목", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "게임 위 미니패널에서 볼 정보를 선택하세요.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // 아래 "미니패널 버튼" 은 전부 꺼도 되므로, 최소 1개 규칙이 수치 항목에만
                    // 걸린다는 걸 문구에서 분명히 한다.
                    "수치 항목은 최소 1개를 선택해야 합니다.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            // 시안은 2열 고정 그리드. 세로 스크롤 Column 안이라 LazyVerticalGrid 는 쓸 수 없어
            // FlowRow 의 maxItemsInEachRow 로 같은 배치를 만든다.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                for (metric in com.mttd.ui.overlay.MiniPanelMetric.entries) {
                    val selected = metric.id in miniPanelMetrics
                    MetricChip(
                        label = metric.settingsLabel,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onToggle = {
                            val updated = if (!selected) miniPanelMetrics + metric.id
                                          else miniPanelMetrics - metric.id
                            if (updated.isEmpty()) showMinimumMetricDialog = true
                            else scope.launch { prefs.setMiniPanelMetrics(updated) }
                        },
                    )
                }
            }

            HorizontalDivider(color = MttdColors.DividerSoft)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("미니패널 버튼", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "미니패널 오른쪽에 둘 버튼을 선택하세요. 끈 버튼의 자리는 수치 칸이 나눠 씁니다.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                for (control in com.mttd.ui.overlay.MiniPanelControl.entries) {
                    val selected = control.id in miniPanelControls
                    MetricChip(
                        label = control.settingsLabel,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onToggle = {
                            val updated = if (!selected) miniPanelControls + control.id
                                          else miniPanelControls - control.id
                            scope.launch { prefs.setMiniPanelControls(updated) }
                        },
                    )
                }
            }
            BulletLine("초기화와 종료는 미니패널에서 두 번 눌러야 실행됩니다. 버튼이 붙어 있어 실수로 눌리는 걸 막기 위한 것입니다.")
            BulletLine("버튼을 모두 꺼도 됩니다. 미니패널을 탭해 상세 패널을 열면 같은 버튼이 모두 있습니다.")

            HorizontalDivider(color = MttdColors.DividerSoft)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("수익 표시", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "총 수익 · 시간당 · 이번 맵에 적용됩니다.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NetValueSelector(
                showNet = miniPanelNetValue,
                onSelect = { v -> scope.launch { prefs.setMiniPanelNetValue(v) } },
            )
            BulletLine("미니패널 숫자 색으로 구분됩니다 — 세전은 초록, 세후는 파랑. 마이너스는 어느 쪽이든 빨강입니다.")
            if (showMinimumMetricDialog) {
                AlertDialog(
                    onDismissRequest = { showMinimumMetricDialog = false },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(25.dp),
                            )
                        }
                    },
                    title = {
                        Text(
                            "표시 항목을 남겨주세요",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    text = {
                        Text(
                            "미니패널에는 최소 1개의 항목을\n선택해야 합니다.",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { showMinimumMetricDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) { Text("확인") }
                    },
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("시간 측정 방식", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "수익과 시간당 수익을 계산할 시간을 선택합니다.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TimeTrackingModeSelector(
                current = timeTrackingMode,
                onSelect = { mode -> scope.launch { prefs.setTimeTrackingMode(mode) } },
            )
        }
    }
}


@Composable
private fun TimeTrackingModeSelector(
    current: com.mttd.domain.models.TimeTrackingMode,
    onSelect: (com.mttd.domain.models.TimeTrackingMode) -> Unit,
) {
    // 시안은 라디오 버튼만 나열하지 않고 선택지 전체를 카드로 만들어 탭 영역을 넓힌다.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in com.mttd.domain.models.TimeTrackingMode.entries) {
            val selected = current == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MttdColors.ChipOffBg,
                        RoundedCornerShape(12.dp),
                    )
                    .border(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(12.dp),
                    )
                    .selectable(selected = selected, onClick = { onSelect(mode) })
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RadioDot(selected = selected, modifier = Modifier.padding(top = 1.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        mode.label,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    Text(
                        mode.description,
                        fontSize = 11.5.sp,
                        lineHeight = 18.sp,
                        color = if (selected) MttdColors.TextMid
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 미니패널 수익을 세전(시장가) / 세후(실수령) 중 무엇으로 보여줄지.
 *
 * 시간 측정 방식과 같은 라디오 카드 형태를 쓴다 — 같은 설정 화면에서 같은 성격의 선택은
 * 같은 모양이어야 한다.
 */
@Composable
private fun NetValueSelector(showNet: Boolean, onSelect: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NetValueOption(
            label = "세전 (시장가)",
            help = "경매장에 올린 가격 그대로 계산합니다.",
            selected = !showNet,
            onSelect = { onSelect(false) },
        )
        NetValueOption(
            label = "세후 (실수령)",
            help = "경매장 세금 1/8을 뺀 실제로 손에 쥐는 금액입니다. 최초의 불꽃 결정과 소비 항목에는 세금이 붙지 않습니다.",
            selected = showNet,
            onSelect = { onSelect(true) },
        )
    }
}

@Composable
private fun NetValueOption(
    label: String,
    help: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MttdColors.ChipOffBg,
                RoundedCornerShape(12.dp),
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, onClick = onSelect)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioDot(selected = selected, modifier = Modifier.padding(top = 1.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            Text(
                help,
                fontSize = 11.5.sp,
                lineHeight = 18.sp,
                color = if (selected) MttdColors.TextMid else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 시안의 불릿 한 줄 — 작은 점 + 보조 문구. */
@Composable
private fun BulletLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(3.dp)
                .background(MttdColors.TextMuted, androidx.compose.foundation.shape.CircleShape),
        )
        Text(
            text,
            fontSize = 11.5.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 시안의 미니패널 항목 칩. Material [Checkbox] 대신 20dp 정사각 체크 박스를 직접 그린다 —
 * 기본 Checkbox 는 터치 타깃 패딩이 붙어 칩 안에서 정렬이 어긋난다.
 */
@Composable
private fun MetricChip(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MttdColors.ChipOffBg,
                RoundedCornerShape(12.dp),
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(6.dp),
                )
                .border(
                    1.5.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MttdColors.CheckboxOff,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MttdColors.TextMid,
        )
    }
}

/** 시안의 라디오 표시 — 바깥 원 + 채워진 안쪽 점. */
@Composable
private fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .border(
                1.5.dp,
                if (selected) MaterialTheme.colorScheme.primary else MttdColors.CheckboxOff,
                androidx.compose.foundation.shape.CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
}

/**
 * 캐릭터 장비/스킬/석판/천명/핵심 재능/프리즘 정보를 미니 토치DB로 내보내는 카드.
 *
 * [CharacterLoadoutTracker] 는 로그인 직후 한 번 오는 `GetPlayerData` 전체 동기화를 관측해야
 * 채워지므로, 앱을 켠 뒤로 게임에 재접속한 적이 없으면 비어 있을 수 있다 — 그 경우 안내만 하고
 * 버튼은 계속 눌러볼 수 있게 둔다(재시도 비용이 없으므로).
 *
 * 2026-08-14부터 [com.mttd.data.export.LogRelayClient] 로 원본 로그 슬라이스(`GetPlayerData`
 * 시작 지점부터 현재 파일 끝까지, [TrackerForegroundService.currentLoadoutExportBlock] 참조)를
 * 그대로 올리고, 서버가 돌려준 1회용 URL을 여는 방식이다 — 필드별 추출([Mli1Codec] 의
 * `logimport=` URL 방식)은 [CharacterLoadoutTracker] 클래스 doc의 이유로 폐기했다. 이 앱이
 * mini-tlidb.winterer.workers.dev 로 직접 통신하는 유일한 지점이라 README/INSTALL 의 네트워크
 * 호스트 목록에 등재돼 있어야 한다.
 */
@Composable
private fun LoadoutExportCard() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val loadout by (service?.loadoutState ?: MutableStateFlow(null))
        .collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val relayClient = remember { com.mttd.data.export.LogRelayClient() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("캐릭터 장비 내보내기", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "캐릭터 빌드 정보를 미니 토치DB로 보냅니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (loadout == null) {
                Text(
                    "① 로그 오픈 → ② 게임에서 로그아웃 후 재접속",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                LoadoutPreview(loadout!!)
            }

            var sending by remember { mutableStateOf(false) }
            var errorText by remember { mutableStateOf<String?>(null) }
            var copied by remember { mutableStateOf(false) }

            fun openUrl(url: String) {
                // FLAG_ACTIVITY_NEW_TASK 필요한 이유는 예전 saltedUrl() 시절과 동일 — 이 파일의
                // 다른 브라우저 오픈 인텐트(업데이트 다운로드 링크, GitHub 링크, 오버레이 권한
                // 설정)와 통일. 지금은 서버가 매번 새 토큰을 발급해 URL이 항상 달라지므로 클라이언트
                // 쪽에서 nonce 를 직접 만들 필요는 없어졌다.
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }

            /** 원본 슬라이스를 릴레이 서버에 올리고, 성공하면 [onSuccess] 에 발급받은 URL을 넘긴다. */
            fun relayThen(onSuccess: (String) -> Unit) {
                sending = true
                errorText = null
                scope.launch {
                    try {
                        val rawSlice = service?.currentLoadoutExportBlock()
                        if (rawSlice == null) {
                            errorText = "아직 원본 로그를 못 잡았습니다. 재접속 후 다시 시도해주세요."
                            return@launch
                        }
                        val result = relayClient.relay(rawSlice)
                        onSuccess(result.url)
                    } catch (e: Exception) {
                        errorText = "전송 실패: ${e.message ?: "네트워크 오류"} — 다시 시도해주세요."
                    } finally {
                        sending = false
                    }
                }
            }

            Button(
                enabled = loadout != null && !sending,
                onClick = { relayThen { url -> openUrl(url) } },
            ) { Text(if (sending) "전송 중..." else "미니 토치DB로 내보내기") }

            TextButton(
                enabled = loadout != null && !sending,
                onClick = {
                    relayThen { url ->
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("mini-tlidb logdump URL", url))
                        copied = true
                    }
                },
            ) { Text("(안 열리면) 주소만 복사해서 직접 붙여넣기") }

            if (copied) {
                Text(
                    "URL이 클립보드에 복사됐습니다. 브라우저에서 새 탭을 열고 주소창에 붙여넣어 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            errorText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 실제로 뭐가 전송될지 미리 볼 수 있게 요약. 사용자가 "진짜 지금 장비가 맞는지" 브라우저를
 * 열기 전에 앱 안에서 확인할 수 있는 유일한 지점이다. 단, 실제로 서버에 올라가는 건 이 필드들이
 * 아니라 원본 로그 슬라이스([TrackerForegroundService.currentLoadoutExportBlock], `LogRelayClient`
 * 참조) 이라 — 파싱은 수신측이 따로 하므로, 이 미리보기는 "대략 이 정도 데이터가 담겨있다"는
 * 참고용이지 전송될 JSON의 정확한 내용은 아니다. 전송 성공/실패는 [LoadoutExportCard] 의
 * `errorText` 로 확인한다.
 */
@Composable
private fun LoadoutPreview(loadout: com.mttd.data.export.CharacterLoadout) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            loadout.char ?: "(이름 없음)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "장비 ${loadout.gear?.size ?: 0}개 · 스킬 ${loadout.skills?.size ?: 0}개 · " +
                "석판 노드 ${loadout.slate?.size ?: 0}개 · 천명 ${loadout.dst?.size ?: 0}개 · " +
                "핵심 재능 ${loadout.genius?.core?.size ?: 0}개 · " +
                "프리즘 ${if (loadout.prism != null) 1 else 0}개 · " +
                "히어로 추억 ${loadout.mems?.size ?: 0}개",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PriceCard() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val svc = service
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val priceState by (svc?.priceState ?: MutableStateFlow(com.mttd.data.prices.PriceRepository.State()))
        .collectAsStateWithLifecycle()
    val prefs = remember(context) { com.mttd.data.prefs.OverlayPrefs(context.applicationContext) }

    // 자동 fetch 는 TrackerForegroundService 가 담당한다 (앱 UI 를 안 열어도 시세가 필요하므로).
    // 여기서 또 부르면 시즌 스윕이 중복 실행되므로 수동 새로고침 버튼만 남긴다.

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("시세", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "가격 출처",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PriceSourceSelector(
                current = priceState.source,
                enabled = svc != null && !priceState.loading,
                onSelect = { src ->
                    val s = svc?.priceRepository() ?: return@PriceSourceSelector
                    scope.launch {
                        s.switchSource(src)
                        prefs.setPriceSourceId(src.id)
                    }
                },
            )

            Button(
                onClick = {
                    val s = svc?.priceRepository() ?: return@Button
                    scope.launch { s.refreshLatest(forceRefresh = true) }
                },
                enabled = svc != null && !priceState.loading,
            ) { Text(if (priceState.loading) "불러오는 중..." else "새로고침") }

            LabelValue("현재 시즌", priceState.seasonId ?: "-")
            LabelValue("모드", priceState.mode.ifBlank { "-" })
            LabelValue("아이템 수", "${priceState.totalItems} (가격 있음 ${priceState.itemsWithPrice})")
            LabelValue(
                "마지막 갱신",
                if (priceState.lastUpdatedMs == 0L) "-"
                else formatUpdatedAgo(priceState.lastUpdatedMs),
            )
            priceState.lastError?.let { LabelValue("오류", it) }

            HorizontalDivider()
            ObservedPriceSection()

            if (priceState.priceById.isNotEmpty()) {
                HorizontalDivider()
                Text("샘플 가격 확인 (감지된 픽업 기준)", fontWeight = FontWeight.SemiBold)
                SamplePriceLookup(priceState)
            }
        }
    }
}

/**
 * 게임 내 경매장에서 직접 조회한 시세 목록.
 *
 * 조회 즉시 스냅샷 가격을 덮어쓰므로, 어떤 아이템이 덮였는지 여기서 확인할 수 있다.
 */
@Composable
private fun ObservedPriceSection() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val svc = service
    val observed by (svc?.observedPriceState
        ?: MutableStateFlow(emptyMap<String, com.mttd.data.prices.ObservedPriceStore.Observed>()))
        .collectAsStateWithLifecycle()

    // 서비스가 이미 들고 있는 것을 재사용. 여기서 새로 만들면 311 KB JSON 을 또 파싱한다.
    val itemInfo = svc?.itemInfoLookup()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("경매장 조회 반영 (${observed.size})", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        if (observed.isNotEmpty()) {
            OutlinedButton(onClick = { svc?.clearObservedPrices() }) { Text("비우기") }
        }
    }
    if (observed.isEmpty()) {
        Text(
            "게임 경매장에서 아이템 시세를 조회하면 그 값이 스냅샷보다 우선 적용됩니다. " +
                "가루(가루 거래)로 매겨진 호가는 제외합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (o in observed.values.sortedByDescending { it.observedAtMs }.take(8)) {
            val name = itemInfo?.lookup(o.itemId)?.name ?: o.itemId
            Text(
                "▸ $name · 평균 ${"%.4f".format(o.avgPrice)} " +
                    "(호가 ${o.samples}건, ${"%.4f".format(o.lowest)}~${"%.4f".format(o.highest)}) · " +
                    formatUpdatedAgo(o.observedAtMs),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 시세 출처 선택. 전환하면 이전 소스의 가격 맵을 버리고 즉시 재조회한다.
 *
 * 두 소스 모두 최초의 불꽃 결정 = 1 기준이라 스케일은 같고, 표본/갱신 주기만 다르다.
 */
@Composable
private fun PriceSourceSelector(
    current: com.mttd.data.prices.PriceSource,
    enabled: Boolean,
    onSelect: (com.mttd.data.prices.PriceSource) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (src in com.mttd.data.prices.PriceSource.entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = current == src,
                        enabled = enabled,
                        onClick = { onSelect(src) },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = current == src,
                    onClick = { onSelect(src) },
                    enabled = enabled,
                )
                Column {
                    Text(src.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        src.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SamplePriceLookup(priceState: com.mttd.data.prices.PriceRepository.State) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val session by (service?.sessionState ?: MutableStateFlow(SessionState()))
        .collectAsStateWithLifecycle()

    val pickups = session.recentPickups.take(5)
    if (pickups.isEmpty()) {
        Text("(세션에서 픽업 발생 시 여기 매칭 결과 표시)", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (p in pickups) {
            val id = p.itemId
            val price = id?.let { priceState.priceById[it] } ?: 0f
            val priceLabel = if (price <= 0f) "미상"
                             else if (price >= 1) "%,.0f".format(price)
                             else "%.4f".format(price)
            Text(
                "▸ ${p.itemName ?: "Unknown"} · $priceLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun formatUpdatedAgo(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    val s = diff / 1000
    return when {
        s < 60 -> "${s}초 전"
        s < 3600 -> "${s / 60}분 전"
        else -> "${s / 3600}시간 ${(s % 3600) / 60}분 전"
    }
}

/**
 * 버전 표시 옆의 업데이트 재확인 버튼. 서비스가 시작 시 1 회 자동으로 확인하긴 하지만,
 * 앱을 오래 켜둔 채로 새 버전이 올라오면 재시작 전엔 알 방법이 없었다. 새 버전이 있으면
 * 이미 떠 있는 [UpdateBanner] 가 같은 상태를 구독하고 있어 알아서 나타난다.
 *
 * 아이콘 하나만 있을 땐 눈에 잘 안 띈다는 피드백을 받아 텍스트 라벨이 있는 버튼으로 바꿨다.
 */
@Composable
private fun UpdateCheckButton(onUpdateFound: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var resultLabel by remember { mutableStateOf<String?>(null) }

    OutlinedButton(
        onClick = {
            val svc = service ?: return@OutlinedButton
            checking = true
            resultLabel = null
            scope.launch {
                resultLabel = when (val result = svc.checkForUpdate()) {
                    is com.mttd.data.update.UpdateChecker.CheckResult.UpdateAvailable -> {
                        // 버튼 라벨만 바뀌면 새 버전이 있다는 걸 알아채기 어렵다 —
                        // 아래 배너를 바로 펼쳐서 무엇이 바뀌는지 그 자리에서 보이게 한다.
                        onUpdateFound()
                        "새 버전 ${result.update.versionName}"
                    }
                    com.mttd.data.update.UpdateChecker.CheckResult.UpToDate -> "현재 최신 버전"
                    is com.mttd.data.update.UpdateChecker.CheckResult.Failed -> result.message
                }
                checking = false
            }
        },
        enabled = service != null && !checking,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (checking) "확인 중..." else resultLabel ?: "업데이트 확인",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * 앱 종료. 최근 앱 목록에서 카드를 스와이프해도 종료되도록 서비스는
 * `stopWithTask=true` 로 설정했지만, 그 전에 명시적으로 끌 수 있는 버튼도 필요해서 추가.
 * 로그 추적·오버레이를 모두 정리하고 액티비티도 닫는다.
 */
@Composable
private fun ExitCard() {
    val context = LocalContext.current
    var confirmExit by remember { mutableStateOf(false) }

    // 기본 Card 는 채워진 회색이라 다른 설정 카드(흰 면 + 얇은 테두리)와 톤이 어긋났다.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("앱 종료", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "로그 추적과 오버레이를 모두 중지하고 앱을 종료합니다.",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (confirmExit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirmExit = false }) { Text("취소") }
                    TextButton(onClick = {
                        com.mttd.service.TrackerForegroundService.stop(context)
                        (context as? android.app.Activity)?.finishAndRemoveTask()
                    }) {
                        Text("종료 확인", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { confirmExit = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(11.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MttdColors.DangerLine),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("앱 종료", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    if (ms <= 0) return "0초"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%d시간 %02d분 %02d초".format(h, m, s)
        m > 0 -> "%d분 %02d초".format(m, s)
        else -> "%d초".format(s)
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
