package com.mttd.diagnostics

import android.content.Context
import android.os.Build
import com.mttd.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 크래시 시 스택트레이스를 기기 로컬 파일에만 남긴다.
 *
 * 자동으로 어디로도 전송하지 않는다 — 이 앱의 "아웃바운드는 정해진 호스트 몇 개뿐" 원칙과
 * 상충하지 않도록, Crashlytics 류의 자동 업로드 SDK 대신 로컬 파일 + 유저가 원할 때 직접
 * 공유하는 방식을 쓴다 (공유 UI 는 별도로 붙인다).
 */
object CrashLogger {
    private const val DIR_NAME = "crash_logs"
    private const val MAX_LOGS = 5

    /** [android.app.Application.onCreate] 맨 앞에서 한 번 호출. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(appContext, thread, throwable)
            } catch (_: Throwable) {
                // 로그를 남기다가 또 죽으면 안 되니 여기서는 조용히 무시.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$timestamp.txt")

        val header = buildString {
            appendLine("mTTD ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine("Time: $timestamp")
            appendLine("---")
        }
        file.writeText(header + throwable.stackTraceToString())

        // 오래된 것부터 정리, 최근 MAX_LOGS 개만 유지 (무한정 쌓이지 않게).
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_LOGS)
            ?.forEach { it.delete() }
    }

    /** 저장된 크래시 로그 목록, 최신 순. 공유 UI 에서 쓴다. */
    fun listLogs(context: Context): List<File> {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
