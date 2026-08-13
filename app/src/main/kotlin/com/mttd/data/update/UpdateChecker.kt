package com.mttd.data.update

import android.util.Log
import com.mttd.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * GitHub Releases 기반 업데이트 확인 — **알림만 하고 설치는 하지 않는다.**
 *
 * 자동 설치를 하려면 `REQUEST_INSTALL_PACKAGES` 를 받거나 Shizuku 로 `pm install` 을 돌려야 하는데,
 * 이 앱은 shell 권한 프로세스([com.mttd.service.UserService])를 **읽기 전용**으로 유지하는 게
 * 원칙이라 그 경로를 열지 않는다. 자동 업데이트가 필요하면 Obtainium 같은 외부 도구를 쓰면 된다.
 *
 * 비교는 `versionName` ↔ 릴리스 태그(`vX.Y.Z`) 의 semver 비교.
 */
class UpdateChecker {

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return 최신 여부 또는 실패 원인을 구분해 반환한다.
     */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        // 이 커스텀 포크는 원본의 릴리스를 업데이트로 제시하지 않는다. 개인 저장소를
        // 만들기 전에는 네트워크 요청도 하지 않는다.
        if (BuildConfig.UPDATE_REPO.isBlank()) {
            return@withContext CheckResult.Failed("업데이트 저장소가 설정되지 않았습니다")
        }
        try {
            val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            val resp = client.newCall(req).awaitResponse()
            val body = resp.use { r ->
                if (!r.isSuccessful) {
                    Log.i(TAG, "check failed: HTTP ${r.code}")
                    return@withContext CheckResult.Failed("GitHub 응답 오류 (HTTP ${r.code})")
                }
                r.body?.string() ?: return@withContext CheckResult.Failed("GitHub 응답이 비어 있습니다")
            }
            val rel = json.decodeFromString<GithubRelease>(body)
            if (rel.draft || rel.prerelease) return@withContext CheckResult.UpToDate

            val latest = rel.tagName.removePrefix("v").trim()
            val current = BuildConfig.VERSION_NAME.substringBefore("-").trim()
            if (compareSemver(latest, current) <= 0) return@withContext CheckResult.UpToDate

            val apk = rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            val update = Update(
                versionName = latest,
                releaseUrl = rel.htmlUrl,
                apkUrl = apk?.browserDownloadUrl,
                apkSizeBytes = apk?.size ?: 0,
                notes = rel.body.orEmpty().trim().take(600),
            )
            Log.i(TAG, "update available: $current -> $latest")
            CheckResult.UpdateAvailable(update)
        } catch (t: Throwable) {
            Log.i(TAG, "check failed: ${t.message}")
            CheckResult.Failed("업데이트 확인 실패")
        }
    }

    sealed interface CheckResult {
        data class UpdateAvailable(val update: Update) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    data class Update(
        val versionName: String,
        val releaseUrl: String,
        val apkUrl: String?,
        val apkSizeBytes: Long,
        val notes: String,
    )

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) { cont.resume(response) {} }
                override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            })
            cont.invokeOnCancellation { runCatching { cancel() } }
        }

    companion object {
        private const val TAG = "mTTD.Update"

        /**
         * `1.2.10` vs `1.3.0` 같은 semver 비교. 숫자 파트만 본다.
         * @return a > b 면 양수, 같으면 0, a < b 면 음수.
         */
        fun compareSemver(a: String, b: String): Int {
            val pa = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
