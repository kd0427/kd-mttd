# 고인물 mTTD 커스텀 기준

이 저장소는 `listil/mttd`에서 가져온 개인 포크다.

## 원본과의 관계

- 원본 원격: `upstream` → `https://github.com/listil/mttd.git`
- 개인 GitHub 저장소를 만든 뒤 `origin`으로 추가한다.

```bash
git remote add origin https://github.com/GITHUB_ID/kd-mttd.git
git push -u origin main
```

원본 변경을 검토할 때는 다음을 사용한다. `a67ccaf` 는 **마지막으로 검토를 끝낸 원본 커밋**이다.

```bash
git fetch upstream
git log --oneline a67ccaf..upstream/main
```

> 한 배치를 반영(또는 검토 후 스킵)할 때마다 위 해시를 그때의 `upstream/main` 끝으로 갱신하고,
> 아래 표에 결과를 남긴다. **이걸 안 하면 이미 처리한 커밋이 계속 목록에 남는다** — 손으로 옮긴
> 커밋은 patch-id 가 달라져서 `--cherry-pick` 으로도 걸러지지 않는다.

### ⚠️ `main..upstream/main` 과 `git merge upstream/main` 은 쓰지 않는다

2026-08-15 에 커밋 메시지에서 `Co-Authored-By: Claude` 트레일러를 걷어내려고 전체 히스토리를
재작성했다. 해시가 전부 바뀌어서 **git 이 원본과 공유하던 이력을 더 이상 알아보지 못한다.**
merge-base 가 `aa6bbd4`(0.3.4 버전 범프 커밋 — `v0.3.4` **태그**가 가리키는 커밋은 아니다)
에서 `b9a9590`(v0.3.0) 으로 후퇴했고, 그 결과

- `git log main..upstream/main` 은 당시 실제 신규 8 개를 **27 개로 부풀려 보여줬다.**
- `git merge upstream/main` 은 **이미 반영된 19 개를 다시 얹으려 든다.**
  (`git log --left-right --cherry-pick main...upstream/main` 으로 실측한 값이다.)

커밋이 사라진 건 아니다 — `aa6bbd4` 는 `upstream/main` 을 통해 그대로 살아 있다. 이 후퇴는
영구적이므로 위의 "마지막 검토 지점" 방식을 계속 쓴다.

반영은 merge 가 아니라 필요한 커밋만 골라 손으로 옮기는 방식으로 한다. 이 포크는 오버레이·
미니패널을 크게 뜯어고쳐서, 그쪽을 건드리는 원본 커밋은 어차피 그대로 붙지 않는다.

### 검토 이력

**`aa6bbd4` → `a67ccaf`** (2026-08-15, 원본 신규 8 개)

| 원본 커밋 | 내용 | 처리 |
| --- | --- | --- |
| `02aff25` | 판매 아이템이 보유 목록에 유령으로 남는 문제 | 반영 (cherry-pick) |
| `59e07c5` | 캐릭터 빌드 내보내기를 로그 릴레이로 전환 | 반영 (cherry-pick + 충돌 해결) |
| `36e04d3` | 수익/가치 스와이프, 버튼을 화면 기준으로 구분 | 재구현 (헤더가 갈라져 그대로 안 붙음) |
| `1d57478` | 펼친 HUD 가 드래그로 안 움직이던 문제 | 재구현 (위와 한 묶음) |
| `bfb3345` | Shizuku 없이 무선 adb 로 붙는 direct 플레이버 | 스킵 — Shizuku 로 간다 (**2026-08-18 뒤집음**, 아래 참고) |
| `b53cd7f` | direct 플레이버 상주 데몬 | 스킵 (위와 같은 이유) |
| `a67ccaf` | direct 데몬 재접속·자동 정리 | 스킵 (위와 같은 이유) |
| `736ef28` | 원본 0.3.5 버전 범프 | 해당 없음 — 이 포크는 버전을 따로 매긴다 |

**2026-08-18 — direct 3 개 커밋의 스킵 결정을 뒤집음** (`upstream/main` 은 여전히 `a67ccaf`,
기준 해시는 안 움직인다)

