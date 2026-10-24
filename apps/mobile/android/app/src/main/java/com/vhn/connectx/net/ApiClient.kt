package com.vhn.connectx.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.vhn.connectx.model.SessionManager
import com.vhn.connectx.model.User
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ExchangeTokenRequest(
    @SerializedName("idToken") val idToken: String
)

data class ExchangeTokenResponse(
    @SerializedName("supabaseToken") val supabaseToken: String = "",
    @SerializedName("profile") val profile: RemoteProfile? = null
)

data class RemoteProfile(
    @SerializedName("id") val id: String = "",
    @SerializedName("firebaseUid") val firebaseUid: String = "",
    @SerializedName("displayName") val displayName: String = "",
    @SerializedName("avatarAssetId") val avatarAssetId: String? = null
)

data class SignUploadRequest(
    @SerializedName("conversationId") val conversationId: String,
    @SerializedName("resourceType") val resourceType: String = "image",
    @SerializedName("mimeType") val mimeType: String = "image/jpeg",
    @SerializedName("sizeBytes") val sizeBytes: Long = 102400
)

data class SignedTicket(
    @SerializedName("cloudName") val cloudName: String = "",
    @SerializedName("apiKey") val apiKey: String = "",
    @SerializedName("timestamp") val timestamp: Long = 0,
    @SerializedName("folder") val folder: String = "",
    @SerializedName("signature") val signature: String = "",
    @SerializedName("uploadUrl") val uploadUrl: String = "",
    @SerializedName("maxSizeBytes") val maxSizeBytes: Long = 0
)

object ApiClient {

    private const val TAG = "ApiClient"

    // Default API base URL: 10.0.2.2 for Android emulator; can be modified for physical device IP
    var apiBaseUrl: String = "http://10.0.2.2:4000"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Exchanges Firebase ID Token for Supabase Custom JWT and syncs user profile with Supabase DB.
     */
    fun exchangeFirebaseToken(
        context: Context,
        firebaseIdToken: String,
        onComplete: (Boolean, String?, String?) -> Unit
    ) {
        val sessionManager = SessionManager(context)
        val url = "$apiBaseUrl/api/v1/auth/exchange-token"
        val jsonPayload = gson.toJson(ExchangeTokenRequest(firebaseIdToken))

        val request = Request.Builder()
            .url(url)
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "API server unreachable at $url. Running in offline/local Supabase sync mode: ${e.message}")
                mainHandler.post {
                    // Fallback local session if backend server is not running on local machine
                    sessionManager.supabaseToken = "mock_supabase_token_${System.currentTimeMillis()}"
                    onComplete(true, "OFFLINE_FALLBACK", null)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    try {
                        val exchangeRes = gson.fromJson(body, ExchangeTokenResponse::class.java)
                        sessionManager.supabaseToken = exchangeRes.supabaseToken
                        exchangeRes.profile?.let { prof ->
                            sessionManager.supabaseProfileId = prof.id
                            sessionManager.currentUser = User(
                                id = prof.id,
                                displayName = prof.displayName,
                                email = sessionManager.currentUser.email
                            )
                        }
                        Log.i(TAG, "Successfully exchanged Firebase token for Supabase session: ${exchangeRes.profile?.id}")
                        mainHandler.post {
                            onComplete(true, null, exchangeRes.supabaseToken)
                        }
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse exchange response", e)
                    }
                }

