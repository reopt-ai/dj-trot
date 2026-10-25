# 기여 안내

DJ 트롯에 손을 보태 주셔서 고맙습니다. 이 문서는 작업 방식과 커밋 규칙을 정리합니다. 빌드와 에뮬레이터 준비는 [README](README.md#시작하기)를 먼저 봐 주세요.

## 처음 한 번

```bash
git config core.hooksPath .githooks   # 커밋 메시지 검사를 켠다
```

`.githooks/commit-msg`가 커밋할 때마다 첫 줄을 검사하고, 규칙에 맞지 않으면 이유를 알려 주며 커밋을 멈춥니다. 들여쓰기, 줄바꿈 문자, 인코딩은 `.editorconfig`를 따르니 편집기의 EditorConfig 지원을 켜 둡니다.

## 브랜치와 PR

- `main`은 언제나 빌드되고 단위 테스트가 통과하는 상태로 둡니다.
- 작업은 `feat/<주제>`, `fix/<주제>`처럼 커밋 type을 앞에 붙인 브랜치에서 하고 PR로 합칩니다.
- PR에는 무엇을, 왜 바꿨는지와 어떻게 확인했는지를 적습니다. 화면을 바꿨다면 에뮬레이터 스크린샷을 붙입니다(넓은 창·좁은 창, 주행 중 화면이 달라지면 그것도). PR 템플릿이 확인할 항목을 보여 줍니다.
- CI는 비용을 줄이려고 자동 실행을 꺼 두었습니다. PR을 열기 전에 `CI=true ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`가 통과하는지 로컬에서 확인합니다. 큰 변경은 Actions 탭에서 CI를 수동으로 한 번 돌립니다.
- 의존성 업데이트는 Dependabot이 매주 묶어서 PR로 엽니다. 메시지는 `build(deps): …`, `ci(deps): …` 모양입니다.

## 커밋 메시지

[Conventional Commits 1.0](https://www.conventionalcommits.org/ko/v1.0.0/)을 따릅니다.

```
<type>(<scope>): <subject>

<body>

<footer>
```

예시:

```
feat(dj): introduce made songs by how long ago they were made

The DJ called a song from this morning "just made" in the evening.
Match the server brief: within an hour it is "just made", after that
"made from the story you left last time".

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

### 첫 줄

- **영어**, 명령형 현재 시제(`add`, `fix`, `drop`), **소문자로 시작**, 끝에 마침표 없음
- 전체 **72자 이내**
- `DJ 트롯`, `Pleos`처럼 고유명사는 그대로 씁니다
- 하위 호환이 깨지는 변경은 type 뒤에 `!`를 붙이고 꼬리말에 `BREAKING CHANGE:`를 적습니다

### type

| type | 쓰는 때 |
| --- | --- |
| `feat` | 사용자가 느끼는 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작은 그대로 두고 구조만 바꿈 |
| `perf` | 성능 개선 |
| `test` | 테스트만 더하거나 고침 |
| `docs` | 문서만 바꿈(README, `docs/`, 주석만 바뀐 경우 포함) |
| `build` | Gradle, 의존성, 빌드 설정 |
| `ci` | CI 설정 |
| `chore` | 그 밖의 저장소 관리(설정 파일, 훅 등) |
| `style` | 포맷만 바꿈 |
| `revert` | 이전 커밋 되돌리기 |

### scope (선택)

바뀐 코드가 한 영역에 모여 있으면 그 이름을 씁니다. 여러 영역에 걸치면 가장 중심이 되는 영역 하나를 쓰거나 생략합니다.

| scope | 범위 |
| --- | --- |
| `api` | `api/` 서버 클라이언트와 주고받는 모델 |
| `account` | `account/` 휴대폰 연결, 기기 자격, 세션 |
| `car` | `car/` Pleos SDK와 AAOS Car API 차량 신호 |
| `dj` | `dj/` DJ 멘트, 목소리, 노래 주문 |
| `playback` | `playback/` Media3 재생 서비스, 브라우즈 트리 |
| `ui` | `ui/` 화면 |
| `tools` | `tools/` 개발 도구 |
| `deps` | 의존성 버전 올리기 |
| `release` | 버전 번호, 제출용 APK |

### 본문과 꼬리말

- 본문은 **무엇보다 왜**를 씁니다. 코드를 보면 알 수 있는 내용은 줄입니다. 한 줄은 72자에서 바꿉니다.
- 함께 작업한 사람이나 AI 도구는 `Co-Authored-By: 이름 <메일>` 꼬리말로 남깁니다.

### 커밋 하나에 변경 하나

- 커밋마다 `./gradlew :app:testDebugUnitTest :app:lintDebug`가 통과해야 합니다.
- 기능 추가와 리팩터링, 포맷 정리를 한 커밋에 섞지 않습니다.

## 코드 규칙

- Kotlin 공식 스타일(`kotlin.code.style=official`)을 따릅니다.
- Android lint 경고는 고칩니다. 꼭 남겨야 하는 항목은 `app/lint.xml`에 이유와 함께 적습니다. `CI=true`로 빌드하면 Kotlin 컴파일 경고도 실패입니다.
- 주석, 문서, 화면 문구는 한국어로 쓰고 식별자와 커밋 메시지는 영어로 씁니다.
- 주석 표기: 🔑 핵심 결정, 🔴 어기면 사고가 나는 규칙(Pleos 요건, 운전 안전), ⚠️ 주의.
- 화면 글자 크기는 Pleos 단계를 옮긴 `DjType`만 씁니다. 글자와 배경의 대비는 4.5:1 이상을 지킵니다.
- 새 화면 요소는 주행 중 제한(`DriveContext.restricted`, `LocalCalm`)을 따라야 합니다. 주행 중에 긴 글, 목록 탐색, 입력을 요구하지 않습니다.

## 저장소에 넣지 않는 것

- Pleos Playground Client ID/Secret, 서명 키, 계정 토큰 같은 비밀값. 로컬 값은 `~/.gradle/gradle.properties`에 둡니다.
- DJ 트롯 서버의 내부 구현(노래를 만드는 방식, 쓰는 외부 서비스, 운영 설정). 이 저장소는 차량 앱이며, 서버에 대해서는 차가 부르는 공개 API의 모양까지만 적습니다.
- 개인 위치나 계정 정보가 담긴 로그와 스크린샷.
