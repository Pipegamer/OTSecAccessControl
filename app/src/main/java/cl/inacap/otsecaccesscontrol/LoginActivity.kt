package cl.inacap.otsecaccesscontrol

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import cl.inacap.otsecaccesscontrol.databinding.ActivityLoginBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var db: AppDatabaseHelper
    private var isDarkMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("otsec_prefs", Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("DARK_MODE", false) // Modo Claro por defecto
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabaseHelper(this)
        applyVisualTheme()

        // Botón para alternar entre Modo Claro y Modo Oscuro
        binding.btnToggleThemeLogin.setOnClickListener {
            isDarkMode = !isDarkMode
            prefs.edit().putBoolean("DARK_MODE", isDarkMode).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
            applyVisualTheme()
        }

        binding.rgRole.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbOperador -> {
                    binding.etUsername.setText("operador.campo")
                    binding.etPassword.setText("Operador2026")
                }
                R.id.rbAuditor -> {
                    binding.etUsername.setText("auditor.ot")
                    binding.etPassword.setText("Auditor2026")
                }
                R.id.rbAdmin -> {
                    binding.etUsername.setText("admin")
                    binding.etPassword.setText("Admin2026")
                }
            }
            binding.tvLoginError.visibility = View.GONE
        }

        binding.btnLogin.setOnClickListener {
            validateLogin()
        }

        binding.tvResetLock.setOnClickListener {
            showUnlockDialog()
        }
    }

    private fun applyVisualTheme() {
        if (isDarkMode) {
            binding.scrollLogin.setBackgroundColor(Color.parseColor("#0F172A"))
            binding.cardLoginForm.setCardBackgroundColor(Color.parseColor("#1E293B"))
            binding.cardLoginForm.strokeColor = Color.parseColor("#334155")
            binding.tvBrandTitle.setTextColor(Color.WHITE)
            binding.tvLoginTitle.setTextColor(Color.WHITE)
            binding.tvLoginSubtitle.setTextColor(Color.parseColor("#94A3B8"))
            binding.tvRoleLabel.setTextColor(Color.parseColor("#CBD5E1"))
            binding.rbOperador.setTextColor(Color.WHITE)
            binding.rbAuditor.setTextColor(Color.WHITE)
            binding.rbAdmin.setTextColor(Color.WHITE)
            binding.etUsername.setTextColor(Color.WHITE)
            binding.etPassword.setTextColor(Color.WHITE)
            binding.btnToggleThemeLogin.text = "☀️ Claro"
            binding.btnToggleThemeLogin.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#334155"))
            binding.btnToggleThemeLogin.setTextColor(Color.WHITE)
        } else {
            binding.scrollLogin.setBackgroundColor(Color.parseColor("#F1F5F9"))
            binding.cardLoginForm.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            binding.cardLoginForm.strokeColor = Color.parseColor("#E2E8F0")
            binding.tvBrandTitle.setTextColor(Color.parseColor("#0F172A"))
            binding.tvLoginTitle.setTextColor(Color.parseColor("#0F172A"))
            binding.tvLoginSubtitle.setTextColor(Color.parseColor("#475569"))
            binding.tvRoleLabel.setTextColor(Color.parseColor("#475569"))
            binding.rbOperador.setTextColor(Color.parseColor("#0F172A"))
            binding.rbAuditor.setTextColor(Color.parseColor("#0F172A"))
            binding.rbAdmin.setTextColor(Color.parseColor("#0F172A"))
            binding.etUsername.setTextColor(Color.parseColor("#0F172A"))
            binding.etPassword.setTextColor(Color.parseColor("#0F172A"))
            binding.btnToggleThemeLogin.text = "🌙 Oscuro"
            binding.btnToggleThemeLogin.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E2E8F0"))
            binding.btnToggleThemeLogin.setTextColor(Color.parseColor("#0F172A"))
        }
    }

    private fun validateLogin() {
        val username = binding.etUsername.text.toString().trim()
        val rawPassword = binding.etPassword.text.toString()

        if (username.isEmpty() || rawPassword.isEmpty()) {
            showError("Complete usuario y contraseña para continuar.")
            return
        }

        // 1. Estado en Tránsito: Cifrar credencial con AES-256 al momento de enviar el formulario
        val encryptedTransitPass = SecurityUtils.encryptInTransit(rawPassword)
        Log.d("OTSEC_LOGCAT", "Login en tránsito cifrado (AES-256): $encryptedTransitPass")
        val passwordAfterTransit = SecurityUtils.decryptInTransit(encryptedTransitPass)

        // 2. Verificar existencia del usuario en SQLite
        val user = db.getUserByUsername(username)
        if (user == null) {
            Log.w("OTSEC_LOGCAT", "Intento de login con usuario inexistente: $username")
            db.insertLog(nowTime(), "[ALERTA]", "Login rechazado: Usuario '$username' no existe", synced = true)
            showError("Error: El usuario '$username' no existe en el sistema. Verifique sus credenciales.")
            return
        }

        // 3. Verificar si la cuenta ya superó los 3 intentos permitidos
        if (user.isLocked) {
            Log.e("OTSEC_LOGCAT", "Acceso denegado: Cuenta '$username' bloqueada por superar 3 intentos")
            showError("INTENTOS SUPERADOS (3/3): Su cuenta está bloqueada por seguridad. Restablezca su contraseña con el Administrador OT.")
            return
        }

        // 4. Estado en Reposo: Comparar Hash SHA-256 + Salt almacenado en SQLite
        val calculatedHash = SecurityUtils.hashPassword(passwordAfterTransit, user.salt)
        if (calculatedHash != user.passwordHash) {
            val newAttempts = user.failedAttempts + 1
            val lockNow = newAttempts >= 3
            db.updateUserAttempts(username, newAttempts, lockNow)

            val weakPinWarning = if (!SecurityUtils.isStrongPassword(rawPassword)) {
                " (Política ISO 27400: No se permiten PINs débiles como '$rawPassword')."
            } else ""

            if (lockNow) {
                db.insertLog(nowTime(), "[ALERTA]", "Cuenta '$username' BLOQUEADA tras 3 intentos fallidos", synced = true)
                Log.e("OTSEC_LOGCAT", "Cuenta '$username' bloqueada tras 3 intentos fallidos")
                showError("¡INTENTOS SUPERADOS (3/3)! Cuenta bloqueada.$weakPinWarning Contáctese con el Administrador OT para reactivar su contraseña.")
            } else {
                val restantes = 3 - newAttempts
                db.insertLog(nowTime(), "[SEC]", "Clave errónea para '$username' (Intento $newAttempts/3)", synced = true)
                Log.w("OTSEC_LOGCAT", "Contraseña incorrecta para '$username' (Intento $newAttempts de 3)")
                showError("Contraseña incorrecta.$weakPinWarning Intentos permitidos restantes: $restantes de 3.")
            }
            return
        }

        // 5. Verificar correspondencia estricta de Rol (RBAC)
        val selectedRole = when (binding.rgRole.checkedRadioButtonId) {
            R.id.rbAdmin -> "ADMIN"
            R.id.rbAuditor -> "AUDITOR"
            else -> "OPERADOR"
        }
        if (user.role != selectedRole) {
            Log.w("OTSEC_LOGCAT", "Intento de escalamiento de rol: '$username' (${user.role}) intentó entrar como $selectedRole")
            showError("Acceso denegado: El usuario '$username' no posee privilegios de $selectedRole.")
            return
        }

        // Login válido: Reiniciar intentos y guardar el último usuario conectado
        db.updateUserAttempts(username, 0, false)
        getSharedPreferences("otsec_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("LAST_CONNECTED_USER", user.username)
            .apply()

        db.insertLog(nowTime(), "[SYS]", "Login exitoso SHA-256+Salt: ${user.username} (${user.role})", synced = true)
        Log.i("OTSEC_LOGCAT", "Login exitoso -> Usuario: ${user.username} | Rol: ${user.role}")

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("USERNAME", user.username)
            putExtra("ROLE", user.role)
        }
        startActivity(intent)
    }

    // Reemplaza la función showUnlockDialog() en LoginActivity.kt por esta versión que exige clave de Admin:
    private fun showUnlockDialog() {
        val targetUser = binding.etUsername.text.toString().trim().ifEmpty { "operador.campo" }
        val inputAdminPass = android.widget.EditText(this).apply {
            hint = "Contraseña de Administrador (Admin2026)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Reactivar Cuenta Bloqueada")
            .setMessage("Para desbloquear al usuario '$targetUser', ingrese la credencial del Administrador OT:")
            .setView(inputAdminPass)
            .setPositiveButton("Autorizar y Reactivar") { _, _ ->
                val adminUser = db.getUserByUsername("admin")
                val typedPass = inputAdminPass.text.toString()
                if (adminUser != null && SecurityUtils.hashPassword(typedPass, adminUser.salt) == adminUser.passwordHash) {
                    db.updateUserAttempts(targetUser, 0, false)
                    db.insertLog(nowTime(), "[SEC]", "Administrador reactivó cuenta '$targetUser'", synced = true)
                    Log.i("OTSEC_LOGCAT", "Administrador autorizó reactivación de la cuenta '$targetUser'")
                    binding.tvLoginError.visibility = View.GONE
                    Toast.makeText(this, "Cuenta '$targetUser' reactivada exitosamente", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("OTSEC_LOGCAT", "Fallo de autorización Admin al intentar desbloquear '$targetUser'")
                    Toast.makeText(this, "Credencial de Administrador incorrecta. Desbloqueo denegado.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showError(msg: String) {
        binding.tvLoginError.text = msg
        binding.tvLoginError.visibility = View.VISIBLE
    }

    private fun nowTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
}