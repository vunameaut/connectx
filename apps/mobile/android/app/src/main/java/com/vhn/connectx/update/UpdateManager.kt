package com.vhn.connectx.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.vhn.connectx.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GithubRelease(
    @SerializedName("tag_name") val tagName: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("body") val body: String = "",
    @SerializedName("published_at") val publishedAt: String = "",
    @SerializedName("assets") val assets: List<GithubAsset> = emptyList()
)

data class GithubAsset(
    @SerializedName("name") val name: String = "",
    @SerializedName("browser_download_url") val downloadUrl: String = "",
    @SerializedName("size") val size: Long = 0
)

object UpdateManager {

    // Default GitHub repo coordinates (can be overridden via SharedPreferences if needed)
    var githubRepo: String = "vhn/connectx"
    var fallbackCheckUrl: String = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks for a new update from GitHub Releases.
     * @param activity Context activity
     * @param isManual True if user clicked "Kiểm tra cập nhật" manually in Profile
     */
    fun checkForUpdate(activity: Activity, isManual: Boolean = false) {
        val prefs = activity.getSharedPreferences("connectx_update", Context.MODE_PRIVATE)
        val customRepo = prefs.getString("custom_github_repo", githubRepo) ?: githubRepo
        val url = if (fallbackCheckUrl.isNotEmpty()) {
            fallbackCheckUrl
        } else {
            "https://api.github.com/repos/$customRepo/releases/latest"
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "ConnectX-Android-Updater")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    if (isManual) {
                        Toast.makeText(
                            activity,
                            "Không thể kết nối máy chủ GitHub: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    mainHandler.post {
                        if (isManual) {
                            Toast.makeText(
                                activity,
                                "Bạn đang ở phiên bản mới nhất (v${BuildConfig.VERSION_NAME})",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return
                }

                try {
                    val release = Gson().fromJson(body, GithubRelease::class.java)
                    val latestVersion = release.tagName.removePrefix("v").trim()
                    val currentVersion = BuildConfig.VERSION_NAME.trim()

                    if (isNewerVersion(latestVersion, currentVersion)) {
                        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                        val downloadUrl = apkAsset?.downloadUrl ?: ""

                        mainHandler.post {
                            showUpdateDialog(activity, release, downloadUrl)
                        }
                    } else if (isManual) {
                        mainHandler.post {
                            AlertDialog.Builder(activity)
                                .setTitle("ConnectX đã cập nhật")
                                .setMessage("Phiên bản hiện tại: v$currentVersion\nBạn đang sử dụng phiên bản mới nhất!")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        if (isManual) {
                            Toast.makeText(activity, "Đang dùng phiên bản mới nhất (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote.isEmpty() || local.isEmpty()) return false
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun showUpdateDialog(activity: Activity, release: GithubRelease, downloadUrl: String) {
        val notes = if (release.body.isNotBlank()) release.body else "Bản cập nhật bao gồm các cải tiến hiệu năng và sửa lỗi."

        AlertDialog.Builder(activity)
            .setTitle("Đã có bản cập nhật mới ${release.tagName}!")
            .setMessage("Nội dung cập nhật:\n\n$notes\n\nBạn có muốn tải và cài đặt ngay không?")
            .setPositiveButton("Cập nhật ngay") { _, _ ->
                if (downloadUrl.isNotEmpty()) {
                    downloadAndInstallApk(activity, downloadUrl, "ConnectX-${release.tagName}.apk")
                } else {
                    Toast.makeText(activity, "Không tìm thấy file APK đính kèm trong bản phát hành này.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Để sau", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadAndInstallApk(activity: Activity, url: String, fileName: String) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Đang tải bản cập nhật...")
            setMessage("Vui lòng đợi trong giây lát...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        val destinationDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        val destinationFile = File(destinationDir, fileName)

        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    progressDialog.dismiss()
                    Toast.makeText(activity, "Lỗi tải bản cập nhật: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    mainHandler.post {
                        progressDialog.dismiss()
                        Toast.makeText(activity, "Tải thất bại (Mã lỗi ${response.code})", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val body = response.body
                if (body == null) {
                    mainHandler.post {
                        progressDialog.dismiss()
                        Toast.makeText(activity, "Dữ liệu trả về rỗng", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                try {
                    body.byteStream().use { input ->
                        FileOutputStream(destinationFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0) {
                                    val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                                    mainHandler.post { progressDialog.progress = progress }
                                }
                            }
                            output.flush()
                        }
                    }

                    mainHandler.post {
                        progressDialog.dismiss()
                        launchPackageInstaller(activity, destinationFile)
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        progressDialog.dismiss()
                        Toast.makeText(activity, "Lỗi ghi file cập nhật: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun launchPackageInstaller(activity: Activity, file: File) {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Không thể mở bộ cài đặt APK: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