`bfb3345` · `b53cd7f` · `a67ccaf` 를 반영했다. 08-15 에는 "Shizuku 로 간다"로 스킵했지만,
목표가 바뀌었다 — **Shizuku 앱 설치 단계 자체를 없애는 것**. 원본이 만든 이유(일부 기기에서
`Shizuku.bindUserService()` 가 영원히 멈추는 버그 우회)와는 다른 동기지만 결과물은 같다.

원본과 다르게 **플레이버로 나누지 않고 `main` 에서 Shizuku 를 통째로 걷어냈다.** 원본은 두
APK(`shizuku`/`direct`)를 유지해야 해서 `PrivilegedAccessManager` 추상화와 소스셋 분리가
필요했지만, 이 포크는 direct 경로 하나뿐이라 그게 전부 불필요하다. 그 덕에 CI 산출물 경로,
`RELEASING.md` 절차, 인앱 업데이트가 전부 그대로다.

| 원본과 다른 점 | 이유 |
| --- | --- |
| 플레이버 없음 (`src/direct/**` → `src/main/**`) | 구현이 하나뿐 |
| `PrivilegedAccessManager` 인터페이스 미도입 | 구현체가 1 개면 추상화가 비용만 남는다 |
| `minSdk` 를 30 으로 (플레이버가 아니라 앱 전체) | 무선 디버깅이 Android 11+ |
| `shizuku-api`/`provider` 의존성·`ShizukuManager` 삭제 | 쓰는 곳이 없음 |
| `extractNativeLibs` 를 매니페스트가 아니라 `packaging.jniLibs.useLegacyPackaging` 로 | 플레이버 오버라이드가 필요 없어져서 AGP 권장 방식이 됨 |
| `DirectDaemonStarter` R8 keep 규칙 추가 | `app_process` 가 클래스명 문자열로 로드한다 — 없으면 **릴리스 빌드에서만** 데몬이 안 뜨고 adb-shell 폴백으로 조용히 넘어간다 |

빌드에 NDK `29.0.13113456` + CMake `3.22.1` 이 필요해졌다 (`INSTALL.md`, `release.yml` 참고).

`upstream`은 **가져오기 전용**이다. 원본에 실수로 push 하는 길을 막으려고 push URL을 가짜 값으로
바꿔 뒀다 (`git push upstream`이 "repository does not exist"로 실패한다).

```bash
git remote set-url --push upstream DISABLED_do_not_push_to_upstream
```

같은 이유로 `gh`의 기본 저장소도 포크로 고정한다. 이 설정이 없으면 remote가 둘이라 `--repo` 없이
실행한 `gh` 명령이 원본을 향한다.

```bash
gh repo set-default kd0427/kd-mttd
```

두 설정 모두 `.git/config`에 있어서 clone 한 곳마다 다시 걸어야 한다.

## 이 포크의 식별자

| 항목 | 값 |
| --- | --- |
| 표시 이름 | 고인물 mTTD |
| release application ID | `com.doyoon.kdmttd` |
| debug application ID | `com.doyoon.kdmttd.debug` |
| APK 이름 | `kd-mttd-<version>-<buildType>.apk` |

Kotlin 내부 패키지(`com.mttd`)는 의도적으로 유지한다. 전면 패키지명 변경은 AIDL, Protobuf, ProGuard,
소스 경로까지 함께 바뀌어 커스텀 초기 단계에는 얻는 것보다 위험이 크다.

## 업데이트 채널

GitHub Actions 릴리스 빌드에서는 `kd0427/kd-mttd`를 업데이트 채널로 지정한다. 로컬 release
빌드에서도 같은 채널을 사용하려면 아래 옵션을 준다.

```bash
./gradlew :app:assembleRelease -PupdateRepo=kd0427/kd-mttd
```

## 게임 로그 접근 방식

Shizuku 앱을 쓰지 않는다. 앱이 기기의 무선 디버깅(Android 11+)에 **자기 자신으로** 페어링해서
`app_process` 로 shell UID 상주 데몬(`DirectDaemonStarter`)을 한 번 띄우고, 그 뒤로는 순수
Binder IPC 로만 통신한다.

