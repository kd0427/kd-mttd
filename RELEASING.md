# 고인물 mTTD 릴리스 방법

`v*` 형식의 Git tag를 GitHub에 push하면 GitHub Actions가 서명된 release APK를 빌드하고
동일한 버전의 GitHub Release에 첨부한다.

## 최초 1회: GitHub Secrets 등록

릴리스 서명키는 저장소에 커밋하지 않는다. GitHub 저장소의 **Settings → Secrets and variables →
Actions**에 아래 네 Secret을 등록한다.

| Secret | 값 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | `release.jks` 파일 전체를 Base64로 변환한 값 |
| `SIGNING_STORE_PASSWORD` | 키 저장소 비밀번호 |
| `SIGNING_KEY_ALIAS` | 키 별칭 |
| `SIGNING_KEY_PASSWORD` | 키 비밀번호 |

`release.jks`가 이미 있다면 Base64 값은 macOS에서 다음처럼 만든다.

```bash
base64 < release.jks | tr -d '\n'
```

## 새 버전 배포

1. [app/build.gradle.kts](app/build.gradle.kts)의 `versionCode`를 이전보다 크게 올리고 `versionName`을 새 버전으로 바꾼다.
2. **릴리스 빌드가 통과하는지 로컬에서 먼저 확인한다.**

   ```bash
   ./gradlew :app:assembleRelease
   ```

   R8 난독화·리소스 축소·`lintVital`은 **릴리스 빌드에만** 걸린다. 디버그가 통과해도 릴리스가 깨질 수 있다 — [proguard-rules.pro](app/proguard-rules.pro)에 protobuf-lite·AIDL·kotlinx-serialization 유지 규칙이 있는 것도 실제로 그렇게 깨졌기 때문이다. 이 단계를 건너뛰고 tag부터 밀면 워크플로가 실패해야 알게 되고, 릴리스는 만들어지지 않는다.

   > ⚠️ 빌드 성공만으로는 못 잡는 것: `app_process` 가 **클래스명 문자열**로 로드하는
   > `DirectDaemonStarter` 가 R8 에 지워지면 빌드는 그대로 통과하고 **릴리스 APK 에서만**
   > shell UID 데몬이 안 뜬다 (앱은 adb-shell 폴백으로 조용히 계속 동작한다). 네이티브/데몬
   > 쪽을 건드렸다면 매핑에 이름이 살아있는지 같이 확인한다.
   >
   > ```bash
   > grep -c "^com.mttd.data.adb.starter.DirectDaemonStarter ->" app/build/outputs/mapping/release/mapping.txt
   > ```

3. 변경을 `main`에 커밋하고 푸시한다.
4. 같은 버전의 tag를 만들고 푸시한다.

```bash
git tag v0.5.8
git push origin v0.5.8
```

Tag는 `v` + `versionName` 이다. 접미사는 붙이지 않는다.

워크플로가 끝나면 GitHub Releases에서 `kd-mttd-0.5.8-release.apk`를 내려받을 수 있다.

> Tag의 버전과 `versionName`은 반드시 같게 유지한다. 앱 내부 업데이트 확인은 GitHub Release tag를 기준으로 비교한다.

## 지난 릴리스 정리

**릴리스를 쌓아두지 않는다.** 2026-08-15 에 v0.3.4~v0.5.9 의 릴리스 32 개를 지웠다. 앱의
업데이트 확인은 `/releases/latest` 만 보므로 옛 릴리스는 아무 역할이 없고, README 도 최신
버전만 배포한다고 적고 있다.

**최신 3개까지만 남긴다.** 새 버전을 낸 뒤 네 번째부터를 지운다 — 되돌릴 일이 생겼을 때
직전 두 버전의 APK 는 손에 있어야 한다.

```bash
gh release delete v0.5.9 --repo kd0427/kd-mttd --yes
```

`--cleanup-tag` 는 붙이지 않는다. **태그는 남긴다** — 어떤 커밋이 어느 버전이었는지 추적할
길이 그것뿐이다. APK 는 지우면 복구할 수 없으니 실행 전에 확인할 것.

## 릴리스 노트

워크플로는 `generate_release_notes: true`로 만들기 때문에 본문이 `Full Changelog` 한 줄뿐이다. 바뀐 내용을 직접 쓰려면 릴리스가 만들어진 뒤에 덧붙인다.

```bash
gh release edit v0.5.8 --repo kd0427/kd-mttd --notes-file notes.md
```

> `gh`는 이 저장소에서 remote가 둘(`origin`=내 포크, `upstream`=원본)이라 **`--repo kd0427/kd-mttd`를 반드시 붙인다.** 안 붙이면 원본 저장소를 향한다. 기본 저장소를 포크로 고정해 두긴 했지만(`gh repo set-default`, `CUSTOMIZATION.md` 참고) 그 설정은 `.git/config`에만 있어서 새로 clone 하면 사라진다.
