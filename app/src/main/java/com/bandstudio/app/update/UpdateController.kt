package com.bandstudio.app.update

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Trạng thái và luồng cập nhật cho giao diện: kiểm tra GitHub Releases, tải (tự động nếu bật), rồi cài khi người dùng xác nhận.
 * Tự kiểm tra tối đa 12 giờ một lần; mọi lỗi mạng khi tự kiểm tra đều im lặng (app vẫn dùng offline bình thường).
 */
class UpdateController(private val app: Application, private val scope: CoroutineScope) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val info: UpdateInfo, val progress: Float) : State
        data class Ready(val info: UpdateInfo, val file: File) : State
        data class Error(val message: String) : State
    }

    private val prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE)

    var state by mutableStateOf<State>(State.Idle)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    var auto by mutableStateOf(prefs.getBoolean("auto", true))
        private set
    var wifiOnly by mutableStateOf(prefs.getBoolean("wifiOnly", true))
        private set
    var includePrerelease by mutableStateOf(prefs.getBoolean("pre", false))
        private set

    val versionName: String get() = Updater.currentVersionName(app)

    fun setAuto(v: Boolean) { auto = v; prefs.edit().putBoolean("auto", v).apply() }
    fun setWifiOnly(v: Boolean) { wifiOnly = v; prefs.edit().putBoolean("wifiOnly", v).apply() }
    fun setIncludePrerelease(v: Boolean) {
        includePrerelease = v; prefs.edit().putBoolean("pre", v).apply()
        state = State.Idle
    }

    init {
        // Bản đã cài xong thì dọn tệp tải cũ.
        File(app.cacheDir, "updates").deleteRecursively()
    }

    /** Gọi khi mở app: tự kiểm tra nếu đang bật và đã quá 12 giờ kể từ lần trước. */
    fun checkOnStart() {
        if (!auto) return
        val last = prefs.getLong("last", 0L)
        if (System.currentTimeMillis() - last < 12 * 3600_000L) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (state is State.Checking || state is State.Downloading) return
        if (!Updater.online(app)) {
            if (manual) state = State.Error("Không có kết nối mạng.")
            return
        }
        scope.launch {
            state = State.Checking
            try {
                val latest = Updater.fetchLatest(includePrerelease)
                prefs.edit().putLong("last", System.currentTimeMillis()).apply()
                if (latest != null && latest.versionCode > Updater.currentVersionCode(app)) {
                    state = State.Available(latest)
                    if (auto && (!wifiOnly || Updater.onWifi(app))) download(latest)
                } else state = State.UpToDate
            } catch (e: Exception) {
                state = if (manual) State.Error("Không kiểm tra được: ${e.message ?: "lỗi mạng"}") else State.Idle
            }
        }
    }

    fun download(info: UpdateInfo) {
        if (state is State.Downloading) return
        scope.launch {
            state = State.Downloading(info, 0f)
            try {
                val f = Updater.download(app, info) { p -> state = State.Downloading(info, p) }
                state = State.Ready(info, f)
            } catch (e: Exception) {
                state = State.Error("Tải không được: ${e.message ?: "lỗi mạng"}")
            }
        }
    }

    /** Cài bản đã tải. Lần đầu Android yêu cầu bật "cho phép cài từ nguồn này", sau đó bấm Cài đặt lại. */
    fun install() {
        val s = state as? State.Ready ?: return
        InstallResultReceiver.lastError = null
        if (!Updater.canInstall(app)) {
            message = "Hãy bật \"Cho phép từ nguồn này\" cho Makeabeat, rồi quay lại bấm Cài đặt."
            Updater.openInstallPermissionSettings(app)
            return
        }
        message = null
        try { Updater.install(app, s.file) } catch (e: Exception) { message = "Không mở được trình cài đặt: ${e.message}" }
    }
}
