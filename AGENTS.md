# AGENTS.md

이 저장소에서 일하는 코딩 에이전트를 위한 안내입니다.

DJ 트롯은 Pleos Connect(Android Automotive OS)용 음악 앱입니다. 저장소에는 차량 클라이언트만 있고 서버는 따로 있습니다. 사람이 읽는 안내는 [README.md](README.md), 작업 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)가 정본입니다.

## 명령

JDK 17이 필요합니다(`JAVA_HOME`). 모듈은 `:app` 하나입니다. 빌드가 길어지면 `caffeinate -dims`로 감쌉니다.

```bash
./gradlew :app:testDebugUnitTest                                              # JVM 단위 테스트 전체
./gradlew :app:testDebugUnitTest --tests 'ai.reopt.djtrot.dj.DjScriptTest'    # 클래스 하나
./gradlew :app:testDebugUnitTest --tests '*DjScriptTest.DJ 자신의*'            # 한국어 백틱 이름은 와일드카드로
./gradlew :app:lintDebug                                                      # Android lint, 경고 하나도 실패
./gradlew :app:installDebug -Pdjtrot.api=http://10.0.2.2:8790/api/v1/dj-trot  # 에뮬레이터에 설치(목 서버를 볼 때)
adb shell am start -n ai.reopt.djtrot/.ui.MainActivity
uv run --no-project tools/mock_djtrot.py --audio-dir <mp3 폴더> --upstream <DJ 트롯 서버> --fake-songs 45
```

- `-q`로 돌리면 성공 시 아무것도 찍히지 않습니다. 결과는 `app/build/test-results/testDebugUnitTest/*.xml`에서 확인합니다.
- Android lint는 경고도 실패로 봅니다. 일부러 남기는 항목은 `app/lint.xml`에 이유와 함께 적고, 코드로 고칠 수 있으면 고칩니다. `CI=true`로 빌드하면 Kotlin 컴파일 경고도 실패입니다. CI(`.github/workflows/ci.yml`)는 비용 때문에 수동 실행(`workflow_dispatch`)만 켜 두었으니, 변경 뒤에는 `CI=true ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`를 로컬에서 돌립니다. 자동 실행을 다시 켜지 않습니다. ktlint·detekt는 없고 Kotlin 공식 스타일(`kotlin.code.style=official`)을 따릅니다.
- `tools/`는 `uvx ruff@0.17.2 check tools/`를 통과해야 합니다.
- 빌드 속성: `djtrot.api`(서버 주소, 기본은 운영), `djtrot.pleosClientId`·`djtrot.pleosClientSecret`(Pleos TTS 서버 모드)이고 모두 `BuildConfig`로 들어갑니다. Client ID/Secret은 저장소가 아니라 `~/.gradle/gradle.properties`에만 둡니다.
- 디버그 빌드만 `10.0.2.2`·`localhost`·`127.0.0.1`로 평문 HTTP를 허용합니다(`app/src/debug/`).
- 에뮬레이터 설치, 시간대, 창 크기 바꾸기, Virtual Console은 README "시작하기"에 있습니다. VHAL 때문에 콜드 부트가 필요합니다.

## 🔴 지켜야 할 것

- **노래 만들기(`POST /songs`)는 토큰과 서버 비용이 듭니다.** 사용자가 승인하지 않으면 실제 서버를 상대로 화면의 "노래 만들기"를 누르거나 이 API를 부르지 않습니다. 화면 시험은 목 서버 `--fake-songs`로 합니다. 이 모드에서는 요청이 서버로 가지 않습니다.
- **비공개 저장소지만 공개해도 되는 수준으로 씁니다.** 서버에 대해서는 차가 부르는 공개 API의 모양까지만 씁니다. 서버의 노래 생성 방식, 외부 서비스 이름, 서버 코드 경로, 운영 설정·장애는 코드 주석, 문서, 테스트 픽스처, 커밋 메시지 어디에도 쓰지 않습니다.
- **커밋 메시지는 `.githooks/commit-msg`가 검사합니다.** 켜는 명령은 `git config core.hooksPath .githooks`입니다. 형식은 `<type>(<scope>): <subject>`이고 영어 명령형, 소문자 시작, 마침표 없이 72자 이내로 씁니다. type과 scope 목록은 CONTRIBUTING.md 표를 따르고, scope는 패키지 이름(`api` `account` `car` `dj` `playback` `ui`)과 `tools` `deps` `release`입니다.
- 주석, 문서, 화면 문구는 한국어로, 식별자와 커밋 메시지는 영어로 씁니다. 주석 표기는 🔑 핵심 결정, 🔴 어기면 사고가 나는 규칙(Pleos 요건, 운전 안전), ⚠️ 주의입니다.

## 구조

루트 패키지는 `ai.reopt.djtrot`이고 그 아래에 `api` `account` `car` `dj` `playback` `ui`가 있습니다. DI 프레임워크는 쓰지 않습니다. 그림과 설계 이유는 [docs/architecture.md](docs/architecture.md)에 있습니다.

**`DjTrotApp`이 전역 객체를 만듭니다.** `api`, `account`, `catalog`, `orders`, `car`, `voice`를 lazy로 만들고, 화면과 재생 서비스가 함께 보는 `dj: MutableStateFlow<DjState>`를 둡니다. 둘은 같은 프로세스에서 이 객체로 상태를 나눕니다.

