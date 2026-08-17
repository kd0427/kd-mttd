package com.mttd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.mttd.data.prefs.OverlayPrefs
import com.mttd.service.TrackerForegroundService
import com.mttd.ui.onboarding.OnboardingScreen
import com.mttd.ui.onboarding.SetupWizardScreen
import com.mttd.ui.theme.mTTDTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * 알림 권한 요청기. 거부돼도 추적은 그대로 동작한다 — 포그라운드 서비스 알림만 안 보이고,
     * 그러면 알림을 눌러 앱으로 돌아오는 길이 사라진다.
     *
     * Android 13+ 는 `POST_NOTIFICATIONS` 를 매니페스트에 선언만 해서는 알림이 뜨지 않는데,
     * 지금까지 요청하는 코드가 어디에도 없었다.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 앱 팔레트가 밝은 배경으로 고정돼 있으므로(ui/theme/Theme.kt) 시스템 바에도 "배경이 밝다"고
        // 알려 아이콘을 어둡게 그리게 한다. 이 플래그는 아이콘 색이 아니라 **배경의 밝기**를 뜻한다 —
        // false 로 두면 enableEdgeToEdge() 로 바 뒤까지 그려진 흰 배경 위에 흰 아이콘이 얹혀
        // 시계·배터리가 보이지 않는다.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val access = TrackerApplication.instance.accessManager

        setContent {
            mTTDTheme {
                val prefs = remember { OverlayPrefs(applicationContext) }
                val scope = rememberCoroutineScope()
                // null = DataStore 첫 읽기 전. false 로 기본값을 줘버리면 복귀 유저에게
                // 마법사가 1프레임 플래시되므로, 값이 오기 전엔 빈 화면(테마 배경만)으로 대기.
                // .collect (continuous) 를 쓰는 이유: 이 Activity/Compose 트리가 앱 생애주기
                // 내내 유지되는 유일한 화면이라, 마법사 완료/재오픈이 재구동 없이 반영돼야 함.
                val wizardCompleted by produceState<Boolean?>(initialValue = null, prefs) {
                    prefs.wizardCompleted.collect { value = it }
                }

                Surface(color = MaterialTheme.colorScheme.background) {
                    when (val done = wizardCompleted) {
                        null -> Unit
                        else -> if (done) {
                            OnboardingScreen(
                                manager = access,
                                userService = { access.service },
                                onReopenWizard = { scope.launch { prefs.setWizardCompleted(false) } },
                            )
                        } else {
                            SetupWizardScreen(
                                manager = access,
                                onFinished = { scope.launch { prefs.setWizardCompleted(true) } },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 저장된 연결이 있으면 조용히 재시도 — 페어링 화면은 띄우지 않는다.
        // 최초 페어링은 설정 마법사/설정 탭의 카드에서만 사용자가 직접 시작한다.
        TrackerApplication.instance.accessManager.retryConnect()
        // 로그 연결이 준비되면 폴러 자동 시작 (이미 실행 중이면 no-op)
        autoStartTracker()
    }

    /**
     * 서비스를 띄우기만 하고, 연결 대기 · 게임 패키지 탐색 · 폴러 시작은 서비스에 맡긴다.
     *
     * 예전에는 여기서 준비 상태를 보고 조기 종료했는데, 연결이 비동기라 **첫 onResume 에서는
     * 거의 항상 not-ready** 였다. 그래서 앱을 한 번 나갔다 들어와야(두 번째 onResume)
     * 트래킹이 시작됐다.
     */
    private fun autoStartTracker() {
        val app = TrackerApplication.instance
        if (app.trackerService.value?.status?.value?.active == true) return
        TrackerForegroundService.startSelfManaged(this)
    }
}
