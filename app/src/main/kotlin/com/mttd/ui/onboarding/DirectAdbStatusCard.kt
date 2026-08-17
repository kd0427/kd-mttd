package com.mttd.ui.onboarding

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mttd.data.adb.DirectAdbManager

/**
 * [OnboardingScreen] 설정 탭과 설정 마법사가 함께 쓰는 무선 adb 연결 상태 카드.
 *
 * IP/포트를 사람이 입력하지 않는다 — mDNS로 자동 탐지([com.mttd.data.adb.AdbMdns]) 하고,
 * 페어링 코드만 알림(RemoteInput)으로 받는다 ([com.mttd.service.DirectAdbPairingService]).
 * 설정 앱의 페어링 화면을 벗어나면 그 세션이 끊기기 때문에, 화면 전환 자체가 없어야 한다.
 */
@Composable
fun DirectAdbStatusCard(manager: DirectAdbManager) {
    val connected by manager.ready.collectAsStateWithLifecycle()
    // adb 셸 폴백으로 읽는 중인지, 데몬 Binder 로 읽는 중인지 — 전자는 WiFi 가 끊기면 같이
    // 끊긴다. "WiFi 꺼도 된다" 를 이 값이 참일 때만 말한다.
    val daemonReady by manager.daemonReady.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val lastError by manager.lastError.collectAsStateWithLifecycle()
    // false로 고정 — !connected 를 초기값으로 넣으면 "연결 안 됨" 상태로 펼쳐진 채 시작했다가
    // 백그라운드 재연결이 성공해도 expanded 가 그대로 남아있어("✅ 연결됨" 헤더 아래에 낡은
    // "페어링 서비스 찾는 중..." 문구가 계속 보이는) 혼란을 준다. connected 가 바뀔 때마다
    // 아래 if(expanded || !connected) 가 항상 최신 값을 보게 두는 편이 맞다.
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 개발자 옵션 두 스위치의 실제 상태. 추측해서 안내하지 않고 값을 읽어서 짚어준다.
    // 화면에 들어올 때만 읽는다 — 사용자가 설정 앱을 다녀오면 STARTED 가 다시 오므로 그때
    // 갱신되고, 안 보는 동안 폴링하지 않는다(SetupWizardScreen 의 오버레이 권한과 같은 방식).
    val lifecycleOwner = LocalLifecycleOwner.current
    var wirelessDebuggingOn by remember { mutableStateOf(true) }
    var usbDebuggingOn by remember { mutableStateOf(true) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // 못 읽으면 켜진 것으로 본다 — 확실하지 않은데 경고를 띄우는 쪽이 더 나쁘다.
            fun read(key: String) = try {
                Settings.Global.getInt(context.contentResolver, key, 1) == 1
            } catch (_: Throwable) { true }
            wirelessDebuggingOn = read("adb_wifi_enabled")
            usbDebuggingOn = read("adb_enabled")
        }
    }

    // 기본 Card 는 채워진 회색이라 나머지 화면(흰 면 + 얇은 테두리)과 톤이 어긋난다 —
    // SetupWizardScreen.WizardCard 와 같은 규칙을 쓴다.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (connected) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (daemonReady) "✅ 연결됨 — 이제 WiFi 를 꺼도 됩니다" else "✅ 연결됨 — 준비 중이니 WiFi 를 유지해주세요",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "접기" else "펼치기",
                    )
                }
            } else {
                Text("❌ 무선 adb 연결 안 됨", fontWeight = FontWeight.SemiBold)
            }

            if (expanded || !connected) {
                // 연결이 끊긴 상태에서만 띄운다. 붙고 나면 무선 디버깅을 꺼도 데몬은 계속
                // 사는 게 이 설계의 목적이라, 그때 "켜세요" 라고 하면 멀쩡한 상태에 없는 문제를
                // 만든다 (헤더의 "이제 WiFi 를 꺼도 됩니다" 와 정면으로 어긋난다).
                if (!connected && !wirelessDebuggingOn) {
                    Text(
                        "무선 디버깅이 꺼져 있습니다 — 개발자 옵션에서 켜주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!usbDebuggingOn) {
                    // adb shell 로 띄운 데몬은 adbd 의 cgroup 에 들어가고, init 은 서비스를
                    // cgroup 단위로 죽인다. USB 디버깅이 꺼져 있으면 WiFi 를 끄는 순간
                    // 무선 디버깅만 남았던 adbd 가 내려가면서 데몬도 같이 죽는다.
                    // (CUSTOMIZATION.md 의 "USB 디버깅이 왜 필요한가" 참고)
                    Text(
                        "USB 디버깅이 꺼져 있습니다 — 이대로 WiFi 를 끄면 연결이 끊깁니다. 개발자 옵션에서 켜주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (connected) {
                    Text(
                        "WiFi 를 꺼도 LTE 로 계속 집계됩니다.\n\n" +
                            "재부팅하면 무선 디버깅이 꺼집니다. 다시 켜고 WiFi 에 연결한 채 이 화면을 열면 " +
                            "자동으로 붙습니다 — 6자리 코드는 다시 입력하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("준비", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "개발자 옵션에서 USB 디버깅과 무선 디버깅을 둘 다 켜주세요.\n" +
                            "USB 디버깅을 안 켜면 WiFi 를 끌 때 연결이 끊깁니다. 케이블은 안 꽂아도 됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("연결 (처음 한 번만)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "1. 아래 \"페어링 시작\" 을 누르세요. 앱을 나가도 계속 찾습니다.\n" +
                            "2. 개발자 옵션 → 무선 디버깅 → \"페어링 코드로 기기 페어링\" 을 여세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "⚠️ 이 화면에서 나가면 처음부터 다시 해야 합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "3. 그 화면을 둔 채 알림창을 내려, 뜬 알림에 6자리 코드를 입력하세요.\n" +
                            "   시간 제한은 없으니 천천히 하셔도 됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val statusText = when (status) {
                    DirectAdbManager.Status.IDLE -> null
                    DirectAdbManager.Status.SEARCHING -> "페어링 서비스 찾는 중..."
                    DirectAdbManager.Status.WAITING_FOR_CODE -> "알림에서 코드를 입력해주세요"
                    DirectAdbManager.Status.PAIRING -> "페어링 중..."
                    DirectAdbManager.Status.CONNECTING -> "연결 시도 중..."
                    DirectAdbManager.Status.CONNECTED -> "연결됨"
                    DirectAdbManager.Status.FAILED -> "실패"
                }
                val busy = status in setOf(
                    DirectAdbManager.Status.SEARCHING,
                    DirectAdbManager.Status.PAIRING,
                    DirectAdbManager.Status.CONNECTING,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) manager.startPairing()
                        },
                        enabled = !busy,
                    ) { Text("페어링 시작") }
                    if (busy) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    }
                }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("개발자 옵션 열기") }
                statusText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                lastError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
