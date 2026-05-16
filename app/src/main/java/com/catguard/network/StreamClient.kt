package com.catguard.network

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

/**
 * Запускається на пристрої-глядачі.
 * Підключається до ws://IP_КАМЕРИ:8765
 */
class StreamClient(
    serverUri: URI,
    val cameraId: String,
    private val onStatus: (cameraId: String, status: String) -> Unit,
    private val onConnect: (cameraId: String) -> Unit,
    private val onDisconnect: (cameraId: String) -> Unit
) : WebSocketClient(serverUri) {

    override fun onOpen(handshake: ServerHandshake) {
        Log.i("StreamClient", "Підключено до камери $cameraId")
        onConnect(cameraId)
    }

    override fun onMessage(message: String) {
        // Формат: "STATUS:OBJECT_DETECTED" або "STATUS:OBJECT_LOST"
        if (message.startsWith("STATUS:")) {
            val status = message.removePrefix("STATUS:")
            Log.d("StreamClient", "Камера $cameraId: $status")
            onStatus(cameraId, status)
        }
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        Log.w("StreamClient", "Камера $cameraId відключилась")
        onDisconnect(cameraId)
    }

    override fun onError(ex: Exception) {
        Log.e("StreamClient", "Помилка камери $cameraId: ${ex.message}")
        onDisconnect(cameraId)
    }
}
