package ai.reopt.djtrot.ui

import ai.reopt.djtrot.R
import androidx.annotation.DrawableRes

/**
 * 마스터트롯 그림 자산을 장르에 붙인다.
 * 🔑 표지는 마스터트롯 웹과 같은 장르 연결을 쓴다 — 장르 이름과 그림 이름이 엇갈려 있다(뽕짝 → 미러볼).
 */
object Art {
    /** 곡 표지 — 커버를 올리지 않은 곡은 장르 그림을 쓴다. */
    @DrawableRes
    fun cover(style: String?): Int = when (family(style)) {
        "ppongjjak" -> R.drawable.cover_mirrorball
        "classic" -> R.drawable.cover_trumpet
        "dance" -> R.drawable.cover_accordion
        "ballad" -> R.drawable.cover_rose
        "gugak" -> R.drawable.cover_janggu
        else -> R.drawable.cover_accordion
    }

    /** 무대 배경 — 지금 곡 장르의 공연 장면. 곡이 없으면 객석 사진. */
    @DrawableRes
    fun stage(style: String?): Int = when (family(style)) {
        "ppongjjak" -> R.drawable.stage_ppongjjak
        "classic" -> R.drawable.stage_classic
        "dance" -> R.drawable.stage_dance
        "ballad" -> R.drawable.stage_ballad
        "gugak" -> R.drawable.stage_gugak
        else -> R.drawable.art_concert
    }

    /**
     * DJ 얼굴 — 설정의 DJ 고르기에 쓴다. 서버 `/config`의 `avatarUrl`과 같은 그림을 앱 안에 담아 오프라인에서도 보인다.
     * 🔑 DJ ID는 서버와의 계약(`hwanggu`·`mari`). 모르는 DJ는 null — 부르는 쪽이 마이크 아이콘으로 돌아간다.
     */
    @DrawableRes
    fun dj(id: String?): Int? = when (id) {
        "hwanggu" -> R.drawable.dj_hwanggu
        "mari" -> R.drawable.dj_mari
        else -> null
    }

    /** 장르 갈래 — 발라드 갈래는 하나로 본다. */
    fun family(style: String?): String? = if (style == "ballad" || style == "ballad-calm") "ballad" else style

    /**
     * 장르 그림을 곡마다 다르게 보이게 하는 확대·위치·좌우 뒤집기. 곡 ID로 정하니 같은 곡은 어디서나 같은 모습이다.
     * 🔑 장르 그림이 하나씩뿐이라 그대로 쓰면 목록에 같은 표지가 줄줄이 선다.
     */
    fun look(seed: String?): Look {
        if (seed.isNullOrEmpty()) return Look.Plain
        val bits = seed.hashCode()
        val zoom = ZOOMS[Math.floorMod(bits, ZOOMS.size)]
        // 확대한 만큼만 옮겨야 가장자리가 비지 않는다. 많이 키웠으면 덜 옮긴다 — 가운데의 마이크·악기가 빠지지 않게.
        val room = (zoom - 1f) / 2f * (if (zoom > 1.5f) 0.6f else 1f)
        return Look(
            zoom = zoom,
            dx = room * SHIFTS[Math.floorMod(bits shr 4, SHIFTS.size)],
            dy = room * SHIFTS[Math.floorMod(bits shr 8, SHIFTS.size)],
            mirror = (bits shr 12) and 1 == 1,
        )
    }

    /** [dx]·[dy]는 표지 크기에 대한 비율이다. */
    data class Look(val zoom: Float, val dx: Float, val dy: Float, val mirror: Boolean) {
        companion object {
            val Plain = Look(1f, 0f, 0f, false)
        }
    }

    // 1배는 두지 않는다 — 옮길 자리가 없어 뒤집기만 남고, 같은 장르 곡끼리 똑같아진다.
    private val ZOOMS = floatArrayOf(1.3f, 1.5f, 1.75f, 2f)
    private val SHIFTS = floatArrayOf(-1f, 0f, 1f)
}
