package com.example.pptrunner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

enum class ConnectionState {
    READY,
    CONNECTING_LOCAL,
    CONNECTING_RELAY,
    CONNECTED_LOCAL,
    CONNECTED_RELAY,
    DISCONNECTED
}

class PptRunnerService : Service() {

    companion object {
        private const val TAG = "PptRunner"
        private const val CHANNEL_ID = "ppt_runner_channel"
        private const val NOTIFICATION_ID = 1
    }

    // ---------------------------------------------------------
    // SERVICE BINDER
    // ---------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): PptRunnerService {
            return this@PptRunnerService
        }
    }

    private val binder = LocalBinder()

    // ---------------------------------------------------------
    // UI STATE
    // ---------------------------------------------------------

    @Volatile
    private var connectionState = ConnectionState.READY

    private val stateListeners =
        CopyOnWriteArraySet<(ConnectionState) -> Unit>()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getConnectionState(): ConnectionState {
        return connectionState
    }

    fun registerStateListener(
        listener: (ConnectionState) -> Unit
    ) {
        stateListeners.add(listener)

        // Immediately give the Activity the current state.
        mainHandler.post {
            listener(connectionState)
        }
    }

    fun unregisterStateListener(
        listener: (ConnectionState) -> Unit
    ) {
        stateListeners.remove(listener)
    }

    private fun setConnectionState(
        newState: ConnectionState
    ) {
        if (connectionState == newState) {
            return
        }

        connectionState = newState

        Log.d(
            TAG,
            "Connection state -> $newState"
        )

        updateForegroundNotification(newState)

        // UI callbacks always happen on the main thread.
        mainHandler.post {
            for (listener in stateListeners) {
                listener(newState)
            }
        }
    }

    // ---------------------------------------------------------
    // MEDIA SESSION
    // ---------------------------------------------------------

    private var mediaSession: MediaSessionCompat? = null

    // ---------------------------------------------------------
    // WEBSOCKET
    // ---------------------------------------------------------

    private var webSocket: WebSocket? = null

    private val client = OkHttpClient()

    private var localUrl: String? = null
    private var relayUrl: String? = null

    // Thread-safe sequence counter.
    private val sequenceCounter = AtomicInteger(0)

    // ---------------------------------------------------------
    // SERVICE LIFECYCLE
    // ---------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Service Created")

        // Must happen immediately.
        showForegroundNotification()

        setupMediaSession()

        setConnectionState(
            ConnectionState.READY
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val newLocalUrl =
            intent?.getStringExtra("LOCAL_URL")

        val newRelayUrl =
            intent?.getStringExtra("RELAY_URL")

        if (newLocalUrl != null && newRelayUrl != null) {

            localUrl = newLocalUrl
            relayUrl = newRelayUrl

            Log.d(TAG, "Starting connection sequence...")
            Log.d(TAG, "LOCAL URL: $localUrl")
            Log.d(TAG, "RELAY URL: $relayUrl")

            connectLocalFirst()
        }

        return START_STICKY
    }

    // ---------------------------------------------------------
    // FOREGROUND NOTIFICATION
    // ---------------------------------------------------------

    private fun showForegroundNotification() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "PPT Runner Service",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(channel)
        }

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle("PPT Runner")
                .setContentText(
                    "Ready to control presentation"
                )
                .setSmallIcon(
                    android.R.drawable.ic_media_play
                )
                .setOngoing(true)
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .build()

        startForeground(
            NOTIFICATION_ID,
            notification
        )
    }

    private fun updateForegroundNotification(
        state: ConnectionState
    ) {

        val text = when (state) {

            ConnectionState.READY ->
                "Ready to connect"

            ConnectionState.CONNECTING_LOCAL ->
                "Connecting to presentation computer"

            ConnectionState.CONNECTING_RELAY ->
                "Connecting through secure relay"

            ConnectionState.CONNECTED_LOCAL ->
                "Connected via local network"

            ConnectionState.CONNECTED_RELAY ->
                "Connected via relay"

            ConnectionState.DISCONNECTED ->
                "Disconnected"
        }

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle("PPT Runner")
                .setContentText(text)
                .setSmallIcon(
                    android.R.drawable.ic_media_play
                )
                .setOngoing(true)
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .build()

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            notification
        )
    }

    // ---------------------------------------------------------
    // LOCAL CONNECTION
    // ---------------------------------------------------------

    private fun connectLocalFirst() {

        val url = localUrl

        if (url.isNullOrBlank()) {
            Log.e(TAG, "LOCAL URL is missing")
            setConnectionState(
                ConnectionState.DISCONNECTED
            )
            return
        }

        setConnectionState(
            ConnectionState.CONNECTING_LOCAL
        )

        Log.d(
            TAG,
            "Attempting LOCAL connection: $url"
        )

        val request =
            Request.Builder()
                .url(url)
                .build()

        webSocket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response
                    ) {

                        Log.d(
                            TAG,
                            "[LOCAL] Connected to Desktop"
                        )

                        setConnectionState(
                            ConnectionState.CONNECTED_LOCAL
                        )
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?
                    ) {

                        // Ignore callbacks from an old/closed socket.
                        if (this@PptRunnerService.webSocket !== webSocket) {
                            return
                        }

                        if (
                            getConnectionState() ==
                            ConnectionState.DISCONNECTED
                        ) {
                            return
                        }

                        Log.e(
                            TAG,
                            "[LOCAL] Failed. Falling back to RELAY",
                            t
                        )

                        connectRelayFallback()
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        if (this@PptRunnerService.webSocket !== webSocket) {
                            return
                        }

                        Log.d(
                            TAG,
                            "[LOCAL] Closed: $code $reason"
                        )

                        if (
                            getConnectionState() ==
                            ConnectionState.CONNECTED_LOCAL
                        ) {
                            connectRelayFallback()
                        }
                    }
                }
            )
    }

    // ---------------------------------------------------------
    // RELAY CONNECTION
    // ---------------------------------------------------------

    private fun connectRelayFallback() {

        val url = relayUrl

        if (url.isNullOrBlank()) {

            Log.e(
                TAG,
                "RELAY URL is missing"
            )

            setConnectionState(
                ConnectionState.DISCONNECTED
            )

            return
        }

        setConnectionState(
            ConnectionState.CONNECTING_RELAY
        )

        Log.d(
            TAG,
            "Attempting RELAY connection: $url"
        )

        val request =
            Request.Builder()
                .url(url)
                .addHeader(
                    "Bypass-Tunnel-Reminder",
                    "true"
                )
                .build()

        webSocket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response
                    ) {

                        Log.d(
                            TAG,
                            "[RELAY] Connected via Cloud"
                        )

                        setConnectionState(
                            ConnectionState.CONNECTED_RELAY
                        )
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?
                    ) {

                        if (this@PptRunnerService.webSocket !== webSocket) {
                            return
                        }

                        if (
                            getConnectionState() ==
                            ConnectionState.DISCONNECTED
                        ) {
                            return
                        }

                        Log.e(
                            TAG,
                            "[RELAY] Failed. No connection available",
                            t
                        )

                        setConnectionState(
                            ConnectionState.DISCONNECTED
                        )
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        if (this@PptRunnerService.webSocket !== webSocket) {
                            return
                        }

                        Log.d(
                            TAG,
                            "[RELAY] Closed: $code $reason"
                        )

                        if (
                            getConnectionState() ==
                            ConnectionState.CONNECTED_RELAY
                        ) {
                            setConnectionState(
                                ConnectionState.DISCONNECTED
                            )
                        }
                    }
                }
            )
    }

    // ---------------------------------------------------------
    // MEDIA SESSION / VOLUME BUTTONS
    // ---------------------------------------------------------

    private fun setupMediaSession() {

        mediaSession =
            MediaSessionCompat(
                this,
                TAG
            ).apply {

                setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setState(
                            PlaybackStateCompat.STATE_PLAYING,
                            0,
                            1f
                        )
                        .build()
                )

                setPlaybackToRemote(
                    object : VolumeProviderCompat(
                        VOLUME_CONTROL_RELATIVE,
                        100,
                        50
                    ) {

                        override fun onAdjustVolume(
                            direction: Int
                        ) {

                            val sequence =
                                sequenceCounter.incrementAndGet()

                            if (direction == 1) {

                                Log.d(
                                    TAG,
                                    "Vol UP -> NEXT #$sequence"
                                )

                                pulseHaptic()

                                val json =
                                    """{"cmd":"NEXT","seq":$sequence}"""

                                webSocket?.send(json)

                            } else if (direction == -1) {

                                Log.d(
                                    TAG,
                                    "Vol DOWN -> PREVIOUS #$sequence"
                                )

                                pulseHaptic()

                                val json =
                                    """{"cmd":"PREVIOUS","seq":$sequence}"""

                                webSocket?.send(json)
                            }
                        }
                    }
                )

                isActive = true
            }
    }

    // ---------------------------------------------------------
    // HAPTIC FEEDBACK
    // ---------------------------------------------------------

    private fun pulseHaptic() {

        val vibrator: Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                getSystemService(
                    VibratorManager::class.java
                ).defaultVibrator

            } else {

                @Suppress("DEPRECATION")
                getSystemService(
                    Vibrator::class.java
                )
            }

        vibrator?.vibrate(
            VibrationEffect.createOneShot(
                50,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
        )
    }

    // ---------------------------------------------------------
    // BINDING
    // ---------------------------------------------------------

    override fun onBind(
        intent: Intent?
    ): IBinder {

        return binder
    }

    // ---------------------------------------------------------
    // SERVICE DESTROY
    // ---------------------------------------------------------
    fun disconnectSession() {
        setConnectionState(ConnectionState.DISCONNECTED)

        val socket = webSocket

        webSocket = null
        localUrl = null
        relayUrl = null

        sequenceCounter.set(0)

        socket?.close(
            1000,
            "User disconnected"
        )
    }

    override fun onDestroy() {

        Log.d(
            TAG,
            "Service Destroyed"
        )

        setConnectionState(
            ConnectionState.DISCONNECTED
        )

        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null

        webSocket?.close(
            1000,
            "Service destroyed"
        )

        webSocket = null

        client.dispatcher.executorService.shutdown()

        super.onDestroy()
    }
}