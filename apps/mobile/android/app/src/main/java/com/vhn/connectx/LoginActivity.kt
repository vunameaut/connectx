package com.vhn.connectx

import android.content.Intent
import android.os.Bundle
import android.view.View
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

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val RC_SIGN_IN = 9001
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth

    private lateinit var btnGoogleSignIn: MaterialButton
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

        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)
        btnGuestSignIn = findViewById(R.id.btnGuestSignIn)
        tvStatus = findViewById(R.id.tvLoginStatus)
        pbLoading = findViewById(R.id.pbLoginLoading)

        auth = FirebaseAuth.getInstance()

        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        btnGoogleSignIn.setOnClickListener {
            startGoogleSignIn()
        }

        btnGuestSignIn.setOnClickListener {
            loginAsDemoUser()
        }
    }

    private fun startGoogleSignIn() {
        setLoading(true)
        tvStatus.text = "Đang mở cửa sổ đăng nhập Google..."
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    handleGoogleSuccess(account)
                } else {
                    handleGoogleFallback("Không lấy được thông tin tài khoản Google.")
                }
            } catch (e: ApiException) {
                // In test environments or when SHA-1 isn't yet configured on Firebase console,
                // fallback gracefully with informative notification
                handleGoogleFallback("Lỗi Google Sign-In (${e.statusCode}): ${e.message}")
            }
        }
    }

    private fun handleGoogleSuccess(account: GoogleSignInAccount) {
        val user = User(
            id = account.id ?: "google_${System.currentTimeMillis()}",
            displayName = account.displayName ?: "Người dùng Google",
            email = account.email ?: "google.user@connectx.app",
            avatarUrl = account.photoUrl?.toString() ?: ""
        )
        sessionManager.currentUser = user
        Toast.makeText(this, "Đăng nhập Google thành công: ${user.displayName}", Toast.LENGTH_SHORT).show()
        navigateToHome()
    }

    private fun handleGoogleFallback(reason: String) {
        setLoading(false)
        tvStatus.text = "Lưu ý: Bạn có thể chọn 'Dùng thử nhanh' để trải nghiệm đầy đủ Zalo & Chat ngay lập tức!"
        Toast.makeText(this, "$reason. Bạn có thể dùng thử tài khoản Demo!", Toast.LENGTH_LONG).show()
    }

    private fun loginAsDemoUser() {
        setLoading(true)
        val demoUser = User(
            id = "user_demo_01",
            displayName = "Nguyễn Hoàng Nam",
            email = "nam.nguyen@connectx.app"
        )
        sessionManager.currentUser = demoUser
        Toast.makeText(this, "Đăng nhập thành công với tài khoản Demo", Toast.LENGTH_SHORT).show()
        navigateToHome()
    }

    private fun setLoading(loading: Boolean) {
        pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        btnGoogleSignIn.isEnabled = !loading
        btnGuestSignIn.isEnabled = !loading
    }

    private fun navigateToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
