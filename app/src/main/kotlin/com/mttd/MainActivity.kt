package com.mttd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mttd.data.prefs.OverlayPrefs
import com.mttd.service.TrackerForegroundService
import com.mttd.ui.onboarding.OnboardingScreen
import com.mttd.ui.onboarding.SetupWizardScreen
import com.mttd.ui.theme.mTTDTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 앱 팔레트가 밝은 배경으로 고정돼 있으므로(ui/theme/Theme.kt) 시스템 바에도 "배경이 밝다"고
        // 알려 아이콘을 어둡게 그리게 한다. 이 플래그는 아이콘 색이 아니라 **배경의 밝기**를 뜻한다 —
        // false 로 두면 enableEdgeToEdge() 로 바 뒤까지 그려진 흰 배경 위에 흰 아이콘이 얹혀
        // 시계·배터리가 보이지 않는다.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val shizuku = TrackerApplication.instance.shizukuManager

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
                val state by shizuku.state.collectAsStateWithLifecycle()

                Surface(color = MaterialTheme.colorScheme.background) {
                    when (val done = wizardCompleted) {
                        null -> Unit
                        else -> if (done) {
                            OnboardingScreen(
                                state = state,
                                onRequestPermission = { shizuku.requestPermissionOrBind() },
                                userService = { shizuku.service },
                                onReopenWizard = { scope.launch { prefs.setWizardCompleted(false) } },
                            )
                        } else {
                            SetupWizardScreen(
                                state = state,
                                onRequestPermission = { shizuku.requestPermissionOrBind() },
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
        val shizuku = TrackerApplication.instance.shizukuManager
        // 다른 앱에서 돌아왔을 때 Shizuku 상태 재조회 (권한이 방금 grant 됐을 수 있음).
        shizuku.requestPermissionOrBind()
        // Shizuku 준비되면 폴러 자동 시작 (이미 실행 중이면 no-op)
        autoStartTracker()
    }

    /**
     * 서비스를 띄우기만 하고, Shizuku 대기 · 게임 패키지 탐색 · 폴러 시작은 서비스에 맡긴다.
     *
     * 예전에는 여기서 `if (!shizuku.state.value.ready) return` 으로 조기 종료했는데,
     * Shizuku 바인딩이 비동기라 **첫 onResume 에서는 거의 항상 not-ready** 였다.
     * 그래서 앱을 한 번 나갔다 들어와야(두 번째 onResume) 트래킹이 시작됐다.
     */
    private fun autoStartTracker() {
        val app = TrackerApplication.instance
        if (app.trackerService.value?.status?.value?.active == true) return
        TrackerForegroundService.startSelfManaged(this)
    }
}
