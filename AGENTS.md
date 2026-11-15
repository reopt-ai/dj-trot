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
uv run --no-project tools/trigger.py set gear=DRIVE rain=on jam=12 --next     # 에뮬레이터에 없는 차량 신호를 덧씌워 DJ 멘트 시험(keys, scenario, say)
```

- `-q`로 돌리면 성공 시 아무것도 찍히지 않습니다. 결과는 `app/build/test-results/testDebugUnitTest/*.xml`에서 확인합니다.
- Android lint는 경고도 실패로 봅니다. 일부러 남기는 항목은 `app/lint.xml`에 이유와 함께 적고, 코드로 고칠 수 있으면 고칩니다. `CI=true`로 빌드하면 Kotlin 컴파일 경고도 실패입니다. CI(`.github/workflows/ci.yml`)는 비용 때문에 수동 실행(`workflow_dispatch`)만 켜 두었으니, 변경 뒤에는 `CI=true ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`를 로컬에서 돌립니다. 자동 실행을 다시 켜지 않습니다. ktlint·detekt는 없고 Kotlin 공식 스타일(`kotlin.code.style=official`)을 따릅니다.
- `tools/`는 `uvx ruff@0.17.2 check tools/`를 통과해야 합니다.
- 개발자 모드: 설정 › 앱 정보에서 버전을 일곱 번 누르면 켜지고, 서버를 로컬·프로덕션으로 바꿔(앱 재시작) 연결을 확인할 수 있습니다. 로컬 주소는 `djtrot.api`가 운영이 아니면 그 주소, 운영이면 `djtrot.localApi`(기본 `http://10.0.2.2:8787/…`)입니다. 자격 보관함과 음성 팩은 서버마다 따로입니다.
- 빌드 속성: `djtrot.demo`(화면의 설정 탭 — 시연, 기본 켜짐 — 끄려면 `-Pdjtrot.demo=false`), `djtrot.api`(서버 주소, 기본은 운영), `djtrot.pleosClientId`·`djtrot.pleosClientSecret`(Pleos TTS 서버 모드)이고 모두 `BuildConfig`로 들어갑니다. Client ID/Secret은 저장소가 아니라 `~/.gradle/gradle.properties`에만 둡니다.
- 디버그 빌드만 `10.0.2.2`·`localhost`·`127.0.0.1`로 평문 HTTP를 허용합니다(`app/src/debug/`).
- 에뮬레이터 설치, 시간대, 창 크기 바꾸기, Virtual Console은 README "시작하기"에 있습니다. VHAL 때문에 콜드 부트가 필요합니다.

## 🔴 지켜야 할 것

- **노래 만들기(`POST /songs`)는 토큰과 서버 비용이 듭니다.** 사용자가 승인하지 않으면 실제 서버를 상대로 화면의 "노래 만들기"를 누르거나 이 API를 부르지 않습니다. 화면 시험은 목 서버 `--fake-songs`로 합니다. 이 모드에서는 요청이 서버로 가지 않습니다.
- **공개 저장소입니다. 올린 것은 누구나 봅니다.** 서버에 대해서는 차가 부르는 공개 API의 모양까지만 씁니다. 서버의 노래 생성 방식, 외부 서비스 이름, 서버 코드 경로, 운영 설정·장애는 코드 주석, 문서, 테스트 픽스처, 커밋 메시지 어디에도 쓰지 않습니다.
- **커밋 메시지는 `.githooks/commit-msg`가 검사합니다.** 켜는 명령은 `git config core.hooksPath .githooks`입니다. 형식은 `<type>(<scope>): <subject>`이고 영어 명령형, 소문자 시작, 마침표 없이 72자 이내로 씁니다. type과 scope 목록은 CONTRIBUTING.md 표를 따르고, scope는 패키지 이름(`api` `account` `car` `dj` `playback` `ui`)과 `tools` `deps` `release`입니다.
- 주석, 문서, 화면 문구는 한국어로, 식별자와 커밋 메시지는 영어로 씁니다. 주석 표기는 🔑 핵심 결정, 🔴 어기면 사고가 나는 규칙(Pleos 요건, 운전 안전), ⚠️ 주의입니다.

## 구조

루트 패키지는 `ai.reopt.djtrot`이고 그 아래에 `api` `account` `car` `dj` `playback` `ui`가 있습니다. DI 프레임워크는 쓰지 않습니다. 그림과 설계 이유는 [docs/architecture.md](docs/architecture.md)에 있습니다.

**`DjTrotApp`이 전역 객체를 만듭니다.** `api`, `account`, `catalog`, `orders`, `car`, `voice`를 lazy로 만들고, 화면과 재생 서비스가 함께 보는 `dj: MutableStateFlow<DjState>`를 둡니다. 둘은 같은 프로세스에서 이 객체로 상태를 나눕니다.

**재생은 서비스가 맡습니다.** `playback/DjTrotLibraryService`가 ExoPlayer와 MediaLibrarySession을 갖고, 화면(`ui/MainActivity`)은 `MediaController`로만 붙습니다. 시스템 미디어, 스티어링 휠, 미디어 검색도 같은 세션에 붙습니다(Gleo가 Media3 검색으로 붙는 방식은 Pleos가 준비 중). Pleos Music/Audio 요건 가운데 자동 재생 금지, 오디오 포커스, `onTaskRemoved` 순서, Started 상태, 쓰지 않을 때 내려놓기(BG→CCH), 음성 앞 묵음은 이 클래스 KDoc에 모여 있으니 고칠 때 깨지 않게 합니다. 노래가 나오지 않은 채 5분(끝났거나 틀기 전이면 1분)이 지나면 서비스가 재생기를 멈추고(`retire()`) Started 상태를 풉니다. 그래서 화면의 재생 버튼은 재생기가 IDLE이면 `prepare()`부터 부릅니다.

**DJ 멘트는 서비스가 곡이 실제로 울릴 때 냅니다.** `onIsPlayingChanged`와 `onMediaItemTransition`에서 `announce()`를 부릅니다. 서버 DJ 브리프(`/dj/brief`, 8초 제한)를 먼저 씁니다. 브리프가 음성 조각(`voiceClips`)을 주면 순서대로 이어 틀고, 받지 못하면 받아 둔 음성 팩(`dj/VoicePackStore`)에서 `dj/VoiceLines` 규칙으로 조각을 고르며, 팩도 없으면 `dj/DjScript`(기기 템플릿)로 바꿉니다. 조각 ID와 고르는 규칙은 서버와의 계약이라 한쪽만 바꾸지 않습니다. 길게 말하는 것은 첫 곡과 그 뒤 세 곡마다이고, 출발·도착·경유 인사도 받아 둔 조각이 있으면 그 목소리로 냅니다. 🔑 DJ의 목소리는 서버가 만든 음성이 먼저입니다. 브리프가 음성을 주지 못하면(팩이 꺼짐, 합성 상한) 받아 둔 음성 팩으로 말하고, 통문장 음성(`voiceUrl`)이 곡 제목을 말하지 않으면 받아 둔 곡 소개 조각을 뒤에 잇습니다. 그 음성이 하나도 없을 때만 `dj/DjVoice`(Pleos TTS, 안 되면 Android TTS)로 읽습니다. 기기 TTS로만 나오는 말을 새로 만들지 말고 서버 음성 팩에 조각을 더합니다(노래 완성 `song.ready`, 광고 `ad.outro.*`처럼). 어느 쪽이든 말하는 동안 음악을 줄이고(`DjVoice.speaking`, 크기는 `duck()`이 서서히 바꿉니다), 재생이 눌리면(사용자 정지, 일시 포커스 손실) 함께 멈춥니다. Pleos TTS `speak`의 두 번째 인자는 `isFinal`이라 항상 true로 부릅니다. 브리프가 준 신청곡 제안은 `DjState.suggestion`에 들어가고 20분이 지나면 버립니다. 제안이 없거나 버려지면 화면이 `dj/StoryBuilder`로 기기에서 만든 제안을 씁니다. 기기 멘트는 `DjScript.remarks()`가 지금 길에서 할 말을 급한 것부터 모으고, 앞의 두 가지를 곡마다 번갈아 씁니다. 길 안내 시작(`car.departures`), 경유지(`car.waypoints`), 도착(`car.arrivals`)에는 곡과 따로 한마디 합니다.

**DJ는 고를 수 있습니다.** 서버 설정(`/config`의 `djs`)이 DJ 목록과 준비 여부를 알려 주고, 고른 DJ의 ID(`DjTrotApp.djId`, 기기에만 저장)를 브리프에 함께 보냅니다. 받아 둔 음성 팩은 DJ마다 폴더가 따로이고(`DjTrotApp.voicePack`이 지금 DJ의 것을 줌), 조각 ID와 고르는 규칙은 DJ와 무관하게 같습니다. 준비되지 않은 DJ는 고를 수 없고, 고른 DJ가 서버에서 내려가면 기본 DJ로 돌아갑니다.

**광고는 장소에 맞으면 다음 곡으로 끼웁니다.** `dj/Ads`가 서버의 광고 목록(`/ads`)을 받아 두고 `AdMatcher`가 지금 장소와 견줍니다(낱말, 목적지 이름의 낱말, 좌표 반경 가운데 하나라도 맞으면). 🔴 판단은 기기 안에서 하고, 좌표(`DriveContext.location`)는 서버로 보내지도 저장하지도 않습니다. 재생 서비스는 노래가 나오는 중에만 광고 음악을 지금 곡 다음에 끼우고, 광고에서 다음 곡으로 저절로 넘어갔을 때만 "끝까지 들었다"고 서버에 알립니다(건너뛰면 세지 않음). 세 번이면 생성권 한 장이고 규칙은 서버가 정합니다.

**말로 넣는 사연은 `dj/StoryMic`이 듣습니다.** Pleos SpeechToText로 한 번 듣고 `DjTrotApp.wish()`가 `DjState.spoken`에 넣습니다. 이 말은 드라이브 맥락의 `spoken`으로 브리프와 노래 주문에 함께 가고, 20분이 지나거나 노래를 맡기면 지웁니다. 듣는 동안 서비스가 DJ 목소리를 멈추고 음악을 줄입니다.

**노래 목록과 메타데이터는 `playback/Catalog`가 쥡니다.** 브라우즈 트리와 메모리 곡 레지스트리입니다. 트리의 모양은 `playback/MediaPath.kt`에 한 번만 적혀 있습니다. 루트 아래 네 폴더 `Shelf`(추천·새 노래·장르·내 노래)는 미디어 센터의 탭이자 화면의 노래 고르기 탭입니다. 미디어 ID 문자열은 `MediaPath.parse()`와 `MediaPath.id`로만 읽고 만듭니다. 탭을 늘리지 말고 폴더 아래에 둡니다. `resolve()`는 목록에서 한 번 본 곡만 압니다. 곡 정보(ID, 장르, 내가 만든 곡인지, 만든 시각)는 `MediaMetadata.extras`의 `djtrot.*` 키로 화면과 서비스에 전해집니다. 화면에서 곡을 고르면 보이는 목록을 통째로 큐로 보내고, 미디어 센터처럼 곡 하나만 오면 그 곡을 마지막으로 보여 준 목록을 이어 틉니다(`queueFor`).

**노래 주문은 앱 범위에서 돕니다.** `dj/SongOrders`는 `POST /songs`를 requestKey로 멱등하게 보내고(응답 없이 실패한 주문을 다시 누르면 같은 키), 3초마다 확인합니다(만드는 단계와 제목·가사 몇 줄을 받아 트롯생성 칸의 제작 중계가 보여 줍니다). 완성되면 `catalog.register()` 뒤 `ready`로 내보내고, 서비스가 그 곡을 지금 곡 다음에 넣습니다. 화면을 닫아도 끝까지 지켜봅니다.

**계정은 휴대폰 페어링으로 받습니다.** `account/DeviceVault`가 기기 자격을 Android Keystore로 암호화해 두고, `account/DeviceAccount`가 1시간 세션 토큰을 메모리에만 둡니다. 로그인 호출은 `account.authorized { token -> … }`로 감쌉니다. 401이면 한 번 새로 받아 다시 부르고, 자격이 철회되면 연결 전 상태로 돌아갑니다. 드라이브 노래 음원(`/songs/…`)에만 서비스의 `authorizedSource()`가 Bearer를 붙입니다.

**서버 계약은 `api/`에 있습니다.** `DjTrotApi`(OkHttp, 코루틴)와 `Models.kt`의 직렬화 모델입니다. 노래는 출처와 상관없이 `trot-song/1` 한 모양입니다. 읽을 때는 `ignoreUnknownKeys`로 받지만, **보낼 때는 서버가 모르는 키를 400으로 거절합니다.** 그래서 `explicitNulls=false`로 null 키를 빼고, 요청 모델에 필드를 더하려면 서버 계약이 먼저 바뀌어야 합니다. `ContractTest`가 이 모양을 지킵니다.

**차량 신호는 `car/`에 있습니다.** `CarSignals`가 Pleos Vehicle, NaviHelper와 `AaosCar`(GEAR_SELECTION, EV_CHARGE_PORT_OPEN·CONNECTED, ENV_OUTSIDE_TEMPERATURE, CarUxRestrictions)를 하나의 `StateFlow<DriveContext>`로 모읍니다. 화면은 보이는 동안, 재생 서비스는 사는 동안 `car.hold(owner)`로 잡고 `car.release(owner)`로 놓습니다. 잡는 쪽이 없으면 리스너를 풀고 SDK 연결을 놓으므로, 신호를 읽는 새 컴포넌트도 같은 식으로 잡아야 값이 옵니다. Vehicle 값은 `checkXXXCapabilities`로 읽을 수 있는지 묻고 걸며, 주지 않는 값은 `DriveContext.unsupported`에 적습니다. 어느 API를 어떻게 쓰는지의 전체 표는 README "어떤 API를 어떻게 쓰나"가 정본이니, 신호나 호출을 더하면 그 표도 함께 고칩니다. Pleos Vehicle이 폐기 예정으로 표시한 API는 쓰지 않고 AAOS 표준 속성으로 읽습니다(`CI=true`에서 컴파일 경고가 실패입니다). SDK마다 따로 실패할 수 있으니 하나가 안 되면 그 값만 비웁니다. `DriveContext`는 안드로이드에 의존하지 않는 순수 모델이라 JVM 테스트로 검사합니다. 위치는 내비의 주소와 좌표를 받되, 좌표는 광고 장소를 기기 안에서 견주는 데만 쓰고 서버로 보내지 않습니다. 서버로 갈 때는 `dj/DriveInputs.kt`의 `toInput()`으로만 바뀌고, 식별 정보는 이 단계에서 빠집니다. 장면(`scene`)은 서버 설정이 받는다고 알린 값(`/config`의 `scenes`, `VoiceLines.serverScenes`)만 보냅니다. 장면을 더할 때는 서버가 먼저 그 값을 알아야 합니다. 날씨(비·안개)와 길 위의 장면 하나(`VoiceLines.scene`)는 서버로 가고, 즐겨찾기(집·회사)와 동승자 유무는 계약에 자리가 있어도 보내지 않습니다. 그 말은 기기가 받아 둔 음성 조각으로 합니다. 디버그 빌드의 `debug/TriggerReceiver`(`app/src/debug`)는 `tools/trigger.py`가 보낸 값을 `CarSignals.stage()`로 실제 값 위에 덧씌웁니다. 키와 풀이는 `debug/Staging.kt`에 한 번만 적고, `DriveContext`에 멘트용 값을 더하면 여기에도 키를 더합니다. 내비 안내 직전과 방향지시등이 켜진 동안은 `DriveContext.holdTalk`가 참이고, 서비스의 `talk()`가 풀릴 때까지 목소리를 미룹니다.

**설정 탭은 메뉴 일곱 개로 시작합니다(공모전·전시용).** 자동 시연(`Demo.SCENARIOS`를 `DjTrotApp.startAuto()`가 차례로 누름), 시뮬레이션(길 위의 상황을 버튼으로 흉내), DJ 멘트 살펴보기, 차량 신호(`car/SignalBoard` — 어느 SDK가 어느 값을 주는지는 `CarSignals`·`AaosCar`의 구독과 같아야 함), DJ 설정(`DjTrotApp.fullEvery`·`duckLevel`, 기기에만 저장), 연결·계정, 앱 정보입니다. 화면은 `ui/DemoView.kt`(메뉴·시뮬레이션·멘트)와 `ui/SettingsPages.kt`(나머지)이고, 이 탭과 트롯생성 탭에서는 재생 조작부(진행 막대·재생 버튼)를 그리지 않습니다. `dj/Demo.kt`의 버튼 목록이 `DemoDrive`(순수 모델)를 바꾸고, `DjTrotApp.demo()`가 그것을 `CarSignals.stage()`로 실제 신호 위에 덧씌웁니다. 출발·경유·도착 버튼은 내비와 같은 사건을 내고, 나머지는 재생 서비스에 신호(`demoCues`)를 보내 지금 곡 위에서 그 상황으로 다시 소개하게 합니다. 멘트·음성·주행 제한은 실제와 같은 길로 나옵니다. 화면은 `ui/DemoView.kt`이고 `BuildConfig.DEMO`가 거짓이면 탭이 없습니다.

**화면은 Compose 단일 화면입니다.** `ui/DjScreen`이 창 크기로 넓은 창(두 칸, 기준 1682×1210dp)과 좁은 창(한 칸, 기준 840dp 폭)을 고르고, 기준 크기에 맞춰 `LocalDensity`를 비례 조정합니다. 주행 중 여부(`!drive.stopped || drive.restricted`)는 `LocalCalm`(`ui/Parts.kt`)으로 내려가고, 목록, 사연 본문, 가사, 애니메이션이 이 값을 보고 스스로 숨거나 멈춥니다. 새 화면 요소도 `LocalCalm`과 `DriveContext.brief()`·`noSetup`을 따라야 합니다. 색과 글자 크기는 `ui/Theme.kt`의 `DjColors`와 `DjType`(Pleos 단계)만 쓰고, 글자 대비는 4.5:1 이상입니다. 휴대폰 연결 화면 `ConnectActivity`는 `distractionOptimized`가 아니라서 주행 중에는 시스템이 막습니다.
