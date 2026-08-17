package com.mttd.data.adb

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import com.mttd.IUserService
import com.mttd.data.GameFileAccessPolicy
import com.mttd.data.adb.starter.DirectUserServiceProvider
import com.mttd.diagnostics.DiagnosticLog
import com.mttd.service.DirectAdbPairingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "mTTD.DirectAdb"
private const val CONNECT_HOST = "127.0.0.1" // 페어링/연결 전부 같은 기기 자기 자신 대상.

/**
 * Shizuku 없이 기기의 무선 디버깅(Android 11+) 에 직접 페어링/연결해서 [IUserService] 를
 * 제공한다.
 *
 * 이 포크는 Shizuku 앱 설치·권한 허용 단계를 없애려고 이 경로만 쓴다 (CUSTOMIZATION.md 참고).
 * 덤으로 `Shizuku.bindUserService()` 가 일부 기기에서 예외도 콜백도 없이 영원히 멈추는 알려진
 * 업스트림 버그(RikkaApps/Shizuku#475, #451)도 함께 피해간다.
 *
 * 페어링 코드 입력은 [DirectAdbPairingService] 의 알림(RemoteInput)이 담당한다 — 설정 앱의
 * "페어링 코드로 기기 페어링" 화면을 유저가 벗어나면(=우리 앱으로 전환하면) 그 페어링 세션
 * 자체가 끊기기 때문에, 화면 전환 없이 알림 서랍에서 코드만 입력받아야 한다.
 *
 * [AdbClient] 하나를 계속 물고 있다가 매 [IUserService] 호출을 `shell:<cmd>` 한 번으로
 * 처리한다. 호출마다 새로 연결하면 TCP+TLS+ADB 핸드셰이크를 반복해서
 * [com.mttd.data.log.LogPoller] 의 폴링 성능에 치명적이므로 주의.
 */
