# 고인물 mTTD 커스텀 기준

이 저장소는 `listil/mttd`에서 가져온 개인 포크다.

## 원본과의 관계

- 원본 원격: `upstream` → `https://github.com/listil/mttd.git`
- 개인 GitHub 저장소를 만든 뒤 `origin`으로 추가한다.

```bash
git remote add origin https://github.com/GITHUB_ID/kd-mttd.git
git push -u origin main
```

원본 변경을 검토할 때는 다음을 사용한다.

```bash
git fetch upstream
git log --oneline main..upstream/main
git merge upstream/main
```

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

## 현재 HUD 설계

- 앱을 접은 상태는 원형 아이콘이 아니라 가로형 요약 바다.
- 요약 바에는 상태, 경과 시간, 총 수익, 시간당 수익, 이번 맵 수익을 표시한다.
- 짧게 탭하면 기존의 상세 HUD를 열고, 길게 누른 채 드래그하면 위치를 옮긴다.
- 현재 배포 버전은 `0.3.6-kd`다.
