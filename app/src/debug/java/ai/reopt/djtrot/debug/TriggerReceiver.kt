package ai.reopt.djtrot.debug

import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.Said
import ai.reopt.djtrot.dj.DjScript
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import java.time.LocalTime

/**
 * 시험 도구 — 에뮬레이터가 주지 않는 차량·내비 신호와 말로 넣는 사연을 adb 브로드캐스트로 흉내 낸다.
 * `tools/trigger.py`가 부르고, 결과 글(지금 신호로 DJ가 할 말)을 브로드캐스트 결과로 돌려준다.
 * 🔴 디버그 빌드에만 있고, 셸만 가진 권한(DUMP)으로 막아 다른 앱은 부르지 못한다.
 * 🔑 덧씌운 값은 프로세스가 사는 동안만 남는다. 앱을 다시 띄우면 실제 값으로 돌아간다.
 */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DjTrotApp
        val extras = intent.extras
        val args = extras?.keySet().orEmpty().mapNotNull { key -> extras?.getString(key)?.let { key to it } }.toMap()
        val result = runCatching { run(app, args) }.getOrElse { "오류: ${it.message}" }
        Log.i(TAG, result)
        if (isOrderedBroadcast) resultData = result
    }

    private fun run(app: DjTrotApp, args: Map<String, String>): String {
        val values = args - COMMAND
        var event: String? = null
        when (val command = args[COMMAND] ?: "show") {
            "show" -> Unit
            "keys" -> return "키:\n" + Staging.KEYS.entries.joinToString("\n") { "  ${it.key.padEnd(13)}${it.value}" }
            "set" -> stage(app, Staging.merge(staged, values))
            "clear" -> stage(app, emptyMap())
            "depart" -> {
                val departure = Staging.departure(values)
                stage(app, Staging.depart(staged, departure))
                app.car.stage(departure)
                event = DjScript.departure(departure)
            }
            "waypoint" -> {
                val arrival = Staging.arrival(values, "경유지")
                app.car.stage(arrival, waypoint = true)
                event = DjScript.waypoint(arrival)
            }
            "arrive" -> {
                val arrival = Staging.arrival(values, "목적지")
                stage(app, staged - Staging.ROUTE)
                app.car.stage(arrival)
                event = DjScript.arrival(arrival)
            }
            "say" -> {
                val text = requireNotNull(values["text"]?.trim()?.takeIf { it.isNotEmpty() }) { "say에는 text가 필요합니다" }
                app.wish(text)
            }
            else -> throw IllegalArgumentException("모르는 명령 '$command'. show keys set clear depart waypoint arrive say 가운데 하나입니다")
        }
        val dj = app.dj.value
        val stage = Staging.Stage(
            // 🔑 재생 서비스에 묻지 않고 시스템에 묻는다 — 서비스가 아직 뜨지 않았어도 답할 수 있다.
            playing = app.getSystemService(AudioManager::class.java)?.isMusicActive == true,
            talks = app.djTalks,
            last = dj.line?.let { Said(it, dj.lineAt ?: LocalTime.now()) },
        )
        return Staging.report(app.car.state.value, staged, LocalTime.now(), app.spoken, event, stage)
    }

    private fun stage(app: DjTrotApp, values: Map<String, String>) {
        staged = values
        app.car.stage(if (values.isEmpty()) null else { drive -> Staging.apply(drive, values) })
    }

    private companion object {
        const val TAG = "DjTrotTrigger"
        const val COMMAND = "cmd"

        /** 지금 덧씌운 값. 브로드캐스트는 늘 메인 스레드에서 받는다. */
        var staged: Map<String, String> = emptyMap()
    }
}
