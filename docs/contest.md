# 공모전과 Pleos 요건 정리

DJ 트롯은 [현대자동차그룹 차량용 앱 개발 공모전](https://appdevchallenge.auto-hmg.io/) 출품작입니다. 이 문서는 공모전 안내와 [Pleos 개발 문서](https://document.pleos.ai/)에서 이 앱에 해당하는 요건을 모았습니다(2026-10-23 확인). 각 요건을 코드에서 어떻게 지키는지는 [README](../README.md#pleos-음악-앱-요건)에 있습니다.

## 일정

| 날짜 | 항목 |
| --- | --- |
| 2026-10-21 ~ 11-15 | 기획서 이메일 접수 |
| 2026-10-28 | 온라인 설명회 |
| 2026-11-23 | 1차 서류심사 발표 |
| 2026-12-03 ~ 04 | 2차 PT 심사(동작하는 앱 필수) |
| 2026-12-18 | 최종 발표 |
| 2027-02 | 전시·시상(전시용 APK 필수) |

## 제출물

- 필수: 접수양식(기획서), 개인정보 동의서
- 선택(가산점): 시연 영상, GitHub 저장소, 숏폼 링크
- GitHub 저장소는 소스와 `apk/팀명_앱이름_release.apk`를 담고, README에 실행 방법·테스트 환경·APK 위치를 적습니다.

## 심사 항목

| 항목 | 보는 것 |
| --- | --- |
| 차량특화성 | 모바일과 다른 차량 환경만의 가치, 주행·정차 상황과 차량 기능의 결합 |
| 창의성 | 독창성, 운전자·동승자의 불편과 해결 방식의 명확성 |
| 기술 활용성 | Pleos Playground SDK·API를 핵심 기능에 썼는가 |
| 사업성 | 타깃, 예상 사용자, 재방문, 확대 계획 |
| 개발 산출물(가산점) | 시연 영상, GitHub, APK |

## 개발 환경

- Android API 28 이상, Gradle 8.0 이상, Java/Kotlin
- Pleos SDK: Vehicle(차량 상태), FusedLocation(위치), NaviHelper(길 안내 연동), TextToSpeech, SpeechToText. 차종별 제공 여부는 `checkXXXCapability`로 확인합니다.
- 에뮬레이터: [Pleos Connect Emulator 설치 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- SDK 문서: [Connect SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/), [API 레퍼런스](https://document.pleos.ai/api-reference/connect-sdk-pleos)

## Pleos Music/Audio 카테고리 요건

출처: [Music/Audio App Development Guide](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide)

- 오디오 재생이 핵심 기능이어야 합니다. 서브카테고리(Music, Podcast, Audiobook, Radio) 중 하나를 분명히 정합니다.
- `<application android:appCategory="audio">`를 선언합니다.
- MediaBrowserService 또는 Media3 `MediaLibraryService`를 구현하고(음성 검색은 Media3 권장), MediaSession으로 재생 상태와 제어를 제공합니다.
- 스티어링 휠: 재생, 일시정지, 정지, 미디어 ID 재생, 검색 재생, 가능하면 이전·다음 곡.
- 오디오 포커스: 영구 손실이면 정지하고 포커스를 돌려줍니다. 일시 손실은 일시정지, 돌아오면 재개합니다.
- 자동 재생 금지(프로필 전환 포함). 사용자의 명시적 동작이나 시스템의 재개 요청에서만 재생합니다.
- 서비스 수명: `onCreate()`에서 `startService()`, 재생할 때 포그라운드, `onTaskRemoved()`에서 재생 정지 후 `stopSelf()`.
- 음성(Gleo): Gleo Interface 또는 Media3 `MediaLibraryService` 검색 구현(최소 재생, 일시정지, 다음·이전, 검색).
- 주행 제한: `CarUxRestrictionsManager`로 받은 `CarUxRestrictions`에 따라 기능별로 제한합니다. 가사 전문과 키보드는 정차 중에만 씁니다. [Driving Restriction Development Guide](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
