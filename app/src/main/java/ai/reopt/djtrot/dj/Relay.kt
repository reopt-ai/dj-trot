package ai.reopt.djtrot.dj

import ai.reopt.djtrot.api.SongProgress

/**
 * 제작 중계 — 맡긴 노래가 지금 어느 단계인지와, 그 단계에서 DJ가 건네는 한마디.
 * 🔑 단계는 접수 → 작사 → 작곡 → 마무리 네 칸이다. 서버가 단계를 알려 주지 않으면(예전 서버) 만드는 내내 작사·작곡 사이를 시간으로 가른다.
 * 🔑 한마디는 글로만 보인다 — 노래가 나오는 중이라 소리 내어 끼어들지 않는다.
 */
object Relay {
    enum class Stage(val label: String, val doing: String) {
        ACCEPT("접수", "사연을 작곡실에 전하는 중"),
        LYRICS("작사", "사연으로 가사를 쓰는 중"),
        COMPOSE("작곡", "가사에 가락을 붙이는 중"),
        FINISH("마무리", "노래를 다듬는 중"),
    }

    /** 예전 서버에서 작사가 끝났다고 보는 때(초). 작사는 보통 20초 안쪽이다. */
    private const val LYRICS_SECONDS = 25L

    fun stage(state: String, progress: SongProgress?, waitedSeconds: Long): Stage = when {
        state != "running" -> Stage.ACCEPT
        progress?.phase == "finish" -> Stage.FINISH
        progress?.phase == "compose" -> Stage.COMPOSE
        progress != null -> Stage.LYRICS
        else -> if (waitedSeconds < LYRICS_SECONDS) Stage.LYRICS else Stage.COMPOSE
    }

    /** 그 단계에서 DJ가 건네는 한마디. 제목을 알면 제목을 부른다. */
    fun comment(stage: Stage, djId: String, title: String?): String {
        val bright = djId == "mari"
        val named = title?.takeIf { it.isNotBlank() }
        return when (stage) {
            Stage.ACCEPT -> if (bright) "사연 잘 받았어요! 작곡실로 바로 달려갈게요." else "사연, 잘 받았습니다. 작곡실에 곱게 전해 드리지요."
            Stage.LYRICS -> if (bright) "지금 사연을 한 줄 한 줄 가사로 옮기고 있어요." else "지금, 그 사연을 한 줄 한 줄 가사로 옮기고 있습니다."
            Stage.COMPOSE -> when {
                named != null && bright -> "가사가 나왔어요! 제목은 ‘$named’. 이제 가락을 입혀요."
                named != null -> "가사가 나왔습니다. 제목은 ‘$named’. 이제 가락을 입힐 차례지요."
                bright -> "가사가 나왔어요! 이제 가락을 입혀요."
                else -> "가사가 나왔습니다. 이제 가락을 입힐 차례지요."
            }
            Stage.FINISH -> if (bright) "거의 다 됐어요! 마지막으로 예쁘게 다듬는 중이에요." else "거의 다 됐습니다. 마지막 손질만 남았어요."
        }
    }
}