**재생은 서비스가 맡습니다.** `playback/DjTrotLibraryService`가 ExoPlayer와 MediaLibrarySession을 갖고, 화면(`ui/MainActivity`)은 `MediaController`로만 붙습니다. 시스템 미디어, 스티어링 휠, Gleo 음성 검색도 같은 세션에 붙습니다. Pleos Music/Audio 요건 가운데 자동 재생 금지, 오디오 포커스, `onTaskRemoved` 순서, Started 상태는 이 클래스 KDoc에 모여 있으니 고칠 때 깨지 않게 합니다.

**DJ 멘트는 서비스가 곡이 실제로 울릴 때 냅니다.** `onIsPlayingChanged`와 `onMediaItemTransition`에서 `announce()`를 부릅니다. 서버 DJ 브리프(`/dj/brief`, 8초 제한)를 먼저 쓰고, 실패하면 `dj/DjScript`(기기 템플릿)로 바꿉니다. 서버가 준 음성 클립은 멘트가 곡 제목을 말할 때만 음악을 줄인 위에 틀고, 아니면 `dj/DjVoice`(Pleos TTS, 안 되면 Android TTS)로 읽습니다. 브리프가 준 신청곡 제안은 `DjState.suggestion`에 들어가고 20분이 지나면 버립니다. 제안이 없거나 버려지면 화면이 `dj/StoryBuilder`로 기기에서 만든 제안을 씁니다.

**노래 목록과 메타데이터는 `playback/Catalog`가 쥡니다.** 브라우즈 트리(`root`·`picks`·`latest`·`styles`·`style:{id}`·`mine`)와 메모리 곡 레지스트리입니다. 미디어 ID는 `song:` 접두사를 붙이고, `resolve()`는 목록에서 한 번 본 곡만 압니다. 곡 정보(ID, 장르, 내가 만든 곡인지, 만든 시각)는 `MediaMetadata.extras`의 `djtrot.*` 키로 화면과 서비스에 전해집니다. 곡을 고르면 그 곡이 있던 목록을 큐로 이어 틉니다(`queueFor`).

**노래 주문은 앱 범위에서 돕니다.** `dj/SongOrders`는 `POST /songs`를 requestKey로 멱등하게 보내고(응답 없이 실패한 주문을 다시 누르면 같은 키), 5초마다 확인합니다. 완성되면 `catalog.register()` 뒤 `ready`로 내보내고, 서비스가 그 곡을 지금 곡 다음에 넣습니다. 화면을 닫아도 끝까지 지켜봅니다.

**계정은 휴대폰 페어링으로 받습니다.** `account/DeviceVault`가 기기 자격을 Android Keystore로 암호화해 두고, `account/DeviceAccount`가 1시간 세션 토큰을 메모리에만 둡니다. 로그인 호출은 `account.authorized { token -> … }`로 감쌉니다. 401이면 한 번 새로 받아 다시 부르고, 자격이 철회되면 연결 전 상태로 돌아갑니다. 드라이브 노래 음원(`/songs/…`)에만 서비스의 `authorizedSource()`가 Bearer를 붙입니다.

**서버 계약은 `api/`에 있습니다.** `DjTrotApi`(OkHttp, 코루틴)와 `Models.kt`의 직렬화 모델입니다. 노래는 출처와 상관없이 `trot-song/1` 한 모양입니다. 읽을 때는 `ignoreUnknownKeys`로 받지만, **보낼 때는 서버가 모르는 키를 400으로 거절합니다.** 그래서 `explicitNulls=false`로 null 키를 빼고, 요청 모델에 필드를 더하려면 서버 계약이 먼저 바뀌어야 합니다. `ContractTest`가 이 모양을 지킵니다.

**차량 신호는 `car/`에 있습니다.** `CarSignals`가 Pleos Vehicle, NaviHelper, FusedLocation과 `AaosCar`(GEAR_SELECTION, CarUxRestrictions)를 하나의 `StateFlow<DriveContext>`로 모읍니다. SDK마다 따로 실패할 수 있으니 하나가 안 되면 그 값만 비웁니다. `DriveContext`는 안드로이드에 의존하지 않는 순수 모델이라 JVM 테스트로 검사합니다. 서버로 갈 때는 `dj/DriveInputs.kt`의 `toInput()`으로만 바뀌고, 위치 좌표와 식별 정보는 이 단계에서 빠집니다.

**화면은 Compose 단일 화면입니다.** `ui/DjScreen`이 창 크기로 넓은 창(두 칸, 기준 1682×1210dp)과 좁은 창(한 칸, 기준 840dp 폭)을 고르고, 기준 크기에 맞춰 `LocalDensity`를 비례 조정합니다. 주행 중 여부(`!drive.stopped || drive.restricted`)는 `LocalCalm`(`ui/Parts.kt`)으로 내려가고, 목록, 사연 본문, 가사, 애니메이션이 이 값을 보고 스스로 숨거나 멈춥니다. 새 화면 요소도 `LocalCalm`과 `DriveContext.brief()`·`noSetup`을 따라야 합니다. 색과 글자 크기는 `ui/Theme.kt`의 `DjColors`와 `DjType`(Pleos 단계)만 쓰고, 글자 대비는 4.5:1 이상입니다. 휴대폰 연결 화면 `ConnectActivity`는 `distractionOptimized`가 아니라서 주행 중에는 시스템이 막습니다.
