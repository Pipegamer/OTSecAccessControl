package cl.inacap.otsecaccesscontrol

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import cl.inacap.otsecaccesscontrol.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabaseHelper
    private lateinit var networkManager: DualNetworkManager
    private var autoLockTimer: CountDownTimer? = null
    private var currentUsername = "operador.campo"
    private var currentRole = "OPERADOR"
    private var currentHmiState = "LOCKED"
    private var isConnected = true
    private var isDarkMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("otsec_prefs", Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("DARK_MODE", false)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabaseHelper(this)
        val lastUser = prefs.getString("LAST_CONNECTED_USER", "operador.campo") ?: "operador.campo"

        currentUsername = intent.getStringExtra("USERNAME") ?: lastUser
        currentRole = intent.getStringExtra("ROLE") ?: "OPERADOR"

        binding.tvUserRoleSubtitle.text = "Usuario: $currentUsername · Rol: $currentRole"

        binding.btnManageUsers.visibility = if (currentRole == "ADMIN") View.VISIBLE else View.GONE

        if (currentRole == "AUDITOR") {
            binding.btnOpenO.isEnabled = false
            binding.btnLockC.isEnabled = false
            binding.btnAlarmR.isEnabled = false
            binding.swAutoLock.isEnabled = false
            binding.sbServoAngle.isEnabled = false
        }

        // Botón para alternar Modo Claro / Modo Oscuro en tiempo real
        binding.btnToggleThemeMain.setOnClickListener {
            isDarkMode = !isDarkMode
            prefs.edit().putBoolean("DARK_MODE", isDarkMode).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
            applyVisualTheme()
        }

        setupConnectionControls()
        setupHmiControls()
        setupLogFilter()
        applyVisualTheme()

        if (db.getLogs().isEmpty()) {
            db.insertLog("10:15:00", "[SYS]", "Enlace Bluetooth listo", synced = true)
            db.insertLog("10:15:02", "[ESTADO]", "Cerrojo bloqueado (Fail-Secure 0°)", synced = true)
        }
        refreshLogsView()
    }

    private fun applyVisualTheme() {
        if (isDarkMode) {
            binding.scrollMain.setBackgroundColor(Color.parseColor("#0F172A"))
            binding.tvMainTitle.setTextColor(Color.WHITE)
            binding.tvUserRoleSubtitle.setTextColor(Color.parseColor("#94A3B8"))

            binding.cardConnection.setCardBackgroundColor(Color.parseColor("#1E293B"))
            binding.cardConnection.strokeColor = Color.parseColor("#334155")
            binding.tvConnHeader.setTextColor(Color.parseColor("#94A3B8"))
            binding.spConnectionMode.setBackgroundColor(Color.parseColor("#0F172A"))
            binding.etMasterIp.setBackgroundColor(Color.parseColor("#0F172A"))
            binding.etMasterIp.setTextColor(Color.WHITE)

            binding.cardControls.setCardBackgroundColor(Color.parseColor("#1E293B"))
            binding.cardControls.strokeColor = Color.parseColor("#334155")
            binding.tvAutoLockLabel.setTextColor(Color.WHITE)
            binding.tvServoLabel.setTextColor(Color.parseColor("#CBD5E1"))

            binding.cardFilter.setCardBackgroundColor(Color.parseColor("#1E293B"))
            binding.cardFilter.strokeColor = Color.parseColor("#334155")
            binding.etFilterLogs.setTextColor(Color.WHITE)
            binding.etFilterLogs.setHintTextColor(Color.parseColor("#94A3B8"))

            binding.btnToggleThemeMain.text = "☀️"
            binding.btnToggleThemeMain.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#334155"))
            binding.btnToggleThemeMain.setTextColor(Color.WHITE)
        } else {
            binding.scrollMain.setBackgroundColor(Color.parseColor("#F1F5F9"))
            binding.tvMainTitle.setTextColor(Color.parseColor("#0F172A"))
            binding.tvUserRoleSubtitle.setTextColor(Color.parseColor("#64748B"))

            binding.cardConnection.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            binding.cardConnection.strokeColor = Color.parseColor("#E2E8F0")
            binding.tvConnHeader.setTextColor(Color.parseColor("#475569"))
            binding.spConnectionMode.setBackgroundColor(Color.parseColor("#F8FAFC"))
            binding.etMasterIp.setBackgroundColor(Color.parseColor("#F8FAFC"))
            binding.etMasterIp.setTextColor(Color.parseColor("#0F172A"))

            binding.cardControls.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            binding.cardControls.strokeColor = Color.parseColor("#E2E8F0")
            binding.tvAutoLockLabel.setTextColor(Color.parseColor("#0F172A"))
            binding.tvServoLabel.setTextColor(Color.parseColor("#475569"))

            binding.cardFilter.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            binding.cardFilter.strokeColor = Color.parseColor("#E2E8F0")
            binding.etFilterLogs.setTextColor(Color.parseColor("#0F172A"))
            binding.etFilterLogs.setHintTextColor(Color.parseColor("#64748B"))

            binding.btnToggleThemeMain.text = "🌙"
            binding.btnToggleThemeMain.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E2E8F0"))
            binding.btnToggleThemeMain.setTextColor(Color.parseColor("#0F172A"))
        }
        applyHmiVisualState(currentHmiState, binding.sbServoAngle.progress)
    }

    private fun setupConnectionControls() {
        val modes = listOf(
            "HC-05 (Bluetooth SPP / Campo)",
            "Estación Maestra (Servidor Dual Android)",
            "Estación Auditora (Conectar a Maestro)"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        binding.spConnectionMode.adapter = adapter

        networkManager = DualNetworkManager(
            onStateReceived = { state, angle, tag, msg ->
                db.insertLog(nowTime(), tag, "$msg (Sincronizado)", synced = true)
                applyHmiVisualState(state, angle)
                refreshLogsView()
                Toast.makeText(this, "Push-Up OT: $msg", Toast.LENGTH_SHORT).show()
            },
            onConnectionStatusChanged = { connected, statusText ->
                isConnected = connected
                binding.tvConnBadge.text = "● $statusText"
                if (connected) {
                    binding.tvConnBadge.setBackgroundColor(Color.parseColor("#D1FAE5"))
                    binding.tvConnBadge.setTextColor(Color.parseColor("#059669"))
                    flushPendingOfflineCache()
                } else {
                    binding.tvConnBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
                    binding.tvConnBadge.setTextColor(Color.parseColor("#D97706"))
                }
            }
        )

        binding.btnConnect.setOnClickListener {
            when (binding.spConnectionMode.selectedItemPosition) {
                0 -> {
                    networkManager.connectBluetoothHc05("00:21:13:04:22:90", currentUsername, lifecycleScope)
                    recordEvent("LOCKED", 0, "[SYS]", "Socket RFCOMM HC-05 iniciado para $currentUsername")
                }
                1 -> {
                    networkManager.startMasterServer(currentUsername, lifecycleScope)
                    recordEvent("LOCKED", 0, "[SYS]", "Servidor Maestro Dual Android iniciado")
                }
                2 -> {
                    val ip = binding.etMasterIp.text.toString().trim()
                    networkManager.connectToMaster(ip, currentUsername, lifecycleScope)
                    recordEvent("LOCKED", 0, "[SYS]", "Conectando a Estación Maestra ($ip)")
                }
            }
        }

        binding.btnSimulateFailSafe.setOnClickListener {
            lifecycleScope.launch {
                isConnected = false
                binding.tvConnBadge.text = "● Modo Avión / Sin Red"
                binding.tvConnBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
                binding.tvConnBadge.setTextColor(Color.parseColor("#DC2626"))
                applyHmiVisualState("LOCKED", 0)

                db.insertLog(
                    nowTime(),
                    "[ALERTA]",
                    "Alarma activada y puerta bloqueada en Modo Avión (Caché synced=false)",
                    synced = false
                )
                refreshLogsView()
                Log.w("OTSEC_LOGCAT", "Modo Avión activo: Evento guardado en caché SQLite con booleano synced=false. Esperando red...")
                Toast.makeText(this@MainActivity, "Modo Avión: Evento guardado en caché local (synced=false). Reconectando en 2s...", Toast.LENGTH_SHORT).show()

                delay(2000)

                isConnected = true
                binding.tvConnBadge.text = "● Reconectado (2s)"
                binding.tvConnBadge.setBackgroundColor(Color.parseColor("#D1FAE5"))
                binding.tvConnBadge.setTextColor(Color.parseColor("#059669"))
                flushPendingOfflineCache()
            }
        }
    }

    private fun flushPendingOfflineCache() {
        val pendingLogs = db.getUnsyncedLogs()
        if (pendingLogs.isNotEmpty()) {
            val summary = pendingLogs.joinToString("\n") { "• ${it.timestamp} ${it.tag} ${it.message}" }
            db.markAllLogsAsSynced()
            db.insertLog(nowTime(), "[SYS]", "Reconexión 2s ($currentUsername): ${pendingLogs.size} evento(s) entregados (synced=true)", synced = true)
            refreshLogsView()

            Log.i("OTSEC_LOGCAT", "Reconexión Fail-Safe (2s) para $currentUsername -> Booleano synced=true. Push-Up mostrado.")
            AlertDialog.Builder(this)
                .setTitle("🔔 Notificación Push-Up OT (Caché Sincronizada)")
                .setMessage("Reconectado en 2 segundos (Usuario: $currentUsername).\n\nEventos retenidos en caché SQLite durante desconexión/Modo Avión (synced: false -> true):\n\n$summary")
                .setPositiveButton("Confirmar Recepción", null)
                .show()
        }
    }

    private fun setupHmiControls() {
        binding.btnOpenO.setOnClickListener {
            autoLockTimer?.cancel()
            recordEvent("OPEN", 90, "[RFID]", "Puerta ABIERTA 90° - UID 4A 1B 8F C2 (Comando 'O')")
            if (binding.swAutoLock.isChecked) {
                startThreeSecondAutoLock()
            }
        }

        binding.btnLockC.setOnClickListener {
            autoLockTimer?.cancel()
            recordEvent("LOCKED", 0, "[ESTADO]", "Puerta CERRADA / BLOQUEADA 0° (Comando 'C')")
        }

        binding.btnAlarmR.setOnClickListener {
            autoLockTimer?.cancel()
            recordEvent("ALARM", 0, "[ALERTA]", "INTRUSIÓN DETECTADA - Alarma Activa (Comando 'R')")
        }

        binding.sbServoAngle.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvAngleValue.text = "${progress}°"
                if (fromUser) {
                    val state = if (progress >= 45) "OPEN" else "LOCKED"
                    applyHmiVisualState(state, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val angle = seekBar?.progress ?: 0
                recordEvent(if (angle >= 45) "OPEN" else "LOCKED", angle, "[ESTADO]", "Válvula/Servo ajustado a ${angle}°")
            }
        })

        binding.btnManageUsers.setOnClickListener {
            showCreateUserDialog()
        }

        binding.btnLogout.setOnClickListener {
            finish()
        }
    }

    private fun startThreeSecondAutoLock() {
        autoLockTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000) + 1
                binding.tvHmiTitle.text = "ACCESO AUTORIZADO (90°) -\nCierre en ${sec}s"
            }

            override fun onFinish() {
                recordEvent("LOCKED", 0, "[ESTADO]", "Auto-Lock (3s) -> Puerta cerrada automáticamente (0°)")
            }
        }.start()
    }

    private fun recordEvent(state: String, angle: Int, tag: String, msg: String) {
        val time = nowTime()
        db.insertLog(time, tag, msg, synced = isConnected)
        Log.i("OTSEC_LOGCAT", "Usuario=$currentUsername($currentRole) | $time $tag $msg | Estado=$state | Ángulo=${angle}° | Synced=$isConnected")
        applyHmiVisualState(state, angle)
        refreshLogsView()
        if (isConnected) {
            networkManager.sendPacket(state, angle, tag, msg, lifecycleScope)
        }
    }

    private fun applyHmiVisualState(state: String, angle: Int) {
        currentHmiState = state
        binding.sbServoAngle.progress = angle
        binding.tvAngleValue.text = "${angle}°"
        when (state) {
            "OPEN" -> {
                binding.cardHmiState.setCardBackgroundColor(Color.parseColor("#10B981"))
                binding.tvHmiHeaderRight.text = "ABIERTO · ${angle}°"
                binding.tvHmiIcon.text = "🔓"
                binding.tvHmiTitle.text = "ACCESO AUTORIZADO (${angle}°) -\nCierre en 3s"
            }
            "ALARM" -> {
                binding.cardHmiState.setCardBackgroundColor(Color.parseColor("#EF4444"))
                binding.tvHmiHeaderRight.text = "FAIL-SECURE · 0°"
                binding.tvHmiIcon.text = "🚨"
                binding.tvHmiTitle.text = "ACCESO DENEGADO - Alarma Activa"
            }
            else -> {
                val lockedColor = if (isDarkMode) "#2A2D34" else "#334155"
                binding.cardHmiState.setCardBackgroundColor(Color.parseColor(lockedColor))
                binding.tvHmiHeaderRight.text = "FAIL-SECURE · ${angle}°"
                binding.tvHmiIcon.text = "🔒"
                binding.tvHmiTitle.text = "SISTEMA BLOQUEADO (${angle}°) - Fail-Secure"
            }
        }
    }

    private fun setupLogFilter() {
        binding.etFilterLogs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshLogsView()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun refreshLogsView() {
        val query = binding.etFilterLogs.text.toString().trim()
        val logs = db.getLogs(query)
        val formatted = logs.takeLast(12).joinToString("\n") {
            val syncTag = if (it.synced) "✓" else "⏳(Caché)"
            "${it.timestamp} ${it.tag} ${it.message} [$syncTag]"
        }
        binding.tvTerminalLogs.text = if (formatted.isEmpty()) "Sin coincidencias en SQLite..." else formatted
        binding.tvLogCount.text = "${logs.size} eventos · ${nowTime()}"
    }

    private fun showCreateUserDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val etNewUser = EditText(this).apply { hint = "Nuevo usuario (ej: operador2)" }
        val etNewPass = EditText(this).apply { hint = "Contraseña segura (mín 8 chars, Mayús+Num)" }
        val etNewRole = EditText(this).apply {
            hint = "Rol: OPERADOR o AUDITOR"
            setText("OPERADOR")
        }
        layout.addView(etNewUser)
        layout.addView(etNewPass)
        layout.addView(etNewRole)

        AlertDialog.Builder(this)
            .setTitle("Crear Usuario (Exclusivo Administrador OT)")
            .setView(layout)
            .setPositiveButton("Guardar con SHA-256 + Salt") { _, _ ->
                val u = etNewUser.text.toString().trim()
                val p = etNewPass.text.toString()
                val r = etNewRole.text.toString().trim().uppercase()

                if (!SecurityUtils.isStrongPassword(p)) {
                    Toast.makeText(
                        this,
                        "Rechazada: No se permiten PINs débiles (ej. 1234). Requiere mín. 8 caracteres, mayúscula y número.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }

                val created = db.createUser(u, p, if (r == "AUDITOR") "AUDITOR" else "OPERADOR")
                if (created) {
                    recordEvent("LOCKED", 0, "[SEC]", "Admin creó usuario '$u' ($r) con SHA-256+Salt")
                    Toast.makeText(this, "Usuario '$u' creado y cifrado con SHA-256 + Salt", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "El usuario '$u' ya existe", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun nowTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()
        autoLockTimer?.cancel()
        networkManager.stop()
    }
}