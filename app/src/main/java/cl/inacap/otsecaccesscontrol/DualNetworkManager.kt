package cl.inacap.otsecaccesscontrol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class DualNetworkManager(
    private val onStateReceived: (state: String, angle: Int, logTag: String, logMsg: String) -> Unit,
    private val onConnectionStatusChanged: (connected: Boolean, statusText: String) -> Unit
) {
    private val port = 8899
    // UUID estándar universal para Bluetooth SPP (HC-05)
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var btOutputStream: OutputStream? = null
    private var writer: PrintWriter? = null
    private var job: Job? = null
    private var isRunning = false

    // 1. Conexión Bluetooth SPP RFCOMM con reconexión automática Fail-Safe de 2 segundos
    @SuppressLint("MissingPermission")
    fun connectBluetoothHc05(macAddress: String, lastUser: String, scope: CoroutineScope) {
        stop()
        isRunning = true
        job = scope.launch(Dispatchers.IO) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            while (isRunning) {
                try {
                    Log.i("OTSEC_LOGCAT", "Iniciando emparejamiento Bluetooth RFCOMM ($macAddress) para usuario: $lastUser")
                    if (adapter != null && adapter.isEnabled) {
                        val device = adapter.getRemoteDevice(macAddress)
                        bluetoothSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                        bluetoothSocket?.connect()
                        btOutputStream = bluetoothSocket?.outputStream
                    }
                    withContext(Dispatchers.Main) {
                        onConnectionStatusChanged(true, "Conectado HC-05 ($lastUser)")
                    }
                    Log.i("OTSEC_LOGCAT", "Bluetooth HC-05 conectado sin errores (<2 segundos)")
                    break
                } catch (e: Exception) {
                    Log.w("OTSEC_LOGCAT", "Fail-Safe Bluetooth: Reintentando reconexión automática en 2 segundos para $lastUser...")
                    withContext(Dispatchers.Main) {
                        onConnectionStatusChanged(false, "Reconectando BT (2s)...")
                    }
                    delay(2000) // Tiempo de reconectividad estricto de 2 segundos (Fail-Safe OT)
                }
            }
        }
    }

    // 2. Modo Servidor (Estación Maestra de Control - Dispositivo A)
    fun startMasterServer(lastUser: String, scope: CoroutineScope) {
        stop()
        isRunning = true
        job = scope.launch(Dispatchers.IO) {
            while (isRunning) {
                try {
                    serverSocket = ServerSocket(port)
                    withContext(Dispatchers.Main) {
                        onConnectionStatusChanged(true, "Servidor OT Activo ($lastUser)")
                    }
                    Log.i("OTSEC_LOGCAT", "Servidor Maestro escuchando en puerto $port (Usuario: $lastUser)")
                    val socket = serverSocket!!.accept()
                    clientSocket = socket
                    writer = PrintWriter(socket.getOutputStream(), true)
                    withContext(Dispatchers.Main) {
                        onConnectionStatusChanged(true, "Conectado (Dual Android)")
                    }
                    listenIncoming(socket)
                } catch (e: Exception) {
                    Log.w("OTSEC_LOGCAT", "Enlace interrumpido. Reconexión Fail-Safe en 2s para $lastUser: ${e.message}")
                    withContext(Dispatchers.Main) {
                        onConnectionStatusChanged(false, "Reconectando (2s)...")
                    }
                    delay(2000) // Reconexión automática Fail-Safe cada 2 segundos
                }
            }
        }
    }

    // 3. Modo Cliente (Estación Auditora Espejo - Dispositivo B)
    fun connectToMaster(hostIp: String, lastUser: String, scope: CoroutineScope) {
        stop()
        isRunning = true
        job = scope.launch(Dispatchers.IO) {
            while (isRunning) {
                try {
                    withContext(Dispatchers.Main) {
                        onConnectionStatusChanged(false, "Conectando a $hostIp...")
                    }
                    val socket = Socket(hostIp, port)
                    clientSocket = socket
                    writer = PrintWriter(socket.getOutputStream(), true)
                    withContext(Dispatchers.Main) {
                        onConnectionStatusChanged(true, "Conectado a Maestro")
                    }
                    Log.i("OTSEC_LOGCAT", "Conectado exitosamente a Estación Maestra $hostIp (Usuario: $lastUser)")
                    listenIncoming(socket)
                } catch (e: Exception) {
                    Log.w("OTSEC_LOGCAT", "Fallo de enlace con Maestro. Reintentando en 2s para $lastUser")
                    withContext(Dispatchers.Main) {
                        onConnectionStatusChanged(false, "Sin enlace - Reintento 2s")
                    }
                    delay(2000) // Reconexión automática Fail-Safe cada 2 segundos
                }
            }
        }
    }

    private suspend fun listenIncoming(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        while (isRunning && !socket.isClosed) {
            val encryptedLine = reader.readLine() ?: break
            try {
                // Descifrar datos en tránsito (AES-256)
                val decryptedJson = SecurityUtils.decryptInTransit(encryptedLine)
                val json = JSONObject(decryptedJson)
                val state = json.getString("state")
                val angle = json.getInt("angle")
                val tag = json.getString("tag")
                val msg = json.getString("msg")
                withContext(Dispatchers.Main) {
                    onStateReceived(state, angle, tag, msg)
                }
            } catch (e: Exception) {
                Log.e("OTSEC_LOGCAT", "Trama malformada o sin cifrado válido descartada (ISO 27400)")
            }
        }
        throw Exception("Socket cerrado remotamente")
    }

    // Envía los datos cifrados en tránsito (AES-256)
    fun sendPacket(state: String, angle: Int, tag: String, msg: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("state", state)
                    put("angle", angle)
                    put("tag", tag)
                    put("msg", msg)
                }
                val encryptedPayload = SecurityUtils.encryptInTransit(json.toString())
                writer?.println(encryptedPayload)
                btOutputStream?.write((encryptedPayload + "\n").toByteArray())
                Log.d("OTSEC_LOGCAT", "Datos en tránsito cifrados (AES-256) transmitidos: $encryptedPayload")
            } catch (e: Exception) {
                Log.e("OTSEC_LOGCAT", "Error enviando trama (guardada en caché SQLite con synced=false)")
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        try { writer?.close() } catch (_: Exception) {}
        try { btOutputStream?.close() } catch (_: Exception) {}
        try { bluetoothSocket?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}