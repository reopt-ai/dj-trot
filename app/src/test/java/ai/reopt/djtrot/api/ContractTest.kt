package ai.reopt.djtrot.api

import ai.reopt.djtrot.car.Destination
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.GearState
import ai.reopt.djtrot.dj.toInput
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DJ 트롯 서버와 주고받는 모양(`trot-song/1`, 브리프, 드라이브 노래)을 읽고 쓰는지. */
class ContractTest {
    private val json = DjTrotApi.json

    @Test fun `노래 모양을 읽고 모르는 키는 무시한다`() {
        val song = json.decodeFromString(
            TrotSong.serializer(),
            """
            {"schema":"trot-song/1","id":"380faad4-0000-4000-8000-000000000001","title":"그 목소리","artist":"마스터트로트 악단",
             "style":"ballad","topic":"love","voice":"female","labels":{"style":"트로트 발라드","topic":"사랑","voice":"여자 목소리"},
             "durationSeconds":177.5,"audio":{"url":"https://cdn.example.com/songs/x.mp3","contentType":"audio/mpeg"},
             "cover":null,"lyrics":{"title":"그 목소리","lines":["다 잊었다 큰소리쳤지"],"sections":{"verse":[0,1]}},
             "lyricSync":{"state":"ready","lines":[{"text":"다 잊었다 큰소리쳤지","start":11.36,"end":18.34}]},
             "provenance":{"kind":"house","createdAt":"2026-10-01","later":"added key"},"later":"added key"}
            """.trimIndent(),
        )
        assertEquals("트로트 발라드", song.labels.style)
        assertEquals(1, song.lyricSync.lines.size)
        assertEquals("house", song.provenance.kind)
    }

    @Test fun `음성 없는 DJ 브리프도 읽는다`() {
        val brief = json.decodeFromString(
            DjBrief.serializer(),
            """{"script":"대전까지 12분 남았네요.","song":{"topic":"mother","style":"ppongjjak","voice":"female","story":"청주에서 대전으로","title":"대전 가는 길"},"voiceUrl":null,"source":"template"}""",
        )
        assertNull(brief.voiceUrl)
        assertEquals("mother", brief.song.topic)
    }

    @Test fun `음성 조각을 주는 DJ 브리프와 음성 팩을 읽는다`() {
        val brief = json.decodeFromString(
            DjBrief.serializer(),
            """{"script":"아아... 비가, 오네요... '고향역', 띄워 드립니다.","song":{"topic":"life","style":"dance","voice":"female","story":"저녁길","title":"저녁길"},
               "voiceUrl":null,"voiceClips":["/api/v1/dj-trot/dj/voice/a","/api/v1/dj-trot/dj/voice/b"],"source":"template"}""",
        )
        assertEquals(2, brief.voiceClips.size)
        val pack = json.decodeFromString(VoicePack.serializer(), """{"version":"abc","clips":[{"id":"open.rain.1","text":"아아... 비가, 오네요...","url":"/api/v1/dj-trot/dj/voice/a"}],"later":1}""")
        assertEquals("open.rain.1", pack.clips.single().id)
        assertNull(json.decodeFromString(VoicePack.serializer(), """{"version":null,"clips":[]}""").version)
    }

    @Test fun `멘트 길이와 날씨는 정해졌을 때만 보낸다`() {
        val plain = json.encodeToString(BriefRequest.serializer(), BriefRequest(DriveContext().toInput(LocalTime.NOON), voice = false))
        assertFalse("ment" in plain)
        // DJ를 정하지 않으면 보내지 않고(서버가 기본 DJ로 답한다), 고르면 그 ID를 보낸다.
        assertFalse("\"dj\"" in plain)
        val mari = json.encodeToString(BriefRequest.serializer(), BriefRequest(DriveContext().toInput(LocalTime.NOON), voice = true, dj = "mari"))
        assertTrue("\"dj\":\"mari\"" in mari)
        // 서버 설정의 DJ 목록 — 없으면(예전 서버) 빈 목록이다.
        val config = json.decodeFromString(DjConfig.serializer(), """{"djs":[{"id":"hwanggu","name":"DJ 황","description":"다방 DJ","ready":true},{"id":"mari","name":"DJ 마리","ready":false}]}""")
        assertEquals(listOf("hwanggu" to true, "mari" to false), config.djs.map { it.id to it.ready })
        assertTrue(json.decodeFromString(DjConfig.serializer(), "{}").djs.isEmpty())
        assertFalse("weather" in plain)
        assertFalse("scene" in plain)
        // 🔴 즐겨찾기의 집·회사는 서버로 가지 않는다.
        assertFalse("kind" in plain)
        val rainy = json.encodeToString(BriefRequest.serializer(), BriefRequest(DriveContext(raining = true, ahead = ai.reopt.djtrot.car.RoadAhead.TUNNEL).toInput(LocalTime.NOON), voice = true, ment = "short"))
        assertTrue("\"ment\":\"short\"" in rainy)
        assertTrue("\"weather\":\"rain\"" in rainy)
        assertTrue("\"scene\":\"tunnel\"" in rainy)
    }

