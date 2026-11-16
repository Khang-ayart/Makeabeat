package com.bandstudio.app

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.bandstudio.app.audio.AudioEngine
import com.bandstudio.app.ui.BandStudioTheme
import com.bandstudio.app.ui.EditorScreen
import com.bandstudio.app.ui.HomeScreen
import com.bandstudio.app.ui.LauncherScreen
import com.bandstudio.app.ui.StudioScreen

class MainActivity : ComponentActivity() {

    private val viewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC // phím âm lượng điều khiển nhạc
        AudioEngine.create()
        setContent {
            BandStudioTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AudioEngine.start()
        viewModel.syncEngine()
    }

    override fun onStop() {
        viewModel.onBackground()
        AudioEngine.stop() // trả lại thiết bị âm thanh, tiết kiệm pin khi ở nền
        super.onStop()
    }
}

@Composable
private fun AppRoot(vm: StudioViewModel) {
    // Quay lại: Trình soạn -> Studio -> Danh sách -> thoát.
    BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }

    // Mỗi khung hình: cập nhật vị trí đầu phát.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            vm.poll()
        }
    }

    when (vm.screen) {
        Screen.Home -> HomeScreen(vm)
        Screen.Studio -> StudioScreen(vm)
        is Screen.Editor -> EditorScreen(vm)
        is Screen.Launcher -> LauncherScreen(vm)
    }
}
