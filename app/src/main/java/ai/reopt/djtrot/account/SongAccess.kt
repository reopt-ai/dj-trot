package ai.reopt.djtrot.account

import ai.reopt.djtrot.api.SongAuth

/**
 * 드라이브 노래에 닿는 자격 — 휴대폰을 연결한 차는 기기 세션, 아니면 시연 손님 ID(기기에 만들어 둔 UUID).
 * 🔑 시연 모드(서버 설정 `creation.guest`)가 켜져 있을 때만 손님으로 **만든다**. 이미 만든 곡은 모드가 꺼진 뒤에도 손님 ID로 읽는다.
 * 🔑 손님 ID는 계정이 아니다 — 개인 정보 없이 이 차가 만든 곡만 다시 찾는 열쇠다. 휴대폰을 연결하면 그때부터는 연결한 계정을 쓴다.
 */
class SongAccess(private val account: DeviceAccount, private val guestId: () -> String, private val guestOpen: () -> Boolean) {
    /** 연결하지 않았고 시연 모드라 손님으로 만드는가. */
    val asGuest: Boolean get() = !account.connected && guestOpen()

    /** 내 노래를 볼 수 있는가 — 연결했거나 시연 모드다. */
    val canList: Boolean get() = account.connected || guestOpen()

    /** 만들기 — 연결했으면 세션, 시연 모드면 손님, 둘 다 아니면 [DeviceAccount.NotConnected]. */
    suspend fun <T> create(block: suspend (SongAuth) -> T): T = when {
        account.connected -> account.authorized { block(SongAuth(token = it)) }
        guestOpen() -> block(SongAuth(guest = guestId()))
        else -> throw DeviceAccount.NotConnected()
    }

    /** 읽기 — 연결했으면 세션, 아니면 손님 ID. */
    suspend fun <T> read(block: suspend (SongAuth) -> T): T =
        if (account.connected) account.authorized { block(SongAuth(token = it)) } else block(SongAuth(guest = guestId()))

    /** 음원 요청에 붙일 머리글 — 재생 스레드에서 불린다. 세션을 받지 못하면 손님 ID. */
    suspend fun headers(): Map<String, String> {
        val token = if (account.connected) runCatching { account.token() }.getOrNull() else null
        return if (token != null) mapOf("Authorization" to "Bearer $token") else mapOf(SongAuth.GUEST_HEADER to guestId())
    }
}
