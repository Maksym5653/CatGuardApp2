package com.catguard.camera

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.catguard.databinding.ActivityCameraBinding
import com.catguard.ml.CatDetector
import com.catguard.network.StreamServer
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var b: ActivityCameraBinding
    private lateinit var detector: CatDetector
    private lateinit var server: StreamServer
    private val executor = Executors.newSingleThreadExecutor()

    // 4-значний код для підключення глядача
    private val code = (1000..9999).random().toString()

    @Volatile private var lastBitmap: android.graphics.Bitmap? = null
    private var lastStatus = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b.tvCode.text = "Код: $code"
        b.tvStatus.text = "Запуск..."

        // Запустити WebSocket сервер (порт 8765)
        server = StreamServer(8765)
        server.onClientCount = { count ->
            runOnUiThread {
                val ip = getLocalIp()
                b.tvCode.text = "Код: $code  |  IP: $ip:8765  |  Глядачів: $count"
            }
        }
        server.start()

        // Ініціалізація TFLite
        detector = CatDetector(this)
        lifecycleScope.launch(Dispatchers.IO) { detector.init() }

        startCamera()
        startDetectionLoop()
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(b.previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(executor) { proxy ->
                lastBitmap = proxy.toBitmap()
                proxy.close()
            }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)

        }, ContextCompat.getMainExecutor(this))
    }

    private fun startDetectionLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000) // Аналіз кожну секунду

                val bmp = lastBitmap ?: continue
                val catFound = detector.hasCat(bmp)
                val status = if (catFound) "OBJECT_DETECTED" else "OBJECT_LOST"

                // Відправляємо статус тільки при зміні
                if (status != lastStatus) {
                    lastStatus = status
                    server.broadcast(status)
                }

                withContext(Dispatchers.Main) {
                    if (catFound) {
                        b.tvStatus.text = "🐱 КІТ ЗНАЙДЕНИЙ"
                        b.tvStatus.setBackgroundColor(0xFF1B5E20.toInt())
                    } else {
                        b.tvStatus.text = "👁 Сканування... кота немає"
                        b.tvStatus.setBackgroundColor(0xFF333333.toInt())
                    }
                }
            }
        }
    }

    private fun getLocalIp(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val ip = wm.connectionInfo.ipAddress
            "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        } catch (e: Exception) { "???" }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        detector.close()
        try { server.stop() } catch (e: Exception) {}
    }
}
