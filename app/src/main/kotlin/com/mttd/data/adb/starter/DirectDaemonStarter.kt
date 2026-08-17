package com.mttd.data.adb.starter

import android.os.Bundle
import java.io.File
import com.mttd.service.UserService

/**
 * `app_process` 로 shell UID 백그라운드 프로세스로 띄우는 진입점(Shizuku `ServiceStarter` 와
 * 같은 역할, [HiddenApis] 클래스 doc 참조 — 새로 작성, Shizuku 소스 포팅 아님).
 *
 * [com.mttd.service.UserService] 는 예전에 Shizuku 프로세스 안에서 쓰던 그 구현체를 그대로
 * 재사용한다 — `app_process` 에 우리 앱 APK 를 CLASSPATH 로 넘기면 같은 dex
 * 안의 이 클래스를 그대로 로드할 수 있어서, 파일 접근 로직([com.mttd.data.GameFileAccessPolicy]
 * 화이트리스트 포함)을 중복 구현할 필요가 없다.
 *
 * 뜬 뒤엔 [DirectUserServiceProvider] (앱 프로세스, Zygote로 정상 기동된 쪽)에 Binder를
 * ContentProvider `call()` 로 넘긴다 — 실제 서비스는 이 Binder를 통해 앱이 직접 붙어서 쓴다.
 * Binder 의 실체(UserService 인스턴스)가 이 프로세스에 있으므로, 이 프로세스가 죽으면 앱이
 * 들고 있는 참조도 함께 죽는다 — 그래서 한 번 보내고 끝내지 않고 두 가지를 계속 반복한다:
 *
 * 1. **재전송**: 앱 프로세스가 (크래시나 OS의 일시적 백그라운드 kill 등으로) 재시작되면 Binder
 *    참조를 잃어버린다. 앱은 재시작 시 스스로 "이미 뜬 데몬이 있으니 그걸 쓰자"고 판단할 방법이
 *    없어서(먼저 연락해오는 쪽은 항상 이 데몬이다), 이 프로세스가 살아있는 한 계속 재전송을
 *    시도해야 앱이 WiFi 없이도(adb 재부트스트랩 없이) 다시 붙을 수 있다.
 * 2. **자동 만료**: 반대로 사용자가 mTTD를 완전히 안 쓰는데 이 shell UID 프로세스가 무한정
 *    남아있는 것도 바람직하지 않다. 다만 "앱이 종료됐다"는 이벤트를 이 프로세스가 직접 감지해
 *    즉시 죽는 방식은 위험하다 — [com.mttd.service.TrackerForegroundService] 는 `START_STICKY`라
 *    OS가 메모리 확보차 잠깐 죽였다 자동 재시작하는 경우에도 같은 "죽음" 신호가 뜨고, 그때
 *    데몬까지 같이 죽이면 재시작 직후 또 WiFi가 필요한 재부트스트랩이 필요해져서 이번에 고친
 *    문제가 그대로 재발한다. 그래서 즉각 반응하는 이벤트 훅 대신, [SELF_EXPIRE_AFTER_MS] 동안
 *    앱 프로세스를 한 번도 못 보면(=일시적 재시작이 아니라 정말 안 쓰는 것으로 판단) 그때 스스로
 *    종료한다 — 오탐(정상 사용 중인데 잘못 죽는 것) 위험을 낮추는 대신 정리가 느린 쪽을 택했다.
 *
 * ## 앱이 살아있을 때만 [HiddenApis.callProvider] 를 부른다
 *
 * `IActivityManager.getContentProviderExternal()` 은 **대상 provider 의 프로세스가 안 떠 있으면
 * 새로 띄운다** (`content` 셸 커맨드가 잠든 앱을 깨우는 그 경로). 그래서 앱 생존 확인 없이 무조건
 * 재전송하면 두 가지가 동시에 깨진다:
 *
 * - 사용자가 최근앱에서 스와이프로 끈 앱을 데몬이 계속 되살린다.
 * - 그 호출이 늘 성공하므로 위 2번의 만료 시계가 영원히 리셋돼 **데몬이 절대 자살하지 않는다.**
 *
 * 그래서 `/proc` 스캔으로 앱 프로세스를 먼저 확인하고, 없으면 아예 호출하지 않는다. shell UID 가
 * 다른 프로세스의 `/proc/<pid>/cmdline` 을 읽는 건 `mttd_starter.cpp` 의 `foreach_proc()` 로 이미
 * 검증된 방식이다. 덕분에 스캔 주기를 [SCAN_INTERVAL_MS] 로 짧게 잡아도 앱이 없을 때 비용이
 * 스캔뿐이라, 앱 재시작 후 다시 붙기까지의 공백도 함께 줄어든다.
 */
object DirectDaemonStarter {

    private const val METHOD_SEND_USER_SERVICE = "sendUserService"
    private const val EXTRA_BINDER = "binder"

    /** `/proc` 을 훑어 앱 프로세스를 확인하는 주기. 앱 재시작을 이만큼 안에 알아챈다. */
    private const val SCAN_INTERVAL_MS = 3_000L

