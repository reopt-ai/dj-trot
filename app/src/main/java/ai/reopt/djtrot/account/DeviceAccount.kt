package ai.reopt.djtrot.account

import ai.reopt.djtrot.api.DeviceCredential
import ai.reopt.djtrot.api.DjTrotApi
import ai.reopt.djtrot.api.DjTrotApiException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 휴대폰 연결 상태. */
sealed interface Connection {
    data object Disconnected : Connection
    data class Connected(val accountId: String?) : Connection
}

/**
 * 차량 기기 계정 — 휴대폰이 승인한 기기 자격으로 1시간짜리 세션을 받아 쓴다(AAOS 휴대폰 로그인 방식).
 * 🔑 세션은 메모리에만 둔다. 만료 1분 전이면 기기 자격으로 새로 받는다(`/devices/session`).
 * 🔴 서버가 401 DEVICE_REVOKED를 주면(휴대폰에서 연결 해제) 자격을 지우고 연결 전 상태로 돌아간다.
 */
class DeviceAccount(
    private val api: DjTrotApi,
    private val vault: DeviceVault,
) {
    private val lock = Mutex()
    private var token: String? = null
    private var expiresAt: Instant = Instant.EPOCH

    private val _connection = MutableStateFlow<Connection>(if (vault.read() != null) Connection.Connected(null) else Connection.Disconnected)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    val connected: Boolean get() = _connection.value is Connection.Connected

    /** 페어링이 끝나 자격을 받았을 때. 바로 세션까지 받아 둔다. */
    suspend fun connect(device: DeviceCredential) {
        vault.write(device)
        lock.withLock {
            token = null
            _connection.value = Connection.Connected(null)
        }
        runCatching { token() }
    }

    /** 유효한 Bearer 토큰. 연결 전이면 null. */
    suspend fun token(): String? = lock.withLock {
        val device = vault.read() ?: return@withLock null.also { _connection.value = Connection.Disconnected }
        val current = token
        if (current != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) return@withLock current
        try {
            val session = api.deviceSession(device)
            token = session.accessToken
            expiresAt = runCatching { Instant.parse(session.expiresAt) }.getOrDefault(Instant.now().plusSeconds(3000))
            _connection.value = Connection.Connected(session.accountId)
            session.accessToken
        } catch (error: DjTrotApiException) {
            if (error.status == 401) {
                vault.clear()
                token = null
                _connection.value = Connection.Disconnected
                null
            } else {
                throw error
            }
        }
    }

    /**
     * 로그인이 필요한 호출. 토큰이 거절되면(401) 한 번 새 세션으로 다시 부른다.
     * 연결 전이면 [NotConnected]를 던진다.
     */
    suspend fun <T> authorized(block: suspend (String) -> T): T {
        val first = token() ?: throw NotConnected()
        return try {
            block(first)
        } catch (error: DjTrotApiException) {
            if (error.status != 401) throw error
            lock.withLock { token = null }
            block(token() ?: throw NotConnected())
        }
    }

    class NotConnected : IllegalStateException("휴대폰과 연결되지 않았어요.")
}