class DirectAdbManager(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("direct_adb", Context.MODE_PRIVATE)
    private val keyStore = PreferenceAdbKeyStore(prefs)
    private val key by lazy(LazyThreadSafetyMode.NONE) { AdbKey(keyStore, "mTTD") }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connected = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * shell UID 데몬 Binder 가 실제로 붙어 있는가 = **네트워크 없이도 동작하는 상태인가.**
     *
     * [ready] 와 구분해야 한다. [ready] 는 "로그를 읽을 수 있다" 일 뿐이라, 데몬이 붙기 전
     * adb 셸 폴백([LocalUserService])으로 읽는 동안에도 참이다. 그때 WiFi 를 끄면 끊긴다.
     * 사용자에게 "이제 WiFi 꺼도 된다" 고 말해도 되는 시점은 이쪽이 참이 된 뒤다.
     */
    private val _daemonReady = MutableStateFlow(false)
    val daemonReady: StateFlow<Boolean> = _daemonReady.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** [DirectAdbStatusCard][com.mttd.ui.onboarding.DirectAdbStatusCard] 진행 상태 표시용. */
    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    enum class Status { IDLE, SEARCHING, WAITING_FOR_CODE, PAIRING, CONNECTING, CONNECTED, FAILED }

    @Volatile
    private var client: AdbClient? = null
    private val clientLock = Any()

    @Volatile
    var service: IUserService? = null
        private set

    fun start() {
        DiagnosticLog.log(appContext, "DirectAdb", "start()")
        // 데몬(DirectDaemonStarter)이 아직 안 붙었을 때의 폴백 — adb shell 커맨드 하나마다
        // 왕복하는 LocalUserService 는 WiFi 가 꺼지면 같이 죽는다(클래스 doc 의 WiFi 의존성
        // 설명 참조). launchDaemon() 이 성공하면 handleDaemonBinder() 가 이걸 진짜 Binder 기반
        // service 로 갈아치운다.
        service = LocalUserService()
        DirectUserServiceProvider.onBinderReceived = { binder -> handleDaemonBinder(binder) }
    }

    /**
     * [DirectUserServiceProvider] 가 shell UID 데몬으로부터 Binder를 받으면 호출됨(앱 프로세스
     * 쪽, 메인 스레드가 아닐 수 있음). 이후로는 adb 연결이 끊겨도(WiFi→LTE 전환 등) 이 Binder는
     * 순수 커널 Binder IPC라 영향을 안 받는다 — [DirectAdbManager] 클래스 doc의 WiFi 의존성
     * 문제를 이 경로가 해결한다.
     *
     * [DirectDaemonStarter] 가 살아있는 동안 이 Binder를 주기적으로 계속 재전송하므로(앱이
     * 재시작돼도 다시 붙을 수 있게 하기 위함, 그쪽 클래스 doc 참조) 이미 붙어있는 상태에서도
     * 반복 호출될 수 있다 — 매번 [linkToDeath] 를 새로 걸면 같은 프로세스가 나중에 한 번 죽을 때
     * death recipient 가 중복 등록돼 콜백이 여러 번 불린다. 이미 붙어있으면 그냥 무시한다.
     */
    private fun handleDaemonBinder(binder: IBinder) {
        if (daemonBound) return
        try {
            binder.linkToDeath({
                Log.w(TAG, "daemon binder died")
                DiagnosticLog.log(appContext, "DirectAdb", "daemon binder died — will re-bootstrap on next retryConnect")
                daemonBound = false
                _daemonReady.value = false
                // service 는 일부러 안 내린다 — 다음 IUserService 호출이 자연히 실패하면서
                // 호출부(LogPoller 등)가 기존에 하던 에러 처리 경로를 그대로 타면 되고, 여기서
                // service = null 로 내리면 그 사이 잠깐 "아직 준비 안 됨" 오탐이 뜬다.
            }, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "linkToDeath failed", t)
        }
        service = IUserService.Stub.asInterface(binder)
        daemonBound = true
        _daemonReady.value = true
        _connected.value = true
        _status.value = Status.CONNECTED
        DiagnosticLog.log(appContext, "DirectAdb", "daemon binder attached — switched off adb shell fallback")
        Log.i(TAG, "daemon binder attached")
    }

    @Volatile
    private var daemonBound = false
    @Volatile
    private var launchingDaemon = false

    /**
     * shell UID 데몬([DirectDaemonStarter])을 [AdbClient] 로 이미 확립된 adb 연결 위에서 한 번
     * 띄운다 — 이 호출 자체는 adb(=이 시점엔 WiFi)가 필요하지만, 뜨고 나면 [handleDaemonBinder]
     * 가 받는 Binder는 네트워크와 무관해진다. [connectLocked] 직후(완전 페어링/재연결 성공 시)
     * 호출한다.
     */
    private fun launchDaemon() {
        if (daemonBound || launchingDaemon) return
        val starterPath = "${appContext.applicationInfo.nativeLibraryDir}/libmttd_starter.so"
        val apkPath = appContext.applicationInfo.sourceDir
        val authority = "${appContext.packageName}.direct.userservice"
        val cmd = "$starterPath --apk=$apkPath " +
            "--class=com.mttd.data.adb.starter.DirectDaemonStarter " +
            "--name=mttd_daemon --authority=$authority --pkg=${appContext.packageName}"
        launchingDaemon = true
        try {
            runShell(cmd)
            DiagnosticLog.log(appContext, "DirectAdb", "launchDaemon() issued")
        } catch (t: Throwable) {
            Log.w(TAG, "launchDaemon failed", t)
            DiagnosticLog.log(appContext, "DirectAdb", "launchDaemon failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            launchingDaemon = false
        }
    }

    fun hasSavedConnection(): Boolean = prefs.getInt(KEY_PORT, -1) > 0

    /** [DirectAdbStatusCard] 의 "페어링 시작" 버튼 — 알림 기반 코드 입력 플로우를 띄운다. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun startPairing() {
        _lastError.value = null
        _status.value = Status.SEARCHING
        appContext.startForegroundService(DirectAdbPairingService.startIntent(appContext))
    }

    /**
     * [DirectAdbPairingService] 가 알림 RemoteInput 으로 코드를 받으면 호출 — 실제 SPAKE2
     * 페어링 후, 이어서 연결 포트를 mDNS 로 찾아 접속까지 마친다.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun completePairing(pairPort: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _status.value = Status.PAIRING
            val ok = AdbPairingClient(CONNECT_HOST, pairPort, code, key).use { it.start() }
            if (!ok) {
                _status.value = Status.FAILED
                _lastError.value = "페어링 실패 (코드를 다시 확인해주세요)"
                DiagnosticLog.log(appContext, "DirectAdb", "pairing failed")
                return@withContext Result.failure(AdbInvalidPairingCodeException())
            }
            DiagnosticLog.log(appContext, "DirectAdb", "pairing succeeded, discovering connect port")

            _status.value = Status.CONNECTING
            val connectPort = discoverPort(AdbMdns.TLS_CONNECT, DISCOVER_TIMEOUT_MS)
            if (connectPort == null) {
                _status.value = Status.FAILED
                _lastError.value = "페어링은 됐는데 연결 포트를 못 찾았어요 — 무선 디버깅이 켜져있는지 확인해주세요"
                DiagnosticLog.log(appContext, "DirectAdb", "connect port discovery timed out after pairing")
                return@withContext Result.failure(IllegalStateException("connect port not found"))
            }

            prefs.edit().putInt(KEY_PORT, connectPort).apply()
            connectLocked(connectPort)
            launchDaemon()
            _status.value = Status.CONNECTED
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "completePairing failed", t)
            _status.value = Status.FAILED
            _lastError.value = "페어링 실패: ${t.message ?: t.javaClass.simpleName}"
            DiagnosticLog.log(appContext, "DirectAdb", "completePairing failed: ${t.javaClass.simpleName}: ${t.message}")
            Result.failure(t)
        }
    }

    /**
     * 재연결이 이미 진행 중인지 — [TrackerForegroundService] 의 3초 재시도 루프와
     * [MainActivity][com.mttd.MainActivity]`.onResume()` 이 둘 다 이걸 부르는데, 이전 시도가
     * 아직 안 끝났으면 또 부르지 않는다. 이게 없으면 두 호출이 겹쳐서 동시에 여러 연결을 만들고
     * 버리는 레이스가 생겼다(진단 로그에 `connected on port N` 이 수 ms 간격으로 여러 번 찍힘).
     */
    @Volatile
    private var reconnecting = false

    /** 부트스트랩 재시도 루프용 — 알림/다이얼로그 없이, 저장된 포트로만 재연결 시도. */
    fun retryConnect() {
        // 데몬 Binder가 이미 붙어있으면(WiFi가 있든 없든 계속 유효) adb 재연결 자체가 필요 없다
        // — _connected.value 만으로는 이 구분이 안 돼서(adb 연결 유지 여부만 반영) daemonBound 를
        // 별도로 본다.
        if (daemonBound || reconnecting) return
        val savedPort = prefs.getInt(KEY_PORT, -1)
        if (savedPort <= 0) return
        reconnecting = true
        _status.value = Status.CONNECTING
        managerScope.launch {
            try {
                connectLocked(savedPort)
                launchDaemon()
                _status.value = Status.CONNECTED
            } catch (t: Throwable) {
                // 저장된 포트가 이제 안 맞을 수 있다(무선 디버깅 재시작 등) — mDNS로 다시 찾아본다.
                @Suppress("NewApi")
                val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    discoverPort(AdbMdns.TLS_CONNECT, DISCOVER_TIMEOUT_MS)
                } else null
                if (fresh != null) {
                    try {
                        prefs.edit().putInt(KEY_PORT, fresh).apply()
                        connectLocked(fresh)
                        launchDaemon()
                        _status.value = Status.CONNECTED
                        return@launch
                    } catch (t2: Throwable) {
                        Log.w(TAG, "retryConnect (rediscovered port) failed", t2)
                    }
                }
                Log.w(TAG, "retryConnect failed", t)
                _status.value = Status.IDLE
                // 첫 시도의 예외(t)를 그대로 보여주면 안 된다 — 저장된 포트는 무선 디버깅을
                // 껐다 켤 때마다 바뀌므로 그 실패는 정상이고, 진짜 원인은 그 다음 재탐색이
                // 실패했는지 여부다. 예전엔 t.message 를 띄우는 바람에 무선 디버깅이 꺼져
                // 있을 때도 "port 42943 ECONNREFUSED" 같은, 사용자가 손쓸 수 없는 문구가 떴다.
                _lastError.value = if (fresh == null) {
                    "무선 디버깅을 찾지 못했습니다 — 개발자 옵션에서 켜져 있는지, WiFi 에 연결됐는지 확인한 뒤 이 화면을 다시 열어주세요"
                } else {
                    "연결하지 못했습니다 — 무선 디버깅을 껐다 켠 뒤 다시 시도해주세요"
                }
                DiagnosticLog.log(appContext, "DirectAdb", "retryConnect failed (rediscovered=${fresh != null}): ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                reconnecting = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun discoverPort(serviceType: String, timeoutMs: Long): Int? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                lateinit var mdns: AdbMdns
                val observer = Observer<Int> { port ->
                    if (port > 0 && cont.isActive) {
                        cont.resume(port)
                    }
                }
                mdns = AdbMdns(appContext, serviceType, observer)
                cont.invokeOnCancellation { mdns.stop() }
                mdns.start()
            }
        }

    /**
     * "진단 로그 보내기" 가 [com.mttd.diagnostics.DiagnosticLog.extraSection] 을 통해 부른다 —
     * 디버그 빌드에서만 등록되므로(release APK 는 이 경로 자체를 안 탐) 별도 가드 불필요.
     * 파일 경로 whitelist([GameFileAccessPolicy]) 대상이 아닌 유일한 셸 명령이라 여기 독립
     * 함수로 뒀다 — [IUserService] 에는 절대 안 태운다 (op 은 이름·타입 고정 원칙, AIDL 참고).
     */
    suspend fun captureLogcatText(maxLines: Int = 500): String = withContext(Dispatchers.IO) {
        try {
            runShell("logcat -d -t $maxLines").toString(Charsets.UTF_8)
        } catch (e: Exception) {
            "(logcat 캡처 실패: ${e.javaClass.simpleName}: ${e.message})"
        }
    }

    private fun connectLocked(port: Int) {
        synchronized(clientLock) {
            try {
                client?.close()
                val c = AdbClient(CONNECT_HOST, port, key)
                c.connect()
                client = c
                _connected.value = true
                // 이전 실패로 남아있던 에러 문구를 지운다 — 안 지우면 재연결에 성공해도 UI에
                // "재연결 실패: ..." 가 그대로 남아 유저를 헷갈리게 한다.
                _lastError.value = null
                DiagnosticLog.log(appContext, "DirectAdb", "connected on port $port")
            } catch (t: Throwable) {
                client = null
                _connected.value = false
                throw t
            }
        }
    }

    /** 연결이 끊긴 걸로 판단되면 상태를 내려서, 다음 재시도 루프가 재연결하게 한다. */
    private fun onConnectionLost(t: Throwable) {
        synchronized(clientLock) {
            try { client?.close() } catch (_: Throwable) {}
            client = null
        }
        _connected.value = false
        DiagnosticLog.log(appContext, "DirectAdb", "connection lost: ${t.javaClass.simpleName}: ${t.message}")
    }

    /** `'` 를 포함한 경로가 올 일은 없지만(내부 생성 경로뿐) 방어적으로 이스케이프. */
    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun runShell(command: String): ByteArray {
        val c = client ?: error("not connected")
        val out = java.io.ByteArrayOutputStream()
        try {
            synchronized(clientLock) {
                c.shellCommand(command) { chunk -> out.write(chunk) }
            }
        } catch (t: Throwable) {
            onConnectionLost(t)
            throw t
        }
        return out.toByteArray()
    }

    private inner class LocalUserService : IUserService.Stub() {

        override fun getFileSize(path: String): Long {
            GameFileAccessPolicy.ensurePathAllowed(path)
            return try {
                val out = runShell("stat -c%s ${shQuote(path)} 2>/dev/null").toString(Charsets.UTF_8).trim()
                out.toLongOrNull() ?: -1L
            } catch (e: Exception) {
                Log.w(TAG, "getFileSize failed for $path", e)
                -1L
            }
        }

        override fun fileExists(path: String): Boolean {
            GameFileAccessPolicy.ensurePathAllowed(path)
            return try {
                val out = runShell("[ -e ${shQuote(path)} ] && echo 1 || echo 0").toString(Charsets.UTF_8).trim()
                out == "1"
            } catch (e: Exception) {
                false
            }
        }

        override fun readFileChunk(path: String, offset: Long, maxBytes: Int): ByteArray {
            GameFileAccessPolicy.ensurePathAllowed(path)
            require(offset >= 0) { "offset must be >= 0" }
            require(maxBytes > 0) { "maxBytes must be > 0" }
            val limit = maxBytes.coerceAtMost(GameFileAccessPolicy.MAX_CHUNK_BYTES)
            return try {
                // AIDL 문서의 "등가 셸 커맨드"를 그대로 원격 실행 — tail -c +offset+1 | head -c limit.
                runShell("tail -c +${offset + 1} ${shQuote(path)} 2>/dev/null | head -c $limit")
            } catch (e: Exception) {
                Log.w(TAG, "readFileChunk failed for $path @$offset+$limit", e)
                ByteArray(0)
            }
        }

        override fun listInstalledGamePackages(): String {
            val pkgs = GameFileAccessPolicy.knownGamePackages.joinToString(" ")
            val cmd = "for p in $pkgs; do [ -d \"/sdcard/Android/data/\$p/files/UE4Game\" ] && echo \"\$p\"; done"
            return try {
                runShell(cmd).toString(Charsets.UTF_8).trim()
            } catch (e: Exception) {
                Log.w(TAG, "listInstalledGamePackages failed", e)
                ""
            }
        }

        override fun destroy() {
            // Shizuku 의 shell-UID 프로세스 종료와 달리 여기선 같은 앱 프로세스라 할 일이 없다 —
            // 연결만 끊는다.
            onConnectionLost(IllegalStateException("destroy() called"))
        }
    }

    companion object {
        private const val KEY_PORT = "connect_port"
        /**
         * mDNS 로 무선 디버깅 포트를 찾는 제한 시간.
         *
         * **페어링 코드 입력 시간과는 무관하다** — 코드 입력에는 제한이 없다
         * ([DirectAdbPairingService] 의 탐색은 타임아웃 없이 돌고, 알림도 답할 때까지 남는다).
         * 이 값은 코드가 통과한 **뒤** 연결 포트를 찾는 데, 그리고 [retryConnect] 의 재탐색에 쓰인다.
         */
        private const val DISCOVER_TIMEOUT_MS = 30_000L
    }
}
