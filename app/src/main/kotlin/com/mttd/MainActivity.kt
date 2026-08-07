package com.mttd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mttd.service.TrackerForegroundService
import com.mttd.ui.onboarding.OnboardingScreen
import com.mttd.ui.theme.mTTDTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val shizuku = TrackerApplication.instance.shizukuManager

        setContent {
            mTTDTheme {
                val state by shizuku.state.collectAsStateWithLifecycle()
                OnboardingScreen(
                    state = state,
                    onRequestPermission = { shizuku.requestPermissionOrBind() },
                    userService = { shizuku.service },
                )
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