- adb 연결은 **데몬을 띄우는 부트스트랩 용도로만** 쓴다 → 연결 후엔 WiFi 를 꺼도(LTE 등) 동작한다.
- 데몬은 3 초마다 `/proc` 을 훑어 앱 프로세스를 확인하고, **새 PID 를 보면 즉시** Binder 를
  재전송한다 → 앱이 재시작해도 최대 3 초 안에 다시 붙는다.
- 앱 프로세스를 72 시간 동안 한 번도 못 보면 데몬이 스스로 종료한다 (`SELF_EXPIRE_AFTER_MS`).
- **재부팅하면 무선 디버깅이 꺼지면서 데몬도 사라진다** → 개발자 옵션에서 다시 켜고 WiFi 에서
  한 번 다시 붙어야 한다. 페어링 키는 저장돼 있어 코드 재입력은 없다.

### ⚠️ `getContentProviderExternal` 은 앱을 되살린다

`HiddenApis.callProvider()` 가 쓰는 `IActivityManager.getContentProviderExternal()` 은 **대상
provider 의 프로세스가 없으면 새로 띄운다** (`content` 셸 커맨드가 잠든 앱을 깨우는 그 경로).
그래서 앱 생존 확인 없이 재전송하면 두 가지가 같이 깨진다 — 사용자가 스와이프로 끈 앱이 계속
되살아나고, 그 호출이 늘 성공하는 바람에 **만료 시계가 리셋돼 데몬이 절대 자살하지 않는다.**

그래서 `DirectDaemonStarter` 는 `/proc` 스캔으로 앱이 살아있을 때만 호출한다. 단 **스캔을 못
믿는 환경에서는 게이트를 끈다** — 데몬은 앱이 켜져 있을 때 그 앱의 adb 연결로 기동되므로 기동
직후 스캔에서 앱을 못 찾으면 `/proc` 이 가려진 환경(hidepid 로 마운트된 롬 등)이라는 뜻이고,
그때는 원본과 같은 무조건 재전송으로 되돌아간다. 죽은 앱을 한 번 되살리는 비용보다, 살아있는
앱이 데몬에 영영 못 붙어 WiFi 의존 폴백에 갇히는 손해가 훨씬 크기 때문이다. 원본
(`listil/mttd`)에는 이 가드가 없다 — 원본의 "안 쓰면 알아서 정리됨" 설명은 그래서 사실과 다르다.

같은 이유로 `getContentProviderExternal` 에 `token=null` 을 넘기던 것도 고쳤다. null 토큰은
"참조를 안 잡는다"가 아니라 AMS 의 **회수 불가 카운트**(`externalProcessNoHandleCount`)를 올려서
호출할 때마다 누적된다 — 실제 토큰을 넘기고 `removeContentProviderExternal` 로 돌려준다.

#### 에뮬레이터 검증 결과 (2026-08-18, API 37)

페어링 없이 `adb shell` 에서 데몬을 직접 띄워 확인했다. 재현 명령:

```bash
BASE=$(adb shell pm path com.doyoon.kdmttd.debug | sed 's|package:||;s|/base.apk||')
adb shell "$BASE/lib/arm64/libmttd_starter.so --apk=$BASE/base.apk \
  --class=com.mttd.data.adb.starter.DirectDaemonStarter --name=mttd_daemon \
  --authority=com.doyoon.kdmttd.debug.direct.userservice --pkg=com.doyoon.kdmttd.debug"
```

| 확인 | 결과 |
| --- | --- |
| `libmttd_starter.so` 가 실행 가능한 실제 파일로 풀림 | `-rwxr-xr-x` 확인 (`useLegacyPackaging` 유효) |
| 데몬 → 앱 Binder 핸드오프 | `daemon binder attached` |
| 토큰 누수 | 수정 전 `externals: notoken=10` → 수정 후 **externals 줄 자체가 없음** |
| 앱이 죽은 동안 AMS 호출 | 40 초간 **0 회** (부활 없음) |
| 앱 재시작 후 재부착 | **1.3 초** (기존 코드는 최대 15 초) |
| 데몬 자살 | `SELF_EXPIRE_AFTER_MS` 를 30 초로 낮춘 빌드에서 정확히 30 초에 종료 |
| 상주 알림 상태 표시 | 연결 후 게임이 없는 상태에서 "게임을 찾는 중" 으로 바뀌는 것 확인 |

