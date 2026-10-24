package ai.reopt.djtrot.car

/**
 * 주소를 DJ 멘트·사연에 넣을 짧은 지역 이름으로 줄인다.
 * - "경기도 성남시 수정구 시흥동" → "성남"
 * - "서울특별시 강남구 역삼동" → "서울 강남구"
 * - "강원특별자치도 양양군 현북면" → "양양"
 * - "수정구 시흥동" → "수정구"
 */
object Region {
    private val metroSuffixes = listOf("특별자치시", "특별자치도", "특별시", "광역시")
    /** 내비가 줄여 쓰는 광역시 이름("서울 강남구 역삼동"). */
    private val shortMetros = setOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종")
    private val whitespace = Regex("\\s+")

    fun label(address: String?): String? {
        val tokens = address?.trim()?.split(whitespace)?.filter { it.isNotEmpty() }.orEmpty()
        if (tokens.isEmpty()) return null

        val metroIndex = tokens.indexOfFirst { token -> metroSuffixes.any { token.endsWith(it) && token.length > it.length } }
        if (metroIndex >= 0) {
            val metro = tokens[metroIndex]
            val base = metro.removeSuffix(metroSuffixes.first { metro.endsWith(it) })
            val next = tokens.getOrNull(metroIndex + 1)
            return when {
                next == null -> base
                // 특별자치도 아래 시·군은 시·군 이름만 쓴다("제주특별자치도 제주시" → "제주").
                metro.endsWith("도") && (next.endsWith("시") || next.endsWith("군")) -> shortUnit(next)
                next.endsWith("구") || next.endsWith("군") -> "$base $next"
                else -> base
            }
        }

        if (tokens.first() in shortMetros) {
            val next = tokens.getOrNull(1)
            return if (next != null && (next.endsWith("구") || next.endsWith("군"))) "${tokens.first()} $next" else tokens.first()
        }

        tokens.firstOrNull { (it.endsWith("시") || it.endsWith("군")) && it.length >= 2 }?.let { return shortUnit(it) }
        tokens.firstOrNull { it.endsWith("구") && it.length >= 2 }?.let { return it }
        return tokens.last()
    }

    /** "성남시" → "성남", "양양군" → "양양". 두 글자 이름("중구"처럼)은 그대로 둔다. */
    private fun shortUnit(token: String) = if (token.length > 2) token.dropLast(1) else token
}
