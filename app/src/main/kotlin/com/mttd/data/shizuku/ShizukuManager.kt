package com.mttd.data.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.mttd.BuildConfig
import com.mttd.IUserService
import com.mttd.service.UserService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku 라이프사이클 + UserService 바인딩 매니저.
 *
 * 상태를 [state] StateFlow 로 노출해서 UI 가 collect. 4 개의 하위 신호로 구성:
 *
 * - [ShizukuState.installed]      Shizuku 앱이 설치되었는가
 * - [ShizukuState.binderAlive]    Shizuku daemon 이 살아있는가 (`pingBinder`)
 * - [ShizukuState.permission]     Shizuku 권한 grant 여부
 * - [ShizukuState.userServiceBound] 우리 UserService 가 shell UID 프로세스로 바인딩됐는가
 *
 * 앱 프로세스에서 [service] 로 AIDL 호출.
 */
class ShizukuManager(private val appContext: Context) {

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile
    var service: IUserService? = null
        private set

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, UserService::class.java.name)
    )
        .daemon(false)          // 프로세스가 앱과 함께 죽어도 OK — M6 에서 daemon=true 로 승격 검토
        .processNameSuffix("adb")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "UserService connected")
            service = if (binder != null && binder.pingBinder()) {
                IUserService.Stub.asInterface(binder)
            } else null
            _state.update { it.copy(userServiceBound = service != null) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "UserService disconnected")
            service = null
            _state.update { it.copy(userServiceBound = false) }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        refreshBinderState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        service = null
        _state.update { it.copy(binderAlive = false, permission = false, userServiceBound = false) }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Shizuku permission result: granted=$granted")
            _state.update { it.copy(permission = granted) }
            if (granted) bindUserService()
        }
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        _state.update { it.copy(installed = isShizukuInstalled()) }
        refreshBinderState()
    }

    fun stop() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {}
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (_: Throwable) {}
        service = null
    }

    /** UI 에서 호출. permission 없으면 요청, 있으면 서비스 바인딩. */
    fun requestPermissionOrBind() {
        val installed = isShizukuInstalled()
        _state.update { it.copy(installed = installed) }
        if (!installed) return
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(binderAlive = true, permission = true) }
            bindUserService()
        } else {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * 권한이 이미 있으면 바인딩만 시도한다. **권한 요청 다이얼로그는 띄우지 않는다.**
     * 백그라운드 서비스의 재시도 루프에서 호출하기 위한 것 — [requestPermissionOrBind] 를
     * 3 초마다 부르면 권한 미승인 상태에서 다이얼로그가 반복해서 뜬다.
     */
    fun bindIfPermitted() {
        _state.update { it.copy(installed = isShizukuInstalled()) }
        refreshBinderState()
    }

    private fun refreshBinderState() {
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val perm = alive && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        _state.update { it.copy(binderAlive = alive, permission = perm) }
        if (perm && service == null) bindUserService()
    }

    private fun bindUserService() {
        try {
            Log.i(TAG, "binding UserService…")
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService failed", t)
        }
    }

    private fun isShizukuInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // 편의 확장 — StateFlow.update {} 는 코틀린 1.7+ 표준.
    private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
        while (true) {
            val prev = value
            val next = block(prev)
            if (compareAndSet(prev, next)) return
        }
    }

    companion object {
        private const val TAG = "mTTD.Shizuku"
        const val PERMISSION_REQUEST_CODE = 1001
    }
}

data class ShizukuState(
    val installed: Boolean = false,
    val binderAlive: Boolean = false,
    val permission: Boolean = false,
    val userServiceBound: Boolean = false,
) {
    val ready: Boolean get() = installed && binderAlive && permission && userServiceBound
}
