package com.catguard.viewer

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.catguard.databinding.ActivityViewerBinding
import com.catguard.network.Esp32
import com.catguard.network.StreamClient
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class ViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityViewerBinding

    // cameraId -> статус ("OBJECT_DETECTED" або "OBJECT_LOST")
    private val statuses = ConcurrentHashMap<String, String>()
    // cameraId -> клієнт
    private val clients = ConcurrentHashMap<String, StreamClient>()

    private var alarmOn = false
    private var alarmJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b.btnAdd.setOnClickListener { showAddDialog() }
        b.btnDisconnect.setOnClickListener { disconnectAll() }
        b.btnTest.setOnClickListener {
            lifecycleScope.launch {
                Esp32.alarmOn()
                b.tvAlarm.text = "🔴 ТЕСТ ТРИВОГИ"
                b.tvAlarm.setBackgroundColor(0xFFFF1744.toInt())
                delay(3000)
                Esp32.alarmOff()
                updateUI()
            }
        }

        updateUI()
    }

    // ─── Діалог підключення до камери ───────────────────────────────────────
    private fun showAddDialog() {
        if (clients.size >= 2) {
            android.widget.Toast.makeText(this, "Максимум 2 камери", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            hint = "IP адреса камери (наприклад: 192.168.1.55)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(40, 20, 40, 20)
        }

        AlertDialog.Builder(this)
            .setTitle("Підключити камеру")
            .setMessage("Введіть IP адресу телефону-камери.\nIP видно на екрані Camera Mode.")
            .setView(input)
            .setPositiveButton("Підключити") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) connectCamera(ip)
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    // ─── Підключення до камери ───────────────────────────────────────────────
    private fun connectCamera(ip: String) {
        val id = ip  // використовуємо IP як ідентифікатор
        if (clients.containsKey(id)) {
            android.widget.Toast.makeText(this, "Вже підключено до $ip", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val uri = URI("ws://$ip:8765")
        val client = StreamClient(
            serverUri = uri,
            cameraId = id,
            onStatus = { camId, status -> onCameraStatus(camId, status) },
            onConnect = { camId ->
                statuses[camId] = "OBJECT_DETECTED"  // за замовчуванням "бачимо кота"
                runOnUiThread { updateUI() }
            },
            onDisconnect = { camId ->
                clients.remove(camId)
                statuses.remove(camId)
                runOnUiThread {
                    updateUI()
                    android.widget.Toast.makeText(this, "Камера $camId відключилась", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )

        clients[id] = client
        client.connect()
        android.widget.Toast.makeText(this, "Підключаюсь до $ip...", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ─── Логіка сигналізації ─────────────────────────────────────────────────
    private fun onCameraStatus(cameraId: String, status: String) {
        statuses[cameraId] = status

        val allLost  = statuses.isNotEmpty() && statuses.values.all { it == "OBJECT_LOST" }
        val anyFound = statuses.values.any { it == "OBJECT_DETECTED" }

        when {
            anyFound -> {
                // Кіт є — скасувати тривогу
                alarmJob?.cancel()
                if (alarmOn) {
                    alarmOn = false
                    lifecycleScope.launch { Esp32.alarmOff() }
                }
            }
            allLost -> {
                // Всі камери втратили кота — чекаємо 2 сек і активуємо
                alarmJob?.cancel()
                alarmJob = lifecycleScope.launch {
                    delay(2000)
                    val stillAllLost = statuses.values.all { it == "OBJECT_LOST" }
                    if (stillAllLost && !alarmOn) {
                        alarmOn = true
                        Esp32.alarmOn()
                    }
                }
            }
        }

        runOnUiThread { updateUI() }
    }

    // ─── Оновлення UI ────────────────────────────────────────────────────────
    private fun updateUI() {
        val count = clients.size

        // Показати/сховати відео (placeholder поки без реального відео)
        when (count) {
            0 -> {
                b.videoView1.visibility = View.GONE
                b.videoView2.visibility = View.GONE
                b.divider.visibility   = View.GONE
                b.tvEmpty.visibility   = View.VISIBLE
            }
            1 -> {
                b.videoView1.visibility = View.VISIBLE
                b.videoView2.visibility = View.GONE
                b.divider.visibility    = View.GONE
                b.tvEmpty.visibility    = View.GONE
            }
            else -> {
                b.videoView1.visibility = View.VISIBLE
                b.videoView2.visibility = View.VISIBLE
                b.divider.visibility    = View.VISIBLE
                b.tvEmpty.visibility    = View.GONE
            }
        }

        // Статусний рядок
        if (alarmOn) {
            b.tvAlarm.text = "🚨 ТРИВОГА! Кіт зник з усіх камер!"
            b.tvAlarm.setBackgroundColor(0xFFFF1744.toInt())
            return
        }

        if (statuses.isEmpty()) {
            b.tvAlarm.text = "Нема підключених камер"
            b.tvAlarm.setBackgroundColor(0xFF333333.toInt())
            return
        }

        val lines = statuses.entries.mapIndexed { i, (id, status) ->
            val icon = if (status == "OBJECT_DETECTED") "🐱" else "👁"
            "Камера ${i + 1} ($id): $icon $status"
        }.joinToString("\n")

        val allOk = statuses.values.all { it == "OBJECT_DETECTED" }
        b.tvAlarm.text = lines
        b.tvAlarm.setBackgroundColor(
            if (allOk) 0xFF1B5E20.toInt() else 0xFFE65100.toInt()
        )
    }

    private fun disconnectAll() {
        clients.values.forEach { try { it.close() } catch (e: Exception) {} }
        clients.clear()
        statuses.clear()
        alarmJob?.cancel()
        if (alarmOn) {
            alarmOn = false
            lifecycleScope.launch { Esp32.alarmOff() }
        }
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectAll()
        Esp32.reset()
    }
}
