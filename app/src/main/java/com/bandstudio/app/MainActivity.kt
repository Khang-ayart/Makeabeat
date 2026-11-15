package com.bandstudio.app

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.bandstudio.app.audio.AudioEngine
import com.bandstudio.app.ui.BandStudioTheme
import com.bandstudio.app.ui.StudioScreen

class MainActivity : ComponentActivity() {

    private val viewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC // phím âm lượng điều khiển nhạc
        AudioEngine.create()
        setContent {
            BandStudioTheme {
                StudioScreen(viewModel)
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
