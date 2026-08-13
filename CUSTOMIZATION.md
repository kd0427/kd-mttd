# KD mTTD 커스텀 기준

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
| 표시 이름 | KD mTTD |
| release application ID | `com.doyoon.kdmttd` |
| debug application ID | `com.doyoon.kdmttd.debug` |
| APK 이름 | `kd-mttd-<version>-<buildType>.apk` |

Kotlin 내부 패키지(`com.mttd`)는 의도적으로 유지한다. 전면 패키지명 변경은 AIDL, Protobuf, ProGuard,
소스 경로까지 함께 바뀌어 커스텀 초기 단계에는 얻는 것보다 위험이 크다.

## 업데이트 채널

기본 업데이트 채널은 꺼져 있다. 개인 GitHub 릴리스로 켜려면 빌드 시 아래 옵션을 준다.

```bash
./gradlew :app:assembleRelease -PupdateRepo=GITHUB_ID/kd-mttd
```
