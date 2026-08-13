package com.mttd.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** GitHub 릴리스 APK를 앱 안에서 받아 Android의 기존 앱 업데이트 화면으로 넘긴다. */
object AppUpdateInstaller {
    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data object InstallerOpened : Result
        data object PermissionSettingsOpened : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Android 보안 정책상 실제 설치는 시스템 확인 창에서 사용자가 승인해야 한다.
     * 같은 applicationId·서명·더 높은 versionCode APK라 기존 데이터는 유지한 채 덮어쓴다.
     */
    suspend fun downloadAndInstall(context: Context, apkUrl: String): Result {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return Result.PermissionSettingsOpened
        }

        val apkFile = try {
            withContext(Dispatchers.IO) { download(context, apkUrl) }
        } catch (e: HttpStatusException) {
            return Result.Failed(
                if (e.code == 403) "GitHub 다운로드가 거부되었습니다. 잠시 뒤 다시 시도하세요 (HTTP 403)"
                else "업데이트 APK 다운로드에 실패했습니다 (HTTP ${e.code})",
            )
        } catch (_: IOException) {
            return Result.Failed("업데이트 APK 다운로드에 실패했습니다")
        } catch (_: Throwable) {
            return Result.Failed("업데이트 준비 중 오류가 발생했습니다")
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updates",
                apkFile,
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            Result.InstallerOpened
        } catch (_: Throwable) {
            Result.Failed("시스템 업데이트 화면을 열 수 없습니다")
        }
    }

    @Throws(IOException::class)
    private fun download(context: Context, apkUrl: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "update.apk")
        val partial = File(dir, "update.apk.part")
        partial.delete()

        client.newCall(
            Request.Builder()
                .url(apkUrl)
                .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                .header("User-Agent", "kd-mTTD updater/1.0 (Android)")
                .get()
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val body = response.body ?: throw IOException("empty body")
            body.byteStream().use { input ->
                partial.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
        if (partial.length() == 0L || !partial.renameTo(target)) {
            partial.delete()
            throw IOException("invalid apk")
        }
        return target
    }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")
}
