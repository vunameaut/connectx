package com.vhn.connectx

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
        private const val WEB_CLIENT_ID = "410097638269-ua7t7s1pv29ofemvbg2ora4m6pofggp3.apps.googleusercontent.com"
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth

    private lateinit var btnGoogleSignIn: MaterialButton
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnEmailSignIn: MaterialButton
    private lateinit var btnRegisterEmail: MaterialButton

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

        // Check for mandatory app updates immediately on login screen
        UpdateManager.checkForUpdate(this, isManual = false)
    }

    private fun initViews() {
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnEmailSignIn = findViewById(R.id.btnEmailSignIn)
        btnRegisterEmail = findViewById(R.id.btnRegisterEmail)

        tvStatus = findViewById(R.id.tvLoginStatus)
        pbLoading = findViewById(R.id.pbLoginLoading)

        auth = FirebaseAuth.getInstance()
    }

    private fun setupGoogleSignIn() {
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
            signInWithEmail(email, pass)
        }

        btnRegisterEmail.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPassword.text.toString().trim()
            if (email.isEmpty() || pass.length < 6) {
                Toast.makeText(this, "Mật khẩu phải từ 6 ký tự trở lên", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            registerWithEmail(email, pass)
        }
    }

    private fun startGoogleSignIn() {
        setLoading(true)
        tvStatus.text = "Đang mở cửa sổ đăng nhập Google..."
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
                    handleDirectAuth(
                        uid = account.id ?: "google_${System.currentTimeMillis()}",
                        name = account.displayName ?: "Người dùng Google",
                        email = account.email ?: "user@gmail.com"
                    )
                } else {
                    handleGoogleError(null, "Không nhận được thông tin tài khoản Google.")
                }
            } catch (e: ApiException) {
                handleGoogleError(e, "Đăng nhập Google chưa thành công.")
            }
        }
    }

    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount) {
        tvStatus.text = "Đang xác thực thông tin..."
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    firebaseUser?.getIdToken(true)?.addOnCompleteListener { tokenTask ->
                        val firebaseIdToken = tokenTask.result?.token ?: ""
                        syncWithBackend(
                            uid = firebaseUser.uid,
                            displayName = account.displayName ?: "Người dùng Google",
                            email = account.email ?: "google@connectx.app",
                            firebaseIdToken = firebaseIdToken
                        )
                    }
                } else {
                    // Fallback to authenticated account profile
                    handleDirectAuth(
                        uid = account.id ?: "google_uid",
                        name = account.displayName ?: "Người dùng Google",
                        email = account.email ?: "user@gmail.com"
                    )
                }
            }
    }

    private fun signInWithEmail(email: String, pass: String) {
        setLoading(true)
        tvStatus.text = "Đang đăng nhập..."

        auth.signInWithEmailAndPassword(email, pass)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    firebaseUser?.getIdToken(true)?.addOnCompleteListener { tokenTask ->
                        syncWithBackend(
                            uid = firebaseUser.uid,
                            displayName = email.substringBefore("@"),
                            email = email,
                            firebaseIdToken = tokenTask.result?.token ?: ""
                        )
                    }
                } else {
                    setLoading(false)
                    tvStatus.text = "Email hoặc mật khẩu không chính xác."
                    Toast.makeText(this, "Thông tin đăng nhập không chính xác, vui lòng thử lại.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun registerWithEmail(email: String, pass: String) {
        setLoading(true)
        tvStatus.text = "Đang tạo tài khoản mới..."

        auth.createUserWithEmailAndPassword(email, pass)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    firebaseUser?.getIdToken(true)?.addOnCompleteListener { tokenTask ->
                        syncWithBackend(
                            uid = firebaseUser?.uid ?: "fb_${System.currentTimeMillis()}",
                            displayName = email.substringBefore("@"),
                            email = email,
                            firebaseIdToken = tokenTask.result?.token ?: ""
                        )
                    }
                } else {
                    setLoading(false)
                    val errorMsg = task.exception?.localizedMessage ?: "Đăng ký không thành công"
                    tvStatus.text = errorMsg
                    Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun syncWithBackend(
        uid: String,
        displayName: String,
        email: String,
        firebaseIdToken: String
    ) {
        tvStatus.text = "Đang hoàn tất kết nối..."

        val user = User(id = uid, displayName = displayName, email = email)
        sessionManager.currentUser = user
        sessionManager.firebaseToken = firebaseIdToken

        ApiClient.exchangeFirebaseToken(this, firebaseIdToken) { _, _, _ ->
            setLoading(false)
            Toast.makeText(this, "Xin chào $displayName!", Toast.LENGTH_SHORT).show()
            navigateToHome()
        }
    }

    private fun handleDirectAuth(uid: String, name: String, email: String) {
        val user = User(id = uid, displayName = name, email = email)
        sessionManager.currentUser = user
        sessionManager.supabaseToken = "session_$uid"
        setLoading(false)
        Toast.makeText(this, "Xin chào $name!", Toast.LENGTH_SHORT).show()
        navigateToHome()
    }

    private fun handleGoogleError(exception: ApiException?, message: String) {
        setLoading(false)
        tvStatus.text = "Đăng nhập Google chưa thành công. Bạn vui lòng thử lại hoặc đăng nhập bằng Email."
        Toast.makeText(this, "Chưa hoàn tất đăng nhập Google. Bạn có thể đăng nhập bằng Email ngay bên dưới.", Toast.LENGTH_LONG).show()
    }

    private fun setLoading(loading: Boolean) {
        pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        btnGoogleSignIn.isEnabled = !loading
        btnEmailSignIn.isEnabled = !loading
        btnRegisterEmail.isEnabled = !loading
    }

    private fun navigateToHome() {
        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
