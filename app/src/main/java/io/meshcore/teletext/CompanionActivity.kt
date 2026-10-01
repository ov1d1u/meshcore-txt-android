package io.meshcore.teletext

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class CompanionActivity : Activity(), MeshCoreBle.Listener {
    private lateinit var ble: MeshCoreBle
    private lateinit var statusRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var scanProgress: ProgressBar
    private lateinit var cards: LinearLayout
    private lateinit var refresh: ImageButton
    private var progressDialog: AlertDialog? = null
    private var scanning = false
    private var connecting = false
    private var connectedAndLeaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        ble = (application as TeletextApplication).ble
        connecting = savedInstanceState?.getBoolean("connecting") ?: false
        buildUi()
        if (connecting) showProgress()
        ble.addListener(this)
        if (!connecting) requestScan()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.companion_background))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 0, 16.dp, 0)
            setBackgroundColor(getColor(R.color.companion_toolbar))
            minimumHeight = 56.dp
        }
        bar.addView(TextView(this).apply {
            text = "Connect via BLE"
            textSize = 20f
            setTextColor(getColor(R.color.companion_primary_text))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        refresh = ImageButton(this).apply {
            setImageResource(R.drawable.ic_refresh)
            imageTintList = ColorStateList.valueOf(getColor(R.color.companion_primary_text))
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            contentDescription = "Refresh companions"
            setOnClickListener { requestScan() }
        }
        bar.addView(refresh, LinearLayout.LayoutParams(48.dp, 56.dp))
        root.addView(bar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 20.dp, 16.dp, 16.dp)
        }
        statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 24.dp
        }
        scanProgress = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        statusRow.addView(scanProgress, LinearLayout.LayoutParams(20.dp, 20.dp).apply {
            marginEnd = 10.dp
        })
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.companion_secondary_text))
            text = "Scanning for MeshCore companions…"
        }
        statusRow.addView(status)
        body.addView(statusRow)
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(cards)
        root.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) root.requestApplyInsets()
    }

    private fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun requestScan() {
        if (connecting) return
        val missing = permissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 42)
        } else {
            status.text = "Scanning for MeshCore companions…"
            onCompanions(emptyList())
            setScanning(true)
            ble.scan()
        }
    }

    private fun setScanning(active: Boolean) {
        if (scanning && !active) {
            statusRow.minimumHeight = maxOf(statusRow.minimumHeight, statusRow.height)
        }
        scanning = active
        scanProgress.visibility = if (active) View.VISIBLE else View.GONE
        refresh.visibility = if (active) View.GONE else View.VISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) requestScan()
            else status.text = "Bluetooth and location permissions are required to find companions"
        }
    }

    override fun onCompanions(devices: List<MeshCoreBle.CompanionDevice>) {
        cards.removeAllViews()
        devices.forEach { companion ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                background = GradientDrawable().apply {
                    setColor(getColor(R.color.companion_card))
                    cornerRadius = 12.dp.toFloat()
                    setStroke(1.dp, getColor(R.color.companion_border))
                }
                elevation = 2.dp.toFloat()
                isClickable = true
                isFocusable = true
                contentDescription = "Connect to ${companion.name}, ${companion.address}"
                setOnClickListener { connect(companion) }
            }
            card.addView(TextView(this).apply {
                text = companion.name
                textSize = 18f
                setTextColor(getColor(R.color.companion_primary_text))
            })
            card.addView(TextView(this).apply {
                text = companion.address
                textSize = 13f
                setTextColor(getColor(R.color.companion_secondary_text))
            })
            cards.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp })
        }
    }

    private fun connect(companion: MeshCoreBle.CompanionDevice) {
        if (connecting) return
        setScanning(false)
        connecting = true
        showProgress()
        ble.connect(companion)
    }

    private fun showProgress() {
        if (progressDialog?.isShowing == true) return
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 20.dp, 24.dp, 20.dp)
            addView(ProgressBar(this@CompanionActivity).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginEnd = 16.dp })
            addView(TextView(this@CompanionActivity).apply {
                text = "Connecting to companion…"
                textSize = 16f
            })
        }
        progressDialog = AlertDialog.Builder(this)
            .setTitle("Connecting")
            .setView(row)
            .setNegativeButton("Cancel") { _, _ ->
                connecting = false
                ble.disconnect()
                requestScan()
            }
            .create()
        progressDialog?.show()
    }

    override fun onState(message: String, ready: Boolean) {
        if (connecting && ready) {
            connecting = false
            connectedAndLeaving = true
            progressDialog?.dismiss()
            progressDialog = null
            startActivity(Intent(this, ServerActivity::class.java)
                .putExtra(ServerActivity.EXTRA_FROM_COMPANION, true))
            finish()
        } else if (connecting) {
            status.text = message
        }
    }

    override fun onScanFinished(message: String) {
        setScanning(false)
        status.text = message
    }

    override fun onContacts(contacts: List<MeshCoreBle.Contact>) = Unit
    override fun onDirectMessage(senderPrefix: String, text: String) = Unit

    override fun onError(message: String) {
        if (connecting && message.startsWith("Companion connection failed:")) {
            connecting = false
            progressDialog?.dismiss()
            progressDialog = null
            ble.disconnect()
            status.text = message
        } else {
            status.text = message
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("connecting", connecting)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        ble.removeListener(this)
        ble.stopScan()
        progressDialog?.dismiss()
        if (connecting && !isChangingConfigurations && !connectedAndLeaving) ble.disconnect()
        super.onDestroy()
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
