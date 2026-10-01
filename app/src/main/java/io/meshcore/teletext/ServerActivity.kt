package io.meshcore.teletext

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

internal object TeletextServers {
    const val preferenceName = "MainActivity" // Activity.getPreferences() used this name previously.
    const val preferenceKey = "server_key"
    private const val suffix = "-txt"

    fun includes(contact: MeshCoreBle.Contact) = contact.name.endsWith(suffix)
    fun displayName(contact: MeshCoreBle.Contact) = contact.name.dropLast(suffix.length)
    fun description(contact: MeshCoreBle.Contact) =
        "${displayName(contact)} (${contact.prefixHex})"
    fun key(contact: MeshCoreBle.Contact) =
        contact.publicKey.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

class ServerActivity : Activity(), MeshCoreBle.Listener {
    companion object {
        const val EXTRA_SERVER_KEY = "server_key"
        const val EXTRA_SERVER_PUBLIC_KEY = "server_public_key"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_FROM_COMPANION = "from_companion"
    }

    private lateinit var ble: MeshCoreBle
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var cards: LinearLayout
    private lateinit var refresh: ImageButton
    private var ready = false
    private var loading = false
    private var loadToken = 0
    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        ble = (application as TeletextApplication).ble
        buildUi()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = OnBackInvokedCallback { goBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        ble.addListener(this)
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
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(getColor(R.color.companion_primary_text))
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            contentDescription = "Back"
            setOnClickListener { goBack() }
        }
        bar.addView(back, LinearLayout.LayoutParams(48.dp, 56.dp))
        bar.addView(TextView(this).apply {
            text = "Select Teletext server"
            textSize = 20f
            setTextColor(getColor(R.color.companion_primary_text))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        refresh = ImageButton(this).apply {
            setImageResource(R.drawable.ic_refresh)
            imageTintList = ColorStateList.valueOf(getColor(R.color.companion_primary_text))
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            contentDescription = "Refresh servers"
            setOnClickListener { loadServers() }
        }
        bar.addView(refresh, LinearLayout.LayoutParams(48.dp, 56.dp))
        root.addView(bar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 20.dp, 16.dp, 16.dp)
        }
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 24.dp
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        statusRow.addView(progress, LinearLayout.LayoutParams(20.dp, 20.dp).apply {
            marginEnd = 10.dp
        })
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.companion_secondary_text))
            text = "Loading servers…"
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

    private fun loadServers() {
        if (!ready || loading) return
        val token = ++loadToken
        val alreadyLoading = ble.isLoadingContacts()
        loading = true
        status.text = "Searching for Teletext servers…"
        if (alreadyLoading && currentContacts.isNotEmpty()) showCards(currentContacts)
        else {
            if (!alreadyLoading) currentContacts = emptyList()
            cards.removeAllViews()
        }
        progress.visibility = View.VISIBLE
        refresh.visibility = View.GONE
        ble.refreshContacts { ok ->
            if (token != loadToken || isFinishing || isDestroyed) return@refreshContacts
            loading = false
            progress.visibility = View.GONE
            refresh.visibility = View.VISIBLE
            if (ok && ready) {
                showLoadedServers()
            } else if (ready) {
                status.text = "Could not load servers. Tap Refresh to try again."
            }
        }
    }

    private var currentContacts = emptyList<MeshCoreBle.Contact>()

    private fun showLoadedServers() {
        status.text = if (currentContacts.isEmpty())
            "No Teletext server contacts are stored on this companion"
        else "Found ${currentContacts.size} stored Teletext server${if (currentContacts.size == 1) "" else "s"}"
        showCards(currentContacts)
    }

    private fun showCards(contacts: List<MeshCoreBle.Contact>) {
        cards.removeAllViews()
        contacts.forEach { server ->
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
                contentDescription = "Select server ${TeletextServers.description(server)}"
                setOnClickListener { selectServer(server) }
            }
            card.addView(TextView(this).apply {
                text = TeletextServers.displayName(server)
                textSize = 18f
                setTextColor(getColor(R.color.companion_primary_text))
            })
            card.addView(TextView(this).apply {
                text = server.prefixHex
                textSize = 13f
                setTextColor(getColor(R.color.companion_secondary_text))
            })
            cards.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp })
        }
    }

    private fun selectServer(server: MeshCoreBle.Contact) {
        if (!ready) return
        val key = TeletextServers.key(server)
        getSharedPreferences(TeletextServers.preferenceName, MODE_PRIVATE)
            .edit().putString(TeletextServers.preferenceKey, key).apply()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SERVER_KEY, key)
            .putExtra(EXTRA_SERVER_PUBLIC_KEY, server.publicKey)
            .putExtra(EXTRA_SERVER_NAME, server.name))
        finish()
    }

    private fun goBack() {
        if (intent.getBooleanExtra(EXTRA_FROM_COMPANION, false)) {
            startActivity(Intent(this, CompanionActivity::class.java))
        }
        finish()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = goBack()

    override fun onState(message: String, ready: Boolean) {
        val becameReady = ready && !this.ready
        this.ready = ready
        if (becameReady) {
            if (ble.hasCachedContacts()) showLoadedServers() else loadServers()
        }
        else if (!ready) {
            loadToken++
            loading = false
            currentContacts = emptyList()
            cards.removeAllViews()
            progress.visibility = View.GONE
            refresh.visibility = View.VISIBLE
            status.text = message
        }
    }

    override fun onContacts(contacts: List<MeshCoreBle.Contact>) {
        currentContacts = contacts.filter(TeletextServers::includes)
        if (ready && !loading && ble.hasCachedContacts()) showLoadedServers()
    }

    override fun onContactsProgress(contacts: List<MeshCoreBle.Contact>) {
        val servers = contacts.filter(TeletextServers::includes)
        if (servers.size == currentContacts.size) return
        currentContacts = servers
        if (ready && loading && !isFinishing) showCards(currentContacts)
    }

    override fun onCompanions(devices: List<MeshCoreBle.CompanionDevice>) = Unit
    override fun onScanFinished(message: String) = Unit
    override fun onDirectMessage(senderPrefix: String, text: String) = Unit
    override fun onError(message: String) { status.text = message }

    override fun onDestroy() {
        loadToken++
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        ble.removeListener(this)
        super.onDestroy()
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