**에뮬레이터로 확인 못 한 것**: 무선 디버깅 페어링(SPAKE2·mDNS), WiFi→LTE 전환 유지, 재부팅
동작. 그리고 검증 기기는 API 37 이라 실제 사용 대역(11~15)과 다르다 — 히든 API 시그니처가
그 대역에서도 같은지는 실기기 확인이 필요하다.

파일 접근 whitelist 는 `GameFileAccessPolicy` 한 곳에만 있다 — 데몬 쪽 `UserService` 와 데몬이
뜨기 전 폴백인 `DirectAdbManager.LocalUserService` 가 같이 쓰므로 여기만 고친다.

### USB 디버깅이 왜 필요한가 (원본에 없는 조건)

**무선 디버깅만 켜고 WiFi 를 끄면 데몬이 죽는다.** USB 디버깅까지 켜져 있으면 산다 —
케이블을 꽂을 필요는 없고 토글만 켜져 있으면 된다.

기기에서 확인한 원인:

```
adb shell 프로세스의 cgroup:  0::/system/uid_0/pid_526
adbd 의 pid:                 526          ← 같다
```

`adb shell` 로 뜬 프로세스는 **adbd 자신의 cgroup** 에 들어간다. `mttd_starter.cpp` 의
`fork()+setsid()` 는 세션만 분리할 뿐 cgroup 소속은 그대로다. adbd 는 init 서비스이고
(`init.svc.adbd`), init 은 서비스를 **cgroup 단위로** 죽인다. 그래서 WiFi 를 끄면 무선
디버깅이 꺼지고, 다른 전송 수단이 없으면 adbd 가 내려가면서 데몬도 같이 끌려간다.
USB 디버깅이 켜져 있으면 adbd 가 USB 전송 때문에 계속 살아 있어 데몬도 산다.

**코드로는 우회할 수 없다.** Shizuku 의 `switch_cgroup()` 이 이걸 탈출하는 함수인데
`uid == 0` 안에서만 부른다. 실제로 shell 로 시도하면:

```
shell 의 그룹에 system(1000) 없음
echo $$ > /sys/fs/cgroup/cgroup.procs  →  Permission denied
```

cgroup 파일이 전부 `system:system` 소유라 shell 은 쓸 수 없다. `/dev/cpuctl` 등 v1
컨트롤러도 마찬가지고, 애초에 죽이는 데 쓰이는 건 v2 통합 cgroup(`/sys/fs/cgroup`) 이다.
**root 없이는 방법이 없으므로 USB 디버깅을 켜 두는 것이 유일한 해법이다.**

원본(`listil/mttd`)의 b53cd7f 커밋은 "WiFi 끔 / WiFi→LTE 전환 양쪽에서 데몬 생존 확인"
이라고 적고 있는데, 개발 기기는 USB 디버깅이 켜져 있는 게 보통이라 이 조건이 가려진
것으로 보인다. 앱은 `adb_enabled`(USB)·`adb_wifi_enabled`(무선) 를 읽어 꺼져 있으면
경고한다 — 둘 다 일반 앱이 읽을 수 있는 값이다.

### "1 시간마다 무선 디버깅을 다시 켜야 한다" (2026-08-19)

제보 증상: 재부팅을 안 했는데 1 시간쯤 지나면 끊기고, 개발자 옵션에서 무선 디버깅을 다시
켜야 붙는다. USB 디버깅·WiFi 둘 다 켜진 상태였다.

원인은 우리 쪽 `SELF_EXPIRE_AFTER_MS` 였다 — 코드베이스에서 1 시간짜리 타이머는 이것뿐이다.
`stopWithTask=true` 때문에 최근 앱에서 스와이프하면 포그라운드 서비스까지 죽어 앱 프로세스가
사라지고, 정확히 1 시간 뒤 데몬이 자살한다. 그래서 72 시간으로 늘렸다 — 주말을 통째로
건너뛰어도 재설정이 필요 없게 하는 게 기준이다.

같이 확인한 사실 두 가지:

