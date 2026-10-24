package ai.reopt.djtrot.ui

import ai.reopt.djtrot.api.LyricLine

/**
 * 지금 자막 상태.
 * - [current]: 부르는 줄. 전주·간주 중에는 비고 [next]만 있다.
 * - [sung]: 지금 줄을 얼마나 불렀는지(0~1). 노래방처럼 글자를 칠한다.
 * - [countdown]: 쉬는 중이면 다음 줄까지 남은 초. 곧 시작하면 점을 하나씩 끈다.
 */
data class LyricMoment(
    val current: String?,
    val next: String?,
    val index: Int,
    val sung: Float = 0f,
    val countdown: Double? = null,
)

/**
 * 줄별 시각으로 노래방 자막을 고른다.
 * 🔑 줄이 끝나도 다음 줄이 시작할 때까지 그 줄을 남긴다(짧은 숨에 자막이 깜빡이지 않게).
 *    간주처럼 [GAP]초 넘게 비면 비우고 다음 줄만 미리 보여 준다.
 */
object LyricTimeline {
    private const val GAP = 4.0

    fun at(lines: List<LyricLine>, seconds: Double): LyricMoment {
        if (lines.isEmpty()) return LyricMoment(null, null, -1)
        val index = lines.indexOfLast { it.start <= seconds }
        if (index < 0) return LyricMoment(null, lines.first().text, -1, countdown = lines.first().start - seconds)
        val line = lines[index]
        val next = lines.getOrNull(index + 1)
        if (seconds > line.end + GAP) return LyricMoment(null, next?.text, index, countdown = next?.let { it.start - seconds })
        val length = (line.end - line.start).coerceAtLeast(0.1)
        val sung = ((seconds - line.start) / length).coerceIn(0.0, 1.0).toFloat()
        return LyricMoment(line.text, next?.text, index, sung)
    }
}
