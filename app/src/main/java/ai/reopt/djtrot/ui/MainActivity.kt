package ai.reopt.djtrot.ui

import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.playback.DjTrotLibraryService
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DjTrotApp
        app.car.start()
        // 🔑 맨 위 화면에서 뒤로(CCP 뒤로 키 포함)는 앱을 끄지 않고 뒤로 숨긴다(Pleos CCP Development Guide).
        //    화면 안의 탭은 Compose BackHandler가 먼저 받는다.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
        setContent {
            DjTrotTheme { DjScreen(app = app, player = controller.value) }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, DjTrotLibraryService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        lifecycleScope.launch { controller.value = runCatching { future.await() }.getOrNull() }
    }

    override fun onStop() {
        controller.value = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }
}
