# KD mTTD 릴리스 방법

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
2. 변경을 `main`에 커밋하고 푸시한다.
3. 같은 버전의 tag를 만들고 푸시한다.

```bash
git tag v0.3.5
git push origin v0.3.5
```

워크플로가 끝나면 GitHub Releases에서 `kd-mttd-0.3.5-release.apk`를 내려받을 수 있다.

> Tag의 버전과 `versionName`은 반드시 같게 유지한다. 앱 내부 업데이트 확인은 GitHub Release tag를 기준으로 비교한다.