                mainHandler.post {
                    sessionManager.supabaseToken = "mock_supabase_token_${System.currentTimeMillis()}"
                    onComplete(true, "LOCAL_SESSION_PROVISIONED", null)
                }
            }
        })
    }

    /**
     * Signs Cloudinary upload payload via backend API.
     */
    fun signCloudinaryUpload(
        conversationId: String,
        mimeType: String,
        sizeBytes: Long,
        supabaseToken: String,
        onComplete: (SignedTicket?) -> Unit
    ) {
        val url = "$apiBaseUrl/api/v1/media/sign-upload"
        val payload = gson.toJson(
            SignUploadRequest(
                conversationId = conversationId,
                resourceType = "image",
                mimeType = mimeType,
                sizeBytes = sizeBytes
            )
        )

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $supabaseToken")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Media sign endpoint unreachable: ${e.message}")
                mainHandler.post { onComplete(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    try {
                        val ticket = gson.fromJson(body, SignedTicket::class.java)
                        mainHandler.post { onComplete(ticket) }
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse signed ticket", e)
                    }
                }
                mainHandler.post { onComplete(null) }
            }
        })
    }

    /**
     * Uploads image binary to Cloudinary CDN using Signed Ticket.
     */
    fun uploadToCloudinary(
        ticket: SignedTicket,
        imageBytes: ByteArray,
        fileName: String,
        onComplete: (String?) -> Unit
    ) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("api_key", ticket.apiKey)
            .addFormDataPart("timestamp", ticket.timestamp.toString())
            .addFormDataPart("folder", ticket.folder)
            .addFormDataPart("signature", ticket.signature)
            .addFormDataPart(
                "file",
                fileName,
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(ticket.uploadUrl)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Cloudinary upload failed: ${e.message}")
                mainHandler.post { onComplete(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    try {
                        val map = gson.fromJson(body, Map::class.java)
                        val secureUrl = map["secure_url"] as? String
                        mainHandler.post { onComplete(secureUrl) }
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse Cloudinary response", e)
                    }
                }
                mainHandler.post { onComplete(null) }
            }
        })
    }

    fun getBaseUrl(context: Context): String {
        val custom = com.vhn.connectx.data.AppDataStore.getInstance(context).customServerHost
        return if (!custom.isNullOrBlank()) custom.trim().removeSuffix("/") else apiBaseUrl
    }

    /**
     * Synchronize Friend Requests from backend server.
     */
    fun syncFriendRequests(
        context: Context,
        myEmail: String,
        onComplete: (List<com.vhn.connectx.model.FriendRequest>) -> Unit
    ) {
        val baseUrl = getBaseUrl(context)
        val url = "$baseUrl/api/v1/sync/friend-requests?email=${java.net.URLEncoder.encode(myEmail, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete(emptyList()) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    try {
                        val obj = gson.fromJson(body, Map::class.java)
                        val rawList = obj["requests"]
                        val json = gson.toJson(rawList)
                        val type = object : com.google.gson.reflect.TypeToken<List<com.vhn.connectx.model.FriendRequest>>() {}.type
                        val list: List<com.vhn.connectx.model.FriendRequest> = gson.fromJson(json, type) ?: emptyList()
                        mainHandler.post { onComplete(list) }
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing friend requests sync", e)
                    }
                }
                mainHandler.post { onComplete(emptyList()) }
            }
        })
    }

    /**
     * Send Friend Request to backend server.
     */
    fun sendFriendRequest(
        context: Context,
        req: com.vhn.connectx.model.FriendRequest,
        onComplete: (Boolean) -> Unit
    ) {
        val baseUrl = getBaseUrl(context)
        val url = "$baseUrl/api/v1/sync/friend-requests"
        val payload = gson.toJson(req)
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete(false) }
            }

            override fun onResponse(call: Call, response: Response) {
                mainHandler.post { onComplete(response.isSuccessful) }
            }
        })
    }

    /**
     * Respond to Friend Request on backend server.
     */
    fun respondFriendRequest(
        context: Context,
        reqId: String,
        status: String,
        onComplete: (Boolean) -> Unit
    ) {
        val baseUrl = getBaseUrl(context)
        val url = "$baseUrl/api/v1/sync/friend-requests/$reqId/respond"
        val payload = gson.toJson(mapOf("status" to status))
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete(false) }
            }

            override fun onResponse(call: Call, response: Response) {
                mainHandler.post { onComplete(response.isSuccessful) }
            }
        })
    }

    /**
     * Fetch Messages for a conversation from backend server.
     */
    fun syncMessages(
        context: Context,
        convId: String,
        currentUserId: String,
        onComplete: (List<com.vhn.connectx.model.Message>) -> Unit
    ) {
        val baseUrl = getBaseUrl(context)
        val url = "$baseUrl/api/v1/sync/messages/$convId"
        val request = Request.Builder().url(url).get().build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete(emptyList()) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    try {
                        val obj = gson.fromJson(body, Map::class.java)
                        val rawList = obj["messages"]
                        val json = gson.toJson(rawList)
                        val type = object : com.google.gson.reflect.TypeToken<List<com.vhn.connectx.model.Message>>() {}.type
                        val list: List<com.vhn.connectx.model.Message> = gson.fromJson(json, type) ?: emptyList()
                        // Ensure isMe is accurately evaluated for the current user
                        val adjusted = list.map { msg ->
                            msg.copy(isMe = (msg.senderId == currentUserId))
                        }
                        mainHandler.post { onComplete(adjusted) }
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing sync messages", e)
                    }
                }
                mainHandler.post { onComplete(emptyList()) }
            }
        })
    }

    /**
     * Send Message to backend server.
     */
    fun sendMessageToServer(
        context: Context,
        convId: String,
        message: com.vhn.connectx.model.Message,
        onComplete: (Boolean) -> Unit
    ) {
        val baseUrl = getBaseUrl(context)
        val url = "$baseUrl/api/v1/sync/messages/$convId"
        val payload = gson.toJson(message)
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete(false) }
            }

            override fun onResponse(call: Call, response: Response) {
                mainHandler.post { onComplete(response.isSuccessful) }
            }
        })
    }
}