    @Test fun `주행 정보는 계약된 키와 길이만 보낸다`() {
        val drive = DriveContext(
            drivingMode = "COMFORT",
            currentAddress = "경기도 성남시 수정구 시흥동",
            destination = Destination("가".repeat(120), remainMinutes = 12),
        )
        val input = drive.toInput(LocalTime.of(8, 5))
        assertEquals("08:05", input.localTime)
        assertNull(input.drivingMode)
        assertEquals(80, input.destination!!.name.length)
        val encoded = json.encodeToString(BriefRequest.serializer(), BriefRequest(input, voice = true))
        // 🔴 서버 스키마가 strict — 모르는 키를 보내면 400이다. 값이 없는 키는 아예 보내지 않는다.
        assertFalse("latitude" in encoded)
        assertFalse("\"address\":null" in encoded)
        assertTrue("\"spoken\":\"\"" in encoded)
        assertTrue("\"gear\":\"UNKNOWN\"" in encoded)
        assertFalse("moving" in encoded)
    }

    @Test fun `지금 곡의 ID는 신청곡일 때만 보낸다`() {
        val input = DriveContext().toInput(LocalTime.NOON)
        val house = json.encodeToString(BriefRequest.serializer(), BriefRequest(input, voice = false, NowPlaying("고향역", "마스터트로트 악단")))
        // 🔴 서버 nowPlaying도 strict이고 songId는 uuid만 — 운영곡에는 키 자체를 보내지 않는다.
        assertFalse("songId" in house)
        val id = "14056dd5-375b-4c93-a20f-5e1f3b073819"
        val made = json.encodeToString(BriefRequest.serializer(), BriefRequest(input, voice = false, NowPlaying("힘내라 오늘도", "DJ 트롯", id)))
        assertTrue("\"songId\":\"$id\"" in made)
    }

    @Test fun `달리는 중인지는 기어와 주행 제한으로 정한다`() {
        assertEquals(false, DriveContext(gear = GearState.PARK).toInput(LocalTime.NOON).moving)
        assertEquals(true, DriveContext(gear = GearState.DRIVE, restricted = true).toInput(LocalTime.NOON).moving)
        assertNull(DriveContext(gear = GearState.DRIVE).toInput(LocalTime.NOON).moving)
        assertEquals("DRIVE", DriveContext(gear = GearState.DRIVE).toInput(LocalTime.NOON).gear)
    }

    @Test fun `토큰 잔액은 토큰 혜택에서 읽는다`() {
        val snapshot = json.decodeFromString(
            BenefitSnapshot.serializer(),
            """{"checkedAt":"2026-10-23T00:00:00Z","benefits":[{"id":"token","title":"드라이브 제작 토큰","kind":"credits","balance":3,"adjustment":0}]}""",
        )
        assertEquals(3, snapshot.tokens)
    }

    @Test fun `드라이브 노래 음원은 상대 주소이고 토큰이 필요하다`() {
        val api = DjTrotApi("http://10.0.2.2:8790/api/v1/dj-trot/")
        val drive = api.absolute("/api/v1/dj-trot/songs/f6fc176a-d620-43ec-a5dc-4cc7feec386e/audio")
        assertEquals("http://10.0.2.2:8790/api/v1/dj-trot/songs/f6fc176a-d620-43ec-a5dc-4cc7feec386e/audio", drive)
        assertTrue(api.needsToken(drive))
        // 제공 목록은 공개 절대 주소 — 토큰을 붙이지 않는다.
        val house = api.absolute("https://cdn.example.com/songs/x.mp3")
        assertEquals("https://cdn.example.com/songs/x.mp3", house)
        assertFalse(api.needsToken(house))
    }
}
