package com.mttd.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mttd.data.adb.DirectAdbManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first

private enum class WizardStep(val order: Int) {
    PAIRING(0),
    OVERLAY(1),
    DONE(2),
}

private fun WizardStep.isSatisfied(connected: Boolean, canDraw: Boolean): Boolean = when (this) {
    WizardStep.PAIRING -> connected
    WizardStep.OVERLAY -> canDraw
    WizardStep.DONE -> true
}

/**
 * 최초 설정을 3단계로 안내하는 마법사.
 *
 * 안드로이드 정책상 무선 디버깅 페어링 화면은 자동화할 수 없어 완전 원클릭은 불가능하지만,
 * 그 외에는 각 단계 완료를 [DirectAdbManager.ready]/[Settings.canDrawOverlays] 로 자동 감지해서
 * 다음 단계로 넘어간다. 이미 일부/전부 준비된 상태로 들어오면(재설치 등) 충족 안 된 첫
 * 단계부터 시작 — 1번부터 스치듯 지나가지 않는다.
 */
@Composable
fun SetupWizardScreen(
    manager: DirectAdbManager,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val connected by manager.ready.collectAsStateWithLifecycle()

    var canDraw by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // 오버레이 권한은 시스템 설정 화면에서만 바뀌므로, 돌아왔을 때 한 번 확인하면 충분하다.
    // 예전엔 0.5 초마다 폴링해서 화면이 꺼져 있어도 계속 IPC 를 날렸다.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            canDraw = Settings.canDrawOverlays(context)
        }
    }

    var step by rememberSaveable {
        mutableStateOf(WizardStep.entries.firstOrNull { !it.isSatisfied(connected, canDraw) } ?: WizardStep.DONE)
    }
    // 사용자가 "이전"/"다음"을 한 번이라도 직접 누르면 true — 그 뒤로는 이미 완료된 단계에
    // 있어도 자동으로 건너뛰지 않고 사용자가 머문 자리를 존중한다(뒤로가서 확인하는 중일 수 있음).
    var manualNav by rememberSaveable { mutableStateOf(false) }

    // LaunchedEffect(step) 안에서 connected 를 그냥 읽으면 최초 캡처값에 고정된다 —
    // rememberUpdatedState 로 감싸야 snapshotFlow 가 최신 값 변화를 계속 관찰한다.
    val latestConnected = rememberUpdatedState(connected)

    // 현재 보고 있는 단계에 한해서만 자동 진행 (edge-triggered).
    LaunchedEffect(step, manualNav) {
        if (step == WizardStep.DONE) return@LaunchedEffect
        if (step.isSatisfied(latestConnected.value, canDraw)) {
            // 이미 충족된 채로 이 단계에 있음. manualNav 전이면(=최초 진입 계산이 근소한
            // 레이스로 이미 끝난 단계에 멈춘 경우) 계속 건너뛴다. manualNav 이후엔 사용자가
            // 일부러 뒤로 가서 보는 중일 수 있으니 가만히 둔다.
            if (!manualNav) step = WizardStep.entries[step.order + 1]
            return@LaunchedEffect
        }
        snapshotFlow { step.isSatisfied(latestConnected.value, canDraw) }.first { it }
        step = WizardStep.entries[step.order + 1]
    }

    Scaffold { padding ->
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
                .verticalScroll(rememberScrollState())
                .padding(safePadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (step == WizardStep.DONE) "설정 완료" else "${step.order + 1} / ${WizardStep.entries.size - 1} 단계",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (step != WizardStep.DONE) {
                    TextButton(onClick = onFinished) { Text("건너뛰기") }
                }
            }

            when (step) {
                WizardStep.PAIRING -> PairingStep(manager = manager)
                WizardStep.OVERLAY -> OverlayStep(canDraw = canDraw)
                WizardStep.DONE -> DoneStep()
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step != WizardStep.PAIRING && step != WizardStep.DONE) {
                    OutlinedButton(onClick = {
                        manualNav = true
                        step = WizardStep.entries[step.order - 1]
                    }) { Text("이전") }
                }
                if (step == WizardStep.DONE) {
                    Button(onClick = onFinished) { Text("시작하기") }
                } else {
                    val isLastBeforeDone = step.order == WizardStep.entries.size - 2
                    Button(onClick = {
                        manualNav = true
                        step = if (isLastBeforeDone) WizardStep.DONE else WizardStep.entries[step.order + 1]
                    }) { Text("다음") }
                }
            }
        }
    }
}

@Composable
private fun WizardCard(title: String, body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // 기본 Card 는 채워진 회색이라, 흰 면 + 얇은 테두리를 쓰는 나머지 화면과 톤이 어긋난다.
    // 여기만 고치면 5개 단계 카드가 전부 따라온다.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            body()
        }
    }
}

@Composable
private fun PairingStep(manager: DirectAdbManager) {
    WizardCard(title = "1. 무선 디버깅 연결") {
        Text(
            "안드로이드 11부터는 일반 앱이 다른 앱의 파일을 못 읽습니다. mTTD 는 기기의 " +
                "무선 디버깅에 자기 자신으로 붙어서 게임 로그만 읽습니다 (root·다른 앱 불필요). " +
                "아래 카드의 순서대로 한 번만 페어링하면 됩니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    // 카드 안에 카드를 넣지 않는다 — 마법사 Column 의 spacedBy 로 나란히 놓는다.
    DirectAdbStatusCard(manager)
}

@Composable
private fun OverlayStep(canDraw: Boolean) {
    val context = LocalContext.current
    WizardCard(title = "2. 오버레이 권한 허용") {
        Text(
            "게임 화면 위에 수익 HUD를 띄우려면 \"다른 앱 위에 표시\" 권한이 필요합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (canDraw) {
            Text("✅ 권한 확인됨", color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = {
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }) { Text("권한 설정 열기") }
        }
    }
}

@Composable
private fun DoneStep() {
    WizardCard(title = "🎉 설정 완료") {
        Text(
            "이제 게임을 실행하고, 인벤토리에서 정렬 버튼을 한 번 눌러주세요 " +
                "(가방 상태를 처음부터 알아야 수익 집계를 시작할 수 있어요). " +
                "화면 구석에 원형 배지가 뜨면 준비된 겁니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
