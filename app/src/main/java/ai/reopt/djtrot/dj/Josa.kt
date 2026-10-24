package ai.reopt.djtrot.dj

/** 받침에 따라 조사를 고른다. 한글이 아닌 글자로 끝나면 받침 없는 쪽을 쓴다. */
object Josa {
    private fun finalConsonant(word: String): Int? {
        val last = word.trimEnd().lastOrNull() ?: return null
        if (last !in '가'..'힣') return null
        return (last - '가') % 28
    }

    /** 으로/로 — ㄹ 받침은 "로"(서울로). */
    fun ro(word: String): String {
        val jong = finalConsonant(word)
        return word + if (jong != null && jong != 0 && jong != 8) "으로" else "로"
    }
}
