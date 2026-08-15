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
| `bfb3345` | Shizuku 없이 무선 adb 로 붙는 direct 플레이버 | 스킵 — Shizuku 로 간다 |
| `b53cd7f` | direct 플레이버 상주 데몬 | 스킵 (위와 같은 이유) |
| `a67ccaf` | direct 데몬 재접속·자동 정리 | 스킵 (위와 같은 이유) |
| `736ef28` | 원본 0.3.5 버전 범프 | 해당 없음 — 이 포크는 버전을 따로 매긴다 |

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

## 현재 HUD 설계

- 앱을 접은 상태는 원형 아이콘이 아니라 가로형 요약 바다.
- 요약 바에는 앱 버전, 맵 횟수, 현재 맵 시간, 총 수익, 총 시간, 시간당 수익, 이번 맵 수익을 표시한다.
- 재생/일시정지 버튼은 탭으로 동작하고, 초기화 버튼은 실수 방지를 위해 1초 롱프레스에서만 동작한다.
- 요약 바의 빈 공간을 짧게 탭하면 기존의 상세 HUD를 열고, 길게 누른 채 드래그하면 위치를 옮긴다.
- 현재 배포 버전은 `app/build.gradle.kts`의 `versionName`과 최신 GitHub Release tag가 기준이다.
