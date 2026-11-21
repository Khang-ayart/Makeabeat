package com.bandstudio.app.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Kiểm tra, tải và cài bản cập nhật từ GitHub Releases. Mạng chỉ dùng cho việc này, không gửi dữ liệu người dùng. */
object Updater {
    const val REPO = "Khang-ayart/Makeabeat"
    private const val LIST_URL = "https://api.github.com/repos/$REPO/releases?per_page=15"

    fun currentVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Bản mới nhất trên GitHub (có thể cũ hơn hoặc bằng bản đang cài: người gọi tự so). Ném IOException nếu lỗi mạng. */
    suspend fun fetchLatest(includePrerelease: Boolean): UpdateInfo? = withContext(Dispatchers.IO) {
        val c = URL(LIST_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Makeabeat-updater")
            if (c.responseCode != 200) throw java.io.IOException("GitHub trả về mã ${c.responseCode}")
            UpdateParser.latest(c.inputStream.bufferedReader().use { it.readText() }, includePrerelease)
        } finally { c.disconnect() }
    }

    /** Tải APK về bộ nhớ đệm, kiểm tra kích thước và SHA-256. Trả về tệp, ném IOException nếu hỏng. [onProgress] 0..1. */
    suspend fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        require(UpdateParser.isAllowedUrl(info.apkUrl)) { "Nguồn tải không hợp lệ" }
        val dir = File(ctx.cacheDir, "updates").also { it.mkdirs(); it.listFiles()?.forEach { f -> f.delete() } }
        val out = File(dir, "Makeabeat-${info.versionName}.apk")
        var url = info.apkUrl
        var conn: HttpURLConnection? = null
        try {
            // Tự theo dõi chuyển hướng (GitHub chuyển sang githubusercontent.com), mỗi chặng đều kiểm tra nguồn.
            repeat(5) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "Makeabeat-updater")
                if (c.responseCode in 300..399) {
                    val next = c.getHeaderField("Location") ?: throw java.io.IOException("Chuyển hướng thiếu địa chỉ")
                    c.disconnect()
                    if (!UpdateParser.isAllowedUrl(next)) throw java.io.IOException("Chuyển hướng tới nguồn không được phép")
                    url = next
                } else { conn = c; return@repeat }
            }
            val c = conn ?: throw java.io.IOException("Quá nhiều lần chuyển hướng")
            if (c.responseCode != 200) throw java.io.IOException("Tải lỗi, mã ${c.responseCode}")
            val total = if (info.size > 0) info.size else c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var read = 0L
            c.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n); md.update(buf, 0, n); read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (info.size > 0 && read != info.size) throw java.io.IOException("Tải thiếu dữ liệu ($read/${info.size})")
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != info.sha256) throw java.io.IOException("Tệp tải về không khớp mã kiểm tra")
            out
        } catch (e: Exception) {
            out.delete(); throw e
        } finally { conn?.disconnect() }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Mở cài đặt "cho phép cài ứng dụng từ nguồn này" của chính app (chỉ cần một lần). */
    fun openInstallPermissionSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Cài [apk] bằng trình cài đặt của hệ thống. Android sẽ hỏi xác nhận (từ Android 12, nếu app đã là nơi cài bản hiện tại
     * thì có thể cài ngầm). Kết quả trả về qua [InstallResultReceiver]. Chữ ký phải trùng bản đang cài, nếu không hệ thống từ chối.
     */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { ins -> session.openWrite("makeabeat.apk", 0, apk.length()).use { os -> ins.copyTo(os); session.fsync(os) } }
            val intent = Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName)
            val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }
}

/** Nhận kết quả cài đặt: cần xác nhận thì mở hộp thoại của hệ thống; lỗi thì ghi lại để màn Home hiển thị. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> lastError = null
            else -> lastError = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Cài đặt thất bại (mã $status)"
        }
    }

    companion object {
        /** Lỗi cài đặt gần nhất (ví dụ chữ ký không khớp bản đang cài); màn Home đọc và hiển thị. */
        var lastError: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
    }
}
