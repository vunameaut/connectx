package com.vhn.connectx.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.vhn.connectx.BuildConfig
import com.vhn.connectx.net.ApiClient
import okhttp3.*
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

data class AppVersionResponse(
    @SerializedName("versionCode") val versionCode: Int = 1,
    @SerializedName("versionName") val versionName: String = "1.0.0",
    @SerializedName("minSupportedVersion") val minSupportedVersion: String = "1.0.0",
    @SerializedName("updateRequired") val updateRequired: Boolean = false,
    @SerializedName("forceUpdate") val forceUpdate: Boolean = false,
    @SerializedName("releaseNotes") val releaseNotes: String = "",
    @SerializedName("downloadUrl") val downloadUrl: String = ""
)

object UpdateManager {

    private const val TAG = "UpdateManager"

    // Default GitHub repo coordinates
    var githubRepo: String = "vhn/connectx"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks for mandatory app updates from backend API or GitHub Releases.
     * @param activity Context Activity
     * @param isManual True if user triggered manually in Profile
     */
    fun checkForUpdate(activity: Activity, isManual: Boolean = false) {
        val backendUrl = "${ApiClient.apiBaseUrl}/api/v1/app/version"

        val request = Request.Builder()
            .url(backendUrl)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Backend version check failed, falling back to GitHub Releases: ${e.message}")
                checkGitHubRelease(activity, isManual)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    try {
                        val versionInfo = gson.fromJson(body, AppVersionResponse::class.java)
                        val currentVersion = BuildConfig.VERSION_NAME.trim()
                        val currentCode = BuildConfig.VERSION_CODE

                        val isNewer = isNewerVersion(versionInfo.versionName, currentVersion)
                        val isBelowMin = isNewerVersion(versionInfo.minSupportedVersion, currentVersion)
                        val hasHigherCode = versionInfo.versionCode > currentCode
                        val isForcedByServer = (versionInfo.forceUpdate || versionInfo.updateRequired) && (isNewer || isBelowMin || hasHigherCode)

                        val updateMandatory = isNewer || isBelowMin || hasHigherCode || isForcedByServer

                        if (updateMandatory) {
                            mainHandler.post {
                                if (activity.isFinishing || activity.isDestroyed) return@post
                                showMandatoryUpdateDialog(
                                    activity = activity,
                                    newVersion = versionInfo.versionName,
                                    releaseNotes = versionInfo.releaseNotes,
                                    downloadUrl = versionInfo.downloadUrl
                                )
                            }
                            return
                        } else if (isManual) {
                            mainHandler.post {
                                if (activity.isFinishing || activity.isDestroyed) return@post
                                showLatestVersionDialog(activity, currentVersion)
                            }
                            return
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse backend version response", e)
                    }
                }
                checkGitHubRelease(activity, isManual)
            }
        })
    }

    private fun checkGitHubRelease(activity: Activity, isManual: Boolean) {
        val prefs = activity.getSharedPreferences("connectx_update", Context.MODE_PRIVATE)
        val customRepo = prefs.getString("custom_github_repo", githubRepo) ?: githubRepo
        val url = "https://api.github.com/repos/$customRepo/releases/latest"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "ConnectX-Android-Updater")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    if (isManual) {
                        Toast.makeText(activity, "Không thể kết nối máy chủ cập nhật: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrEmpty()) {
                    mainHandler.post {
                        if (isManual) {
                            showLatestVersionDialog(activity, BuildConfig.VERSION_NAME)
                        }
                    }
                    return
                }

                try {
                    val release = gson.fromJson(body, GithubRelease::class.java)
                    val latestVersion = release.tagName.removePrefix("v").trim()
                    val currentVersion = BuildConfig.VERSION_NAME.trim()

                    if (isNewerVersion(latestVersion, currentVersion)) {
                        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                        val downloadUrl = apkAsset?.downloadUrl ?: ""

                        mainHandler.post {
                            if (activity.isFinishing || activity.isDestroyed) return@post
                            showMandatoryUpdateDialog(
                                activity = activity,
                                newVersion = release.tagName,
                                releaseNotes = release.body,
                                downloadUrl = downloadUrl
                            )
                        }
                    } else if (isManual) {
                        mainHandler.post {
                            if (activity.isFinishing || activity.isDestroyed) return@post
                            showLatestVersionDialog(activity, currentVersion)
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
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
        val rClean = remote.removePrefix("v").substringBefore("-").trim()
        val lClean = local.removePrefix("v").substringBefore("-").trim()
        val rParts = rClean.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = lClean.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    /**
     * Displays a MANDATORY, NON-CANCELABLE update dialog.
     * User cannot dismiss this dialog and must update to continue using the app.
     */
    private fun showMandatoryUpdateDialog(
        activity: Activity,
        newVersion: String,
        releaseNotes: String,
        downloadUrl: String
    ) {
        val notes = if (releaseNotes.isNotBlank()) releaseNotes else "Bản cập nhật bắt buộc: Nâng cấp bảo mật Firebase Auth, Supabase DB & Cloudinary Media."

        val dialog = AlertDialog.Builder(activity)
            .setTitle("⚠️ BẮT BUỘC CẬP NHẬT PHIÊN BẢN $newVersion")
            .setMessage(
                "Phiên bản hiện tại (v${BuildConfig.VERSION_NAME}) đã hết hạn hỗ trợ hoặc có bản cập nhật bảo mật quan trọng.\n\n" +
                "Bạn BẮT BUỘC phải cập nhật lên phiên bản $newVersion để tiếp tục sử dụng ứng dụng ConnectX.\n\n" +
                "Nội dung cập nhật:\n$notes"
            )
            .setPositiveButton("CẬP NHẬT NGAY (BẮT BUỘC)", null) // Handled below to prevent auto-dismiss
            .setCancelable(false)
            .setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    // Back button exits the app completely
                    activity.finishAffinity()
                    true
                } else {
                    false
                }
            }
            .create()

        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        // Override positive button click to download APK
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val actualUrl = if (downloadUrl.isNotEmpty()) {
                downloadUrl
            } else {
                "${ApiClient.apiBaseUrl}/download/ConnectX-latest.apk"
            }
            downloadAndInstallApk(activity, actualUrl, "ConnectX-$newVersion.apk")
        }
    }

    private fun showLatestVersionDialog(activity: Activity, currentVersion: String) {
        AlertDialog.Builder(activity)
            .setTitle("ConnectX đã cập nhật")
            .setMessage("Phiên bản hiện tại: v$currentVersion\nBạn đang sử dụng phiên bản mới nhất!")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun downloadAndInstallApk(activity: Activity, url: String, fileName: String) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Đang tải bản cập nhật bắt buộc...")
            setMessage("Vui lòng đợi trong giây lát...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
        }

        val destinationDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        val destinationFile = File(destinationDir, fileName)

        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    progressDialog.dismiss()
                    showDownloadErrorDialog(activity, url, fileName, e.message ?: "Mất kết nối mạng")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    mainHandler.post {
                        progressDialog.dismiss()
                        showDownloadErrorDialog(activity, url, fileName, "Mã lỗi HTTP: ${response.code}")
                    }
                    return
                }

                val body = response.body
                if (body == null) {
                    mainHandler.post {
                        progressDialog.dismiss()
                        showDownloadErrorDialog(activity, url, fileName, "Dữ liệu trả về trống")
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
                        showDownloadErrorDialog(activity, url, fileName, e.message ?: "Lỗi ghi file")
                    }
                }
            }
        })
    }

    private fun showDownloadErrorDialog(activity: Activity, url: String, fileName: String, reason: String) {
        AlertDialog.Builder(activity)
            .setTitle("Tải bản cập nhật thất bại")
            .setMessage("Không thể tải bản cập nhật bắt buộc: $reason.\n\nBạn phải cập nhật để tiếp tục.")
            .setPositiveButton("Thử lại") { _, _ ->
                downloadAndInstallApk(activity, url, fileName)
            }
            .setNegativeButton("Thoát ứng dụng") { _, _ ->
                activity.finishAffinity()
            }
            .setCancelable(false)
            .show()
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
