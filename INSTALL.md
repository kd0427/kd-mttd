# KD mTTD (Android) 빌드 가이드

소스에서 직접 빌드해서 설치하려는 개발자·기여자용 문서다. GitHub Release APK 를
받아 쓰는 일반 사용자는 **[README.md](README.md)** 의 "설치와 사용법"을 참고하면 된다
(무선 디버깅 연결, 최초 실행 설정, HUD 사용법 모두 그쪽에 있다 — 빌드한 APK 도 동일하게 동작).

---

## 1. 빌드 요구사항

| 항목 | 요구사항 |
|---|---|
| JDK | 17 |
| Android SDK | Platform 34, Build-Tools 34.0.0, **NDK 29.0.13113456, CMake 3.22.1** |
| 기기 | Android 11 (API 30) 이상, 게임 설치됨, 무선 디버깅 켜짐 |

NDK/CMake 는 무선 adb 페어링용 네이티브 코드(`app/src/main/jni`)를 빌드하는 데 쓴다. NDK 버전은
`app/build.gradle.kts` 의 `ndkVersion` 과 정확히 일치해야 한다 — 다른 버전은 헤더 조합이 깨진다.

---

## 2. 소스 빌드

```bash
cd kd-mttd

# JDK 17 경로 지정 (Homebrew openjdk@17 기준)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# Android SDK 경로 지정 (이 환경의 command-line tools 설치 경로)
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties

./gradlew :app:assembleDebug
```

산출물은 `app/build/outputs/apk/debug/kd-mttd-<versionName>-debug.apk`.

Android SDK 구성요소가 아직 없다면, Google SDK 라이선스를 검토·수락한 뒤 설치한다.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
yes | sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools --licenses
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
  "ndk;29.0.13113456" "cmake;3.22.1"
```

### 릴리스 빌드

`keystore.properties` 가 있으면 자동으로 서명된다. 없으면 서명 없이 빌드된다.

```properties
# keystore.properties (커밋 금지)
storeFile=release.jks
storePassword=...
keyAlias=mttd
keyPassword=...
```

```bash
./gradlew :app:assembleRelease
```

개인 GitHub 릴리스 업데이트 확인을 켜려면 빌드할 때 저장소를 지정한다.

```bash
./gradlew :app:assembleRelease -PupdateRepo=GITHUB_ID/REPOSITORY
```

지정하지 않으면 원본/외부 저장소를 조회하지 않는다.

---

## 3. adb로 설치

```bash
# 유선
adb install -r app-debug.apk

# 무선 디버깅
adb connect 192.168.0.x:xxxxx
adb -s 192.168.0.x:xxxxx install -r app-debug.apk
```

기기가 여러 대면 `adb devices -l` 로 확인 후 `-s` 로 지정.

설치가 제대로 됐는지 확인:
```bash
adb shell dumpsys package com.doyoon.kdmttd.debug | grep versionName
```

> debug 빌드는 패키지명이 `com.doyoon.kdmttd.debug` 라 release 빌드(`com.doyoon.kdmttd`)와
> 함께 설치할 수 있다.

---

## 4. 문제 해결 (adb 기반 진단)

### 수익이 계속 0

시세를 못 받은 경우.
```bash
adb logcat -d | grep "mTTD.Prices"
```
`loaded N prices` 가 안 보이면 네트워크 문제. 앱의 **시세** 카드에서 `아이템 수` 가
1000 이상인지 확인하고, 아니면 **새로고침** 버튼을 누른다.

### 개수는 잡히는데 금액이 안 뜸

해당 아이템에 시세가 없는 경우. 앱의 **세션** 카드 `총 수익` 옆 `(미가격 N)` 이 그 개수다.
신규 시즌 아이템이면 서버에 시세가 아직 없을 수 있다.

### `가방 정렬을 눌러주세요` 가 안 사라짐

로그를 읽고 있는지부터 확인.
```bash
adb logcat -d | grep "mTTD.Service"
```
`poller started:` 가 없다면 무선 adb 연결이 끊긴 것이다 (재부팅 후 흔함 — 재부팅하면 무선
디버깅 자체가 꺼진다). 개발자 옵션에서 무선 디버깅을 다시 켜고, WiFi 에 연결한 상태로 트래커
앱을 한 번 열면 저장된 페어링 키로 다시 붙는다 (코드 재입력 불필요).

### 오버레이가 안 보임

- "다른 앱 위에 표시" 권한 확인
- 게임이 전체화면 몰입 모드면 일부 기기에서 가려질 수 있음
- 앱의 **오버레이** 카드 → **오버레이 표시** 로 다시 띄우기

### 아무 로그도 안 나옴

게임이 로그 파일을 만들고 있는지 확인:
```bash
adb shell ls -la /sdcard/Android/data/com.xd.TLglobal/files/UE4Game/UE_game/UE_game/Saved/Logs/
```
`UE_game.log` 의 크기가 플레이 중에 계속 커져야 정상.

---

## 5. 제거

```bash
adb uninstall com.doyoon.kdmttd.debug
# 릴리스 빌드라면
adb uninstall com.doyoon.kdmttd
```

앱을 삭제하면 끝이다. 게임 쪽에는 아무것도 남기지 않는다. 개발자 옵션의 무선 디버깅도 꺼주면
된다 — shell UID 데몬은 앱과 1 시간 이상 통신하지 못하면 스스로 종료하고, 재부팅하면 바로 사라진다.
