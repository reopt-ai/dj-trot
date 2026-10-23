# DJ 트롯 (dj-trot)

Pleos Connect(현대자동차그룹 차량 인포테인먼트, Android Automotive OS 기반) 위에서 동작하는 AI 트로트 DJ 앱.
차가 아는 것(목적지·지나는 지역·주행 상태·동승자·말로 넣은 사연)으로 **이 드라이브만의 트로트를 AI가 새로 만들어** DJ가 틀어준다.

- 이 저장소는 차량 클라이언트만 담는다. 노래 목록과 노래 만들기는 DJ 트롯 서버 API를 부른다.
- 출품: [현대자동차그룹 차량용 앱 개발 공모전](https://appdevchallenge.auto-hmg.io/) — 요건 정리는 [docs/contest.md](docs/contest.md)

## 컨셉

| 차량 신호 (Pleos SDK) | 노래에 들어가는 것 |
| --- | --- |
| NaviHelper — 목적지·도착 예정 | 가사 소재, "도착 전에 완성" 타이밍 |
| FusedLocation — 지나는 지역 | 지역 연고 가사 |
| Vehicle — 정체·장거리·야간·충전 중 | 분위기 선택(신나는·애절한·정통) |
| SpeechToText — 한마디 사연 | 노래 주문에 넣는 사연 |
| TextToSpeech — DJ 멘트 | 생성 대기(약 2분)를 채우는 진행, 곡 소개 |

흐름: 선택지와 사연으로 노래를 주문하고, 완성되면 "방금 만든 노래"로 재생한다. 기다리는 동안에는 제공 목록의 노래를 튼다.

## 구조 (예정)

```
app/            Pleos Connect(AAOS) 앱 — Kotlin, Media3 MediaLibraryService + MediaSession
docs/           공모전 요건·기획서·설계 메모
```

Pleos 음악 앱 필수 요건: `android:appCategory="audio"`, MediaLibraryService(또는 MBS)+MediaSession, 스티어링 휠 제어, 오디오 포커스 반납, 자동재생 금지, 서브카테고리 Music 명시, CarUxRestrictions 적용.

## 일정

| 날짜 | 항목 |
| --- | --- |
| 2026-10-28 | 온라인 설명회 |
| 2026-11-15 | 기획서(접수양식) 이메일 접수 마감 |
| 2026-11-23 | 1차 서류심사 발표 |
| 2026-12-03~04 | 2차 PT 심사 — 동작하는 앱 필수 |
| 2026-12-18 | 최종 발표 |
| 2027-02 | 전시·시상 (전시용 APK 필수) |
