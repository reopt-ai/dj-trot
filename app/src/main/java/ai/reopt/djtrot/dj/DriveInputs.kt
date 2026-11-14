package ai.reopt.djtrot.dj

import ai.reopt.djtrot.api.DestinationInput
import ai.reopt.djtrot.api.DriveInput
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.GearState
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MODES = setOf("NORMAL", "ECO", "SPORT")

/**
 * 차가 아는 것을 서버에 보내는 드라이브 맥락(`DriveInput`)의 모양으로.
 * 🔴 서버 스키마가 strict이고 길이 상한이 있다 — 넘는 글은 잘라 보내야 400이 나지 않는다.
 * 🔴 즐겨찾기(집·회사)와 동승자 유무는 보내지 않는다 — 그 말은 기기가 받아 둔 음성 조각으로 한다([VoiceLines]).
 * [seed]는 곡마다 다른 값이다 — 분위기 장면(충전·크루즈…)을 곡의 절반에만 보내는 데 쓴다.
 */
fun DriveContext.toInput(now: LocalTime, spoken: String = "", seed: Long = 0): DriveInput = DriveInput(
    destination = destination?.let { DestinationInput(it.name.take(80), it.address?.take(200), it.remainMinutes?.coerceIn(0, 100_000)) },
    currentAddress = currentAddress?.take(200),
    region = region?.take(40),
    drivingMode = drivingMode?.takeIf { it in MODES },
    localTime = now.format(HOUR_MINUTE),
    spoken = spoken.trim().take(200),
    // 🔑 주차 중인데 "달리고 계시네요"라고 하지 않게. 주행 제한이 걸리면 달리는 중, P단이면 서 있음, 그 밖에는 모름.
    moving = when {
        restricted -> true
        gear == GearState.PARK -> false
        else -> null
    },
    gear = gear.name,
    weather = VoiceLines.weather(this),
    scene = VoiceLines.scene(this, seed, local = false)?.id,
)
