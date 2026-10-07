package com.example.pptrunner

import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    // =========================================================
    // UI
    // =========================================================

    private lateinit var mainRoot: View

    private lateinit var heroCard: FrameLayout
    private lateinit var heroImage: ImageView

    private lateinit var txtStatus: TextView
    private lateinit var txtStatusDot: TextView
    private lateinit var txtConnectionMode: TextView
    private lateinit var txtDescription: TextView
    private lateinit var txtRemoteLabel: TextView

    private lateinit var connectionStatusRow: View
    private lateinit var connectionSpinner: ProgressBar

    private lateinit var commandPanel: View

    private lateinit var scanButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var infoButton: Button

    // =========================================================
    // HERO ANIMATION
    // =========================================================

    private var heroAnimator: ObjectAnimator? = null

    // =========================================================
    // SERVICE
    // =========================================================

    private var pptRunnerService: PptRunnerService? = null
    private var serviceBound = false

    // Keep one listener instance.
    private val stateListener:
                (ConnectionState) -> Unit = { state ->

        runOnUiThread {
            updateConnectionUi(state)
        }
    }

    // =========================================================
    // SERVICE CONNECTION
    // =========================================================

    private val serviceConnection =
        object : ServiceConnection {

            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?
            ) {

                val binder =
                    service as? PptRunnerService.LocalBinder

                if (binder == null) {
                    serviceBound = false
                    return
                }

                pptRunnerService =
                    binder.getService()

                serviceBound = true

                pptRunnerService?.registerStateListener(
                    stateListener
                )

                updateConnectionUi(
                    pptRunnerService?.getConnectionState()
                        ?: ConnectionState.READY
                )
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {

                pptRunnerService?.unregisterStateListener(
                    stateListener
                )

                pptRunnerService = null
                serviceBound = false

                updateConnectionUi(
                    ConnectionState.DISCONNECTED
                )
            }
        }

    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        // -----------------------------------------------------
        // VIEW REFERENCES
        // -----------------------------------------------------

        mainRoot =
            findViewById(R.id.mainRoot)

        heroCard =
            findViewById(R.id.heroCard)

        heroImage =
            findViewById(R.id.heroImage)

        txtStatus =
            findViewById(R.id.txtStatus)

        txtStatusDot =
            findViewById(R.id.txtStatusDot)

        connectionStatusRow =
            findViewById(R.id.connectionStatusRow)

        connectionSpinner =
            findViewById(R.id.connectionSpinner)

        txtConnectionMode =
            findViewById(R.id.txtConnectionMode)

        txtDescription =
            findViewById(R.id.txtDescription)

        txtRemoteLabel =
            findViewById(R.id.txtRemoteLabel)

        commandPanel =
            findViewById(R.id.commandPanel)

        scanButton =
            findViewById(R.id.btnScan)

        disconnectButton =
            findViewById(R.id.btnDisconnect)

        infoButton =
            findViewById(R.id.btnInfo)

        // -----------------------------------------------------
        // Remove default Android button tinting.
        // -----------------------------------------------------

        scanButton.backgroundTintList = null
        disconnectButton.backgroundTintList = null

        // -----------------------------------------------------
        // Window insets.
        // -----------------------------------------------------

        applySystemBarInsets()

        // -----------------------------------------------------
        // Start service.
        // -----------------------------------------------------

        startPptRunnerService()

        // -----------------------------------------------------
        // QR scanning.
        // -----------------------------------------------------

        setupQrScanner()

        // -----------------------------------------------------
        // Disconnect.
        // -----------------------------------------------------

        disconnectButton.setOnClickListener {

            pptRunnerService?.disconnectSession()
        }

        // -----------------------------------------------------
        // Info.
        // -----------------------------------------------------

        infoButton.setOnClickListener {

            showInfoDialog()
        }

        // -----------------------------------------------------
        // Initial UI.
        // -----------------------------------------------------

        updateConnectionUi(
            ConnectionState.READY
        )
    }

    // =========================================================
    // SYSTEM BAR INSETS
    // =========================================================

    private fun applySystemBarInsets() {

        val baseLeft =
            mainRoot.paddingLeft

        val baseTop =
            mainRoot.paddingTop

        val baseRight =
            mainRoot.paddingRight

        val baseBottom =
            mainRoot.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(
            mainRoot
        ) { view, insets ->

            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout()
                )

            view.setPadding(
                baseLeft,
                baseTop + systemBars.top,
                baseRight,
                baseBottom + systemBars.bottom
            )

            insets
        }

        ViewCompat.requestApplyInsets(
            mainRoot
        )
    }

    // =========================================================
    // ACTIVITY START
    // =========================================================

    override fun onStart() {

        super.onStart()

        val serviceIntent =
            Intent(
                this,
                PptRunnerService::class.java
            )

        bindService(
            serviceIntent,
            serviceConnection,
            BIND_AUTO_CREATE
        )
    }

    // =========================================================
    // ACTIVITY STOP
    // =========================================================

    override fun onStop() {

        if (serviceBound) {

            pptRunnerService?.unregisterStateListener(
                stateListener
            )

            try {

                unbindService(
                    serviceConnection
                )

            } catch (_: IllegalArgumentException) {
                // Already unbound.
            }

            serviceBound = false
            pptRunnerService = null
        }

        super.onStop()
    }

    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        stopHeroAnimation()

        super.onDestroy()
    }

    // =========================================================
    // START FOREGROUND SERVICE
    // =========================================================

    private fun startPptRunnerService() {

        val serviceIntent =
            Intent(
                this,
                PptRunnerService::class.java
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            startForegroundService(
                serviceIntent
            )

        } else {

            startService(
                serviceIntent
            )
        }
    }

    // =========================================================
    // QR SCANNER
    // =========================================================

    private fun setupQrScanner() {

        val scanner =
            GmsBarcodeScanning.getClient(
                this
            )

        scanButton.setOnClickListener {

            scanner.startScan()

                .addOnSuccessListener { barcode ->

                    val qrText =
                        barcode.rawValue

                    if (
                        qrText != null &&
                        qrText.startsWith("{")
                    ) {

                        try {

                            val json =
                                JSONObject(qrText)

                            val localUrl =
                                json.getString(
                                    "local"
                                )

                            val relayBase =
                                json.getString(
                                    "relay"
                                )

                            val sessionId =
                                json.getString(
                                    "sid"
                                )

                            val relayUrl =
                                "$relayBase/mobile/$sessionId"

                            Toast.makeText(
                                this,
                                "Connecting...",
                                Toast.LENGTH_SHORT
                            ).show()

                            val updateIntent =
                                Intent(
                                    this,
                                    PptRunnerService::class.java
                                ).apply {

                                    putExtra(
                                        "LOCAL_URL",
                                        localUrl
                                    )

                                    putExtra(
                                        "RELAY_URL",
                                        relayUrl
                                    )
                                }

                            startService(
                                updateIntent
                            )

                        } catch (
                            _: Exception
                        ) {

                            Toast.makeText(
                                this,
                                "Invalid QR Format",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                    } else {

                        Toast.makeText(
                            this,
                            "Not a PPT Runner QR",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                .addOnFailureListener {

                    Toast.makeText(
                        this,
                        "Scan cancelled",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    // =========================================================
    // CONNECTION UI
    // =========================================================

    private fun updateConnectionUi(
        state: ConnectionState
    ) {

        when (state) {

            // =================================================
            // READY
            // =================================================

            ConnectionState.READY -> {

                stopHeroAnimation()

                heroCard.visibility =
                    View.VISIBLE

                heroImage.alpha =
                    1f

                txtStatus.text =
                    "READY TO CONNECT"

                txtDescription.text =
                    "Scan the QR shown on the presentation computer"

                txtStatusDot.text =
                    "●"

                txtStatusDot.setTextColor(
                    Color.rgb(
                        102,
                        114,
                        129
                    )
                )

                connectionStatusRow.visibility =
                    View.GONE

                txtRemoteLabel.visibility =
                    View.GONE

                commandPanel.visibility =
                    View.GONE

                scanButton.visibility =
                    View.VISIBLE

                scanButton.isEnabled =
                    true

                disconnectButton.visibility =
                    View.GONE

                startHeroPulse()

                animateText()
            }

            // =================================================
            // CONNECTING LOCAL
            // =================================================

            ConnectionState.CONNECTING_LOCAL -> {

                heroCard.visibility =
                    View.VISIBLE

                heroImage.alpha =
                    0.75f

                txtStatus.text =
                    "CONNECTING"

                txtDescription.text =
                    "Connecting to the presentation computer"

                txtStatusDot.text =
                    "●"

                txtStatusDot.setTextColor(
                    Color.rgb(
                        255,
                        193,
                        7
                    )
                )

                connectionStatusRow.visibility =
                    View.VISIBLE

                connectionSpinner.visibility =
                    View.VISIBLE

                txtConnectionMode.text =
                    "LOCAL NETWORK"

                txtConnectionMode.setTextColor(
                    Color.rgb(
                        255,
                        193,
                        7
                    )
                )

                txtRemoteLabel.visibility =
                    View.GONE

                commandPanel.visibility =
                    View.GONE

                scanButton.visibility =
                    View.GONE

                disconnectButton.visibility =
                    View.VISIBLE

                startHeroPulse()

                animateText()
            }

            // =================================================
            // CONNECTING RELAY
            // =================================================

            ConnectionState.CONNECTING_RELAY -> {

                heroCard.visibility =
                    View.VISIBLE

                heroImage.alpha =
                    0.65f

                txtStatus.text =
                    "CONNECTING"

                txtDescription.text =
                    "Switching to secure relay"

                txtStatusDot.text =
                    "●"

                txtStatusDot.setTextColor(
                    Color.rgb(
                        255,
                        193,
                        7
                    )
                )

                connectionStatusRow.visibility =
                    View.VISIBLE

                connectionSpinner.visibility =
                    View.VISIBLE

                txtConnectionMode.text =
                    "SECURE RELAY"

                txtConnectionMode.setTextColor(
                    Color.rgb(
                        255,
                        193,
                        7
                    )
                )

                txtRemoteLabel.visibility =
                    View.GONE

                commandPanel.visibility =
                    View.GONE

                scanButton.visibility =
                    View.GONE

                disconnectButton.visibility =
                    View.VISIBLE

                startHeroPulse()

                animateText()
            }

            // =================================================
            // CONNECTED LOCAL
            // =================================================

            ConnectionState.CONNECTED_LOCAL -> {

                stopHeroAnimation()

                heroCard.visibility =
                    View.GONE

                txtStatus.text =
                    "CONNECTED"

                txtDescription.text =
                    "Presentation ready"

                txtStatusDot.text =
                    "●"

                txtStatusDot.setTextColor(
                    Color.rgb(
                        0,
                        230,
                        118
                    )
                )

                connectionStatusRow.visibility =
                    View.VISIBLE

                connectionSpinner.visibility =
                    View.GONE

                txtConnectionMode.text =
                    "● LOCAL"

                txtConnectionMode.setTextColor(
                    Color.rgb(
                        4,
                        217,
                        245
                    )
                )

                txtRemoteLabel.visibility =
                    View.VISIBLE

                commandPanel.visibility =
                    View.VISIBLE

                scanButton.visibility =
                    View.GONE

                disconnectButton.visibility =
                    View.VISIBLE

                animateConnectedContent()
            }

            // =================================================
            // CONNECTED RELAY
            // =================================================

            ConnectionState.CONNECTED_RELAY -> {

                stopHeroAnimation()

                heroCard.visibility =
                    View.GONE

                txtStatus.text =
                    "CONNECTED"

                txtDescription.text =
                    "Presentation ready"

                txtStatusDot.text =
                    "●"

                txtStatusDot.setTextColor(
                    Color.rgb(
                        0,
                        230,
                        118
                    )
                )

                connectionStatusRow.visibility =
                    View.VISIBLE

                connectionSpinner.visibility =
                    View.GONE

                txtConnectionMode.text =
                    "● RELAY"

                txtConnectionMode.setTextColor(
                    Color.rgb(
                        4,
                        217,
                        245
                    )
                )

                txtRemoteLabel.visibility =
                    View.VISIBLE

                commandPanel.visibility =
                    View.VISIBLE

                scanButton.visibility =
                    View.GONE

                disconnectButton.visibility =
                    View.VISIBLE

                animateConnectedContent()
            }

            // =================================================
            // DISCONNECTED
            // =================================================

            ConnectionState.DISCONNECTED -> {

                stopHeroAnimation()

                heroCard.visibility =
                    View.VISIBLE

                heroImage.alpha =
                    0.55f

                txtStatus.text =
                    "DISCONNECTED"

                txtDescription.text =
                    "Scan the QR to start a new session"

                txtStatusDot.text =
                    "●"

                txtStatusDot.setTextColor(
                    Color.rgb(
                        255,
                        101,
                        112
                    )
                )

                connectionStatusRow.visibility =
                    View.GONE

                txtRemoteLabel.visibility =
                    View.GONE

                commandPanel.visibility =
                    View.GONE

                scanButton.visibility =
                    View.VISIBLE

                scanButton.isEnabled =
                    true

                disconnectButton.visibility =
                    View.GONE

                animateText()
            }
        }
    }

    // =========================================================
    // HERO PULSE
    // =========================================================

    private fun startHeroPulse() {

        stopHeroAnimation()

        heroAnimator =
            ObjectAnimator.ofFloat(
                heroCard,
                View.SCALE_X,
                1f,
                1.008f
            ).apply {

                duration = 1800L

                repeatCount =
                    ObjectAnimator.INFINITE

                repeatMode =
                    ObjectAnimator.REVERSE

                interpolator =
                    AccelerateDecelerateInterpolator()

                start()
            }

        heroCard.scaleY = 1f
    }

    // =========================================================
    // STOP HERO ANIMATION
    // =========================================================

    private fun stopHeroAnimation() {

        heroAnimator?.cancel()
        heroAnimator = null

        heroCard.animate().cancel()

        heroCard.scaleX = 1f
        heroCard.scaleY = 1f
    }

    // =========================================================
    // CONNECTED CONTENT ANIMATION
    // =========================================================

    private fun animateConnectedContent() {

        commandPanel.animate().cancel()
        disconnectButton.animate().cancel()

        commandPanel.alpha = 0f
        commandPanel.translationY = 16f

        commandPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260L)
            .setInterpolator(
                AccelerateDecelerateInterpolator()
            )
            .start()

        disconnectButton.alpha = 0f
        disconnectButton.translationY = 12f

        disconnectButton.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300L)
            .setStartDelay(60L)
            .setInterpolator(
                AccelerateDecelerateInterpolator()
            )
            .start()

        animateText()
    }

    // =========================================================
    // TEXT ANIMATION
    // =========================================================

    private fun animateText() {

        txtStatus.animate().cancel()
        txtDescription.animate().cancel()

        txtStatus.alpha = 0f
        txtDescription.alpha = 0f

        txtStatus.animate()
            .alpha(1f)
            .setDuration(160L)
            .setInterpolator(
                AccelerateDecelerateInterpolator()
            )
            .start()

        txtDescription.animate()
            .alpha(1f)
            .setDuration(220L)
            .setStartDelay(30L)
            .setInterpolator(
                AccelerateDecelerateInterpolator()
            )
            .start()
    }

    // =========================================================
    // INFO
    // =========================================================

    private fun showInfoDialog() {

        val message = """
            PPT Runner
            
            Presentation Remote
            
            Made in India 🇮🇳
            
            Designed & Developed by
            Hemant Verma
            
            © 2026 PPT Runner
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("PPT Runner")
            .setMessage(message)
            .setPositiveButton(
                "CLOSE",
                null
            )
            .setNeutralButton(
                "LINKEDIN"
            ) { _, _ ->

                try {

                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                "https://www.linkedin.com/in/hemant-verma-ind/"
                            )
                        )
                    )

                } catch (_: Exception) {

                    Toast.makeText(
                        this,
                        "Unable to open LinkedIn",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }
}