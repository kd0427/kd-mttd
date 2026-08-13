package com.mttd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.mttd.data.prefs.OverlayPrefs

/** 자동 시작을 켠 경우 재부팅 뒤에도 게임 실행 감시를 복원한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val enabled = runBlocking { OverlayPrefs(context.applicationContext).autoStartOnGameLaunch.first() }
        if (enabled) TrackerForegroundService.startSelfManaged(context)
    }
}
