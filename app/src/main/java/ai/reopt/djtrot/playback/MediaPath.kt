package ai.reopt.djtrot.playback

/**
 * 루트 바로 아래 네 폴더. 미디어 센터는 이것을 탭으로 그리고, 앱 화면의 노래 고르기 탭도 같은 넷을 같은 순서로 쓴다.
 * 🔑 AAOS 미디어 센터는 루트의 폴더를 탭으로 보인다(4개 안팎 권장). 폴더를 루트에 늘어놓지 말고 이 아래에 둔다.
 * [label]은 화면 탭 이름, [title]·[subtitle]은 미디어 센터의 폴더 이름이다.
 */
enum class Shelf(val id: String, val label: String, val title: String, val subtitle: String) {
    PICKS("picks", "추천곡", "DJ 트롯 추천", "오늘의 트로트"),
    LATEST("latest", "새 노래", "새로 들어온 노래", "방금 무대에 오른 노래"),
    STYLES("styles", "장르", "장르별", "뽕짝·정통·세미·발라드"),
    MINE("mine", "내 노래", "내 드라이브 노래", "길에서 만든 노래"),
}

/**
 * 미디어 ID가 브라우즈 트리의 어디를 가리키는지. 문자열 ID는 이 타입으로만 읽고 만든다.
 *
 * ```
 * root
 * ├─ picks          곡
 * ├─ latest         곡
 * ├─ styles         style:{id} → 곡
 * └─ mine           곡 (휴대폰 연결 뒤)
 * ```
 * 곡은 어느 목록에 있든 `song:{id}` 하나다. 그래서 지금 곡 표시가 목록과 상관없이 맞는다.
 */
sealed interface MediaPath {
    val id: String

    data object Root : MediaPath {
        override val id = "root"
    }

    data class Top(val shelf: Shelf) : MediaPath {
        override val id get() = shelf.id
    }

    /** 장르 목록. ID는 서버 선반 ID와 같다. */
    data class Style(val style: String) : MediaPath {
        override val id get() = STYLE_PREFIX + style
    }

    data class Song(val songId: String) : MediaPath {
        override val id get() = SONG_PREFIX + songId
    }

    /** 곡이 바로 들어 있는 목록 — 누르면 목록을 처음부터 튼다. */
    val holdsSongs: Boolean get() = (this is Top && shelf != Shelf.STYLES) || this is Style

    companion object {
        private const val STYLE_PREFIX = "style:"
        private const val SONG_PREFIX = "song:"

        /** 모르는 ID면 null. */
        fun parse(id: String): MediaPath? = when {
            id == Root.id -> Root
            id.startsWith(SONG_PREFIX) -> id.removePrefix(SONG_PREFIX).takeIf { it.isNotBlank() }?.let(::Song)
            id.startsWith(STYLE_PREFIX) -> id.removePrefix(STYLE_PREFIX).takeIf { it.isNotBlank() }?.let(::Style)
            else -> Shelf.entries.firstOrNull { it.id == id }?.let(::Top)
        }
    }
}