    /**
     * 같은 앱 프로세스에게 재전송을 반복하는 주기. 새 PID 를 발견하면 이 주기와 무관하게 즉시
     * 보내므로, 이 값은 "이미 보낸 PID 에게 혹시 못 받았을까봐 다시 보내는" 안전망 역할만 한다
     * (예: [DirectUserServiceProvider] 가 [com.mttd.data.adb.DirectAdbManager.start] 보다 먼저
     * 떠서 콜백이 아직 null 인 찰나에 도착한 경우).
     */
    private const val ANNOUNCE_INTERVAL_MS = 15_000L

    /** 클래스 doc의 "자동 만료" 참조 — 넉넉하게 잡아 정상적인 재시작/백그라운드 전환 중 오탐 방지. */
    private const val SELF_EXPIRE_AFTER_MS = 60 * 60_000L // 1시간

    @JvmStatic
    fun main(args: Array<String>) {
        val params = args.associate { arg ->
            val idx = arg.indexOf('=')
            if (idx < 0) arg to "" else arg.substring(0, idx) to arg.substring(idx + 1)
        }
        val authority = params["--authority"] ?: error("--authority= required")
        val callingPkg = params["--pkg"] ?: error("--pkg= required")

        val service = UserService()
        val extras = Bundle()
        extras.putBinder(EXTRA_BINDER, service.asBinder())

        // 이 데몬은 **앱이 켜져 있을 때 그 앱의 adb 연결로** 기동된다 — 즉 지금 이 순간 앱
        // 프로세스는 반드시 존재한다. 그런데도 못 찾는다면 `/proc` 을 신뢰할 수 없는 환경
        // (예: hidepid 로 마운트된 커스텀 롬)이라는 뜻이므로, 게이트를 끄고 예전처럼 무조건
        // 재전송한다 — 죽은 앱을 한 번 되살리는 비용보다, 살아있는 앱이 데몬에 영영 못 붙어
        // WiFi 의존 폴백에 갇히는 손해가 훨씬 크다.
        val procScanUsable = findAppPid(callingPkg) >= 0

        // 시작 시점을 첫 기준점으로 삼는다 — 앱이 아직 뜨기 전이라 첫 시도가 바로 실패해도
        // SELF_EXPIRE_AFTER_MS 전체를 유예로 준다(막 시작했는데 바로 만료 판정하면 안 됨).
        var lastSeenAtMs = System.currentTimeMillis()
        var announcedPid = -1
        var lastAnnounceAtMs = 0L

        while (!procScanUsable) {
            // `/proc` 을 못 믿는 환경용 폴백 — 원본과 같은 "무조건 재전송" 동작.
            try {
                HiddenApis.callProvider(authority, callingPkg, METHOD_SEND_USER_SERVICE, extras)
                lastSeenAtMs = System.currentTimeMillis()
            } catch (t: Throwable) {
                if (System.currentTimeMillis() - lastSeenAtMs > SELF_EXPIRE_AFTER_MS) {
                    System.exit(0)
                }
            }
            Thread.sleep(ANNOUNCE_INTERVAL_MS)
        }

        while (true) {
            val pid = findAppPid(callingPkg)
            if (pid < 0) {
                // 앱이 없다 — 여기서 callProvider 를 부르면 앱을 되살린다(클래스 doc 참조).
                if (System.currentTimeMillis() - lastSeenAtMs > SELF_EXPIRE_AFTER_MS) {
                    System.exit(0)
                }
            } else {
                lastSeenAtMs = System.currentTimeMillis()
                val isNewProcess = pid != announcedPid
                val safetyNetDue = lastSeenAtMs - lastAnnounceAtMs > ANNOUNCE_INTERVAL_MS
                if (isNewProcess || safetyNetDue) {
                    try {
                        HiddenApis.callProvider(authority, callingPkg, METHOD_SEND_USER_SERVICE, extras)
                        announcedPid = pid
                        lastAnnounceAtMs = System.currentTimeMillis()
                    } catch (t: Throwable) {
                        // 일시적 오류(앱이 막 뜨는 중 등) — announcedPid 를 갱신하지 않으므로
                        // 다음 스캔에서 자동으로 다시 시도한다.
                    }
                }
            }
            Thread.sleep(SCAN_INTERVAL_MS)
        }
    }

    /**
     * `/proc` 을 훑어 프로세스 이름이 [pkg] 와 정확히 같은 PID 를 찾는다. 없으면 -1.
     *
     * 이 앱은 `android:process` 를 안 쓰는 단일 프로세스라 프로세스 이름이 곧 패키지명이다.
     * `cmdline` 은 NUL 로 구분되므로 첫 토큰만 본다.
     */
    private fun findAppPid(pkg: String): Int {
        val entries = File("/proc").list() ?: return -1
        for (entry in entries) {
            val pid = entry.toIntOrNull() ?: continue
            val name = try {
                File("/proc/$entry/cmdline").readText().substringBefore('\u0000').trim()
            } catch (_: Throwable) {
                continue // 스캔 도중 사라진 프로세스 등 — 그냥 넘어간다.
            }
            if (name == pkg) return pid
        }
        return -1
    }
}
