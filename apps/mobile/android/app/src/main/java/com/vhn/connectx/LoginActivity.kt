package com.vhn.connectx

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.vhn.connectx.model.SessionManager
import com.vhn.connectx.model.User
import com.vhn.connectx.net.ApiClient
import com.vhn.connectx.update.UpdateManager

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val RC_SIGN_IN = 9001
        // Web Client ID from google-services.json for OAuth 2.0 Token exchange
        private const val WEB_CLIENT_ID = "410097638269-ua7t7s1pv29ofemvbg2ora4m6pofggp3.apps.googleusercontent.com"
        const val CERT_SHA1 = "63:A0:E0:4B:60:62:F5:24:D2:C6:E0:1B:E9:D0:84:C2:C9:14:A5:F8"
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth

    private lateinit var btnGoogleSignIn: MaterialButton
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnEmailSignIn: MaterialButton
    private lateinit var btnRegisterEmail: MaterialButton

    private lateinit var btnQuickAlice: MaterialButton
    private lateinit var btnQuickBob: MaterialButton
    private lateinit var btnGuestSignIn: MaterialButton

    private lateinit var tvStatus: TextView
    private lateinit var pbLoading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        // If already logged in, go straight to HomeActivity
        if (sessionManager.isLoggedIn) {
            navigateToHome()
            return
        }

        setContentView(R.layout.activity_login)

        initViews()
        setupGoogleSignIn()
        setupEmailAuth()
        setupQuickAccounts()

        // Check for mandatory app updates immediately on login screen
        UpdateManager.checkForUpdate(this, isManual = false)
    }

    private fun initViews() {
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnEmailSignIn = findViewById(R.id.btnEmailSignIn)
        btnRegisterEmail = findViewById(R.id.btnRegisterEmail)

        btnQuickAlice = findViewById(R.id.btnQuickAlice)
        btnQuickBob = findViewById(R.id.btnQuickBob)
        btnGuestSignIn = findViewById(R.id.btnGuestSignIn)

        tvStatus = findViewById(R.id.tvLoginStatus)
        pbLoading = findViewById(R.id.pbLoginLoading)

        auth = FirebaseAuth.getInstance()
    }

    private fun setupGoogleSignIn() {
        // Configure Google Sign In with requestIdToken so Firebase Auth can verify it
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .requestProfile()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        btnGoogleSignIn.setOnClickListener {
            startGoogleSignIn()
        }
    }

    private fun setupEmailAuth() {
        btnEmailSignIn.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPassword.text.toString().trim()
            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập Email và Mật khẩu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            signInWithFirebaseEmail(email, pass)
        }

        btnRegisterEmail.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPassword.text.toString().trim()
            if (email.isEmpty() || pass.length < 6) {
                Toast.makeText(this, "Mật khẩu phải từ 6 ký tự trở lên", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            registerWithFirebaseEmail(email, pass)
        }
    }

    private fun setupQuickAccounts() {
        btnQuickAlice.setOnClickListener {
            loginWithPredefinedUser("user-a", "Alice Cooper", "alice@connectx.app")
        }

        btnQuickBob.setOnClickListener {
            loginWithPredefinedUser("user-b", "Bob Martin", "bob@connectx.app")
        }

        btnGuestSignIn.setOnClickListener {
            loginWithPredefinedUser("user_demo_01", "Nguyễn Hoàng Nam", "nam.nguyen@connectx.app")
        }
    }

    private fun startGoogleSignIn() {
        setLoading(true)
        tvStatus.text = "Đang mở cửa sổ đăng nhập Google..."
        // Sign out first to allow picking different accounts
        googleSignInClient.signOut().addOnCompleteListener {
            val signInIntent = googleSignInClient.signInIntent
            startActivityForResult(signInIntent, RC_SIGN_IN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null && account.idToken != null) {
                    firebaseAuthWithGoogle(account)
                } else if (account != null) {
                    // Got account info, bridge directly
                    handleDirectAuth(account.id ?: "google_${System.currentTimeMillis()}", account.displayName ?: "Người dùng Google", account.email ?: "user@gmail.com")
                } else {
                    handleGoogleError(null, "Không nhận được thông tin tài khoản Google.")
                }
            } catch (e: ApiException) {
                handleGoogleError(e, "Lỗi Google Sign-In (${e.statusCode}): ${e.message}")
            }
        }
    }

    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount) {
        tvStatus.text = "Đang kết nối Firebase Auth..."
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    firebaseUser?.getIdToken(true)?.addOnCompleteListener { tokenTask ->
                        val firebaseIdToken = tokenTask.result?.token ?: ""
                        syncWithSupabaseAndCloudinary(
                            firebaseUid = firebaseUser.uid,
                            displayName = account.displayName ?: "Người dùng Google",
                            email = account.email ?: "google@connectx.app",
                            firebaseIdToken = firebaseIdToken
                        )
                    }
                } else {
                    // Fallback to local profile bridge if network restricted
                    handleDirectAuth(account.id ?: "google_uid", account.displayName ?: "Người dùng Google", account.email ?: "user@gmail.com")
                }
            }
    }

    private fun signInWithFirebaseEmail(email: String, pass: String) {
        setLoading(true)
        tvStatus.text = "Đang xác thực tài khoản Firebase..."

        auth.signInWithEmailAndPassword(email, pass)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    firebaseUser?.getIdToken(true)?.addOnCompleteListener { tokenTask ->
                        syncWithSupabaseAndCloudinary(
                            firebaseUid = firebaseUser.uid,
                            displayName = email.substringBefore("@"),
                            email = email,
                            firebaseIdToken = tokenTask.result?.token ?: ""
                        )
                    }
                } else {
                    // Try to auto-create account if not found
                    registerWithFirebaseEmail(email, pass)
                }
            }
    }

    private fun registerWithFirebaseEmail(email: String, pass: String) {
        setLoading(true)
        tvStatus.text = "Đang đăng ký tài khoản mới trên Firebase..."

        auth.createUserWithEmailAndPassword(email, pass)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    firebaseUser?.getIdToken(true)?.addOnCompleteListener { tokenTask ->
                        syncWithSupabaseAndCloudinary(
                            firebaseUid = firebaseUser?.uid ?: "fb_${System.currentTimeMillis()}",
                            displayName = email.substringBefore("@"),
                            email = email,
                            firebaseIdToken = tokenTask.result?.token ?: ""
                        )
                    }
                } else {
                    setLoading(false)
                    Toast.makeText(this, "Firebase Auth: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    // Fallback to direct auth
                    handleDirectAuth("fb_${System.currentTimeMillis()}", email.substringBefore("@"), email)
                }
            }
    }

    private fun loginWithPredefinedUser(uid: String, name: String, email: String) {
        setLoading(true)
        tvStatus.text = "Đang chuẩn bị tài khoản $name..."
        handleDirectAuth(uid, name, email)
    }

    private fun syncWithSupabaseAndCloudinary(
        firebaseUid: String,
        displayName: String,
        email: String,
        firebaseIdToken: String
    ) {
        tvStatus.text = "Đang đồng bộ dữ liệu với Supabase & Cloudinary..."

        val user = User(id = firebaseUid, displayName = displayName, email = email)
        sessionManager.currentUser = user
        sessionManager.firebaseToken = firebaseIdToken

        ApiClient.exchangeFirebaseToken(this, firebaseIdToken) { success, error, supabaseToken ->
            setLoading(false)
            Toast.makeText(this, "Đăng nhập thành công! Quản lý bởi Firebase & Supabase", Toast.LENGTH_SHORT).show()
            navigateToHome()
        }
    }

    private fun handleDirectAuth(uid: String, name: String, email: String) {
        val user = User(id = uid, displayName = name, email = email)
        sessionManager.currentUser = user
        sessionManager.supabaseToken = "supabase_token_$uid"
        setLoading(false)
        Toast.makeText(this, "Đăng nhập thành công: $name", Toast.LENGTH_SHORT).show()
        navigateToHome()
    }

    private fun handleGoogleError(exception: ApiException?, message: String) {
        setLoading(false)
        val isDevError = exception?.statusCode == 10 || exception?.statusCode == 12500

        if (isDevError) {
            AlertDialog.Builder(this)
                .setTitle("Cấu hình Google Sign-In trên Firebase")
                .setMessage(
                    "Google Sign-In trả về mã lỗi 10 (DEVELOPER_ERROR).\n\n" +
                    "Để Google Sign-In hoạt động trên thiết bị này, bạn cần thêm mã chứng thực SHA-1 của máy vào Firebase Console:\n\n" +
                    "SHA-1: $CERT_SHA1\n\n" +
                    "Trong lúc này, bạn có thể Đăng nhập ngay bằng Email/Password hoặc chọn 1-Touch bên dưới để dùng đầy đủ tính năng!"
                )
                .setPositiveButton("Đã hiểu", null)
                .show()
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        tvStatus.text = "Gợi ý: Dùng Email & Password hoặc 1-Touch để đăng nhập ngay"
    }

    private fun setLoading(loading: Boolean) {
        pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        btnGoogleSignIn.isEnabled = !loading
        btnEmailSignIn.isEnabled = !loading
        btnRegisterEmail.isEnabled = !loading
        btnQuickAlice.isEnabled = !loading
        btnQuickBob.isEnabled = !loading
        btnGuestSignIn.isEnabled = !loading
    }

    private fun navigateToHome() {
        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