- **무선 디버깅은 재부팅 말고도 꺼진다.** WiFi 가 잠깐 끊기거나(절전·로밍·재접속) 오래 유휴
  상태면 Android 가 토글을 내린다. 그래서 데몬이 죽은 뒤 재부트스트랩이 필요해진 시점엔
  토글까지 꺼져 있어서 "무선 디버깅을 다시 켜야 하는" 모습으로 보였다. 반대로 **데몬이
  살아있으면 토글이 꺼져 있어도 Binder 재부착만으로 끝나서 아무 상관이 없다.**
- **토글 off 가 adbd 를 재시작시켜 데몬을 죽이는지는 미측정이다.** 정황(이 속성을 바꾸는
  모든 방법이 `stop adbd → setprop → start adbd` 형태)은 그렇다고 말하지만 재현은 못 했다.
  에뮬레이터(API 37)에서는 `settings put global adb_wifi_enabled` 가 `dumpsys adb` 의
  `enabled` 만 바꾸고 `tls_port=0` 에 리스너도 안 열린다(`ss -ltn` 에 5555 하나뿐) — 무선
  디버깅 TLS 서버 자체가 안 떠서 검증이 불가능하다. shell 은 `persist.adb.tls_server.enable`
  를 SELinux 로 읽지도 쓰지도 못하고, production 빌드라 `adb root` 로 adbd 를 재시작할 수도
  없다. 실기기 + USB 케이블이 있어야 측정된다.

### 상주 알림이 연결 상태를 알려준다

게임을 하는 동안 사용자가 보는 건 오버레이뿐이라, 로그 연결이 끊긴 걸(재부팅 후 무선 디버깅이
꺼진 경우 등) 앱을 열기 전엔 알 방법이 없었다 — 수치가 안 늘어나는 걸로만 눈치채야 했다. 그래서
이미 떠 있는 포그라운드 알림 문구를 상태에 따라 바꾼다.

| 상태 | 접힌 알림 문구 |
| --- | --- |
| 로그 연결 안 됨 | `연결 끊김 — 무선 디버깅을 켜주세요` (+ 펼치면 상세 안내) |
| 연결됐지만 게임을 못 찾음 | `게임 실행 대기 중` |
| 폴링 중 | `로그 감시 중` |

**접힌 알림 한 줄을 넘기지 말 것.** 실측상 한글 **22 자쯤에서 잘린다** — 처음엔
`게임 로그에 연결되지 않음 — 무선 디버깅을 켜고 앱을 열어주세요` 로 썼다가 에뮬레이터에서
`…무선 디버깅을 켜고…` 로 잘려 **정작 할 일이 말줄임표 뒤로 사라지는** 걸 확인하고 줄였다.
긴 안내는 `BigTextStyle` 로 넘겨 펼쳤을 때만 보이게 한다.

"앱을 열어주세요" 같은 문구도 뺐다 — 알림을 누르면 앱이 열리므로 군더더기다.

> **안 넣은 것**: "앱이 삭제되면 데몬 즉시 종료". `-Djava.class.path` 의 APK 경로가 사라졌는지로
> 판단하려 했는데, **앱 업데이트도 APK 경로를 바꾼다** — 인앱 업데이트를 쓰는 이 앱에서는 업데이트
> 때마다 데몬이 죽어 매번 WiFi 재부트스트랩이 필요해진다. 삭제는 만료에 맡긴다(그 사이
> 데몬은 `/proc` 스캔만 하고, 재부팅하면 어차피 사라진다).

## 현재 HUD 설계

- 앱을 접은 상태는 원형 아이콘이 아니라 가로형 요약 바다.
- 요약 바에는 앱 버전, 맵 횟수, 현재 맵 시간, 총 수익, 총 시간, 시간당 수익, 이번 맵 수익을 표시한다.
- 재생/일시정지 버튼은 탭으로 동작하고, 초기화 버튼은 실수 방지를 위해 1초 롱프레스에서만 동작한다.
- 요약 바의 빈 공간을 짧게 탭하면 기존의 상세 HUD를 열고, 길게 누른 채 드래그하면 위치를 옮긴다.
- 현재 배포 버전은 `app/build.gradle.kts`의 `versionName`과 최신 GitHub Release tag가 기준이다.
