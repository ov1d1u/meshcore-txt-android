package io.meshcore.teletext

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

private const val INDEX_PAGE = 100

class MainActivity : Activity(), MeshCoreBle.Listener {
    private lateinit var ble: MeshCoreBle
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var toolbarAction: ImageButton
    private lateinit var pageInput: AutoCompleteTextView
    private lateinit var pageAdapter: ArrayAdapter<IndexPage>
    private lateinit var content: TextView
    private lateinit var contentScroll: ScrollView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var suggestions: LinearLayout
    private lateinit var suggestionSeparator: View
    private lateinit var footerScroll: HorizontalScrollView
    private var contacts = emptyList<MeshCoreBle.Contact>()
    private var server: MeshCoreBle.Contact? = null
    private var selectingFromIntent = false
    private var ready = false
    private var connectionStatus = "Disconnected"
    private var transferStatus = ""
    private var displayedPage: Int? = null
    private val pageHistory = PageHistory()
    private var requestFromHistory = false
    private var previewShown = false
    private var previewPage: Int? = null
    private var previewSectionIndex = 0
    private var previewSectionCount = 0
    private var previewFollowTail = true
    private var previousPageScrollY: Int? = null
    private var previewUpdate: Runnable? = null
    private var indexPages = emptyList<IndexPage>()
    private var assembler: TransferAssembler? = null
    private var requestId: String? = null
    private var lastIssuedId: String? = null
    private var requestedPage: Int? = null
    private var attempts = 0
    private var lastProgress = -1L
    private var requestedMissing: Long? = null
    private var missingRequestCount = 0
    private var timeout: Runnable? = null
    private var failureDialog: AlertDialog? = null
    private var backCallback: OnBackInvokedCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private var pageFile: File? = null
    private val sectionOffsets = mutableListOf(0L)
    private var sectionIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        ble = (application as TeletextApplication).ble
        buildUi()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = OnBackInvokedCallback { navigateSystemBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        selectingFromIntent = intent.hasExtra(ServerActivity.EXTRA_SERVER_KEY)
        ble.addListener(this)
        if (selectingFromIntent) {
            selectServerFromIntent(intent)
            selectingFromIntent = false
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.companion_background))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, keyboard.bottom))
                insets
            }
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 0, 8.dp, 0)
            minimumHeight = 64.dp
            setBackgroundColor(getColor(R.color.companion_toolbar))
        }
        bar.addView(toolbarButton(R.drawable.ic_arrow_back, "Back") { goBack() },
            LinearLayout.LayoutParams(48.dp, 56.dp))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply {
            textSize = 20f
            setTextColor(getColor(R.color.companion_primary_text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.companion_secondary_text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        labels.addView(title)
        labels.addView(subtitle)
        bar.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        toolbarAction = toolbarButton(R.drawable.ic_refresh, "Refresh page") {
            if (requestId != null) cancelRequest() else startRequest(previewPage ?: displayedPage ?: INDEX_PAGE)
        }
        bar.addView(toolbarAction, LinearLayout.LayoutParams(48.dp, 56.dp))
        root.addView(bar)

        val selector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            setBackgroundColor(getColor(R.color.companion_toolbar))
        }
        pageAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        pageInput = object : AutoCompleteTextView(this) {
            override fun enoughToFilter() = true
        }.apply {
            hint = "Page 100–999"
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_GO
            filters = arrayOf(InputFilter.LengthFilter(3))
            threshold = 1
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_drop_down, 0)
            compoundDrawableTintList = ColorStateList.valueOf(getColor(R.color.companion_secondary_text))
            setAdapter(pageAdapter)
            setOnClickListener { post { showDropDown() } }
            setOnFocusChangeListener { _, focused -> if (focused) post { showDropDown() } }
            setOnItemClickListener { parent, _, position, _ ->
                val page = parent.getItemAtPosition(position) as IndexPage
                setText(page.number.toString(), false)
                setSelection(text.length)
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    submitPageNumber()
                    true
                } else false
            }
        }
        selector.addView(pageInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        selector.addView(button("Go") { submitPageNumber() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 8.dp
            })
        root.addView(selector)

        content = TextView(this).apply {
            textSize = 17f
            text = "Choose a server to start reading."
            setTextColor(getColor(R.color.companion_primary_text))
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
        }
        contentScroll = ScrollView(this).apply {
            setPadding(16.dp, 20.dp, 16.dp, 16.dp)
            clipToPadding = false
            addView(content)
        }
        root.addView(contentScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        footerScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(getColor(R.color.companion_toolbar))
        }
        val footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        }
        previous = button("Previous section") {
            if (previewShown) showPreviewSection(previewSectionIndex - 1)
            else showSection(sectionIndex - 1)
        }
        next = button("Next section") {
            if (previewShown) showPreviewSection(previewSectionIndex + 1)
            else showSection(sectionIndex + 1)
        }
        footer.addView(previous)
        footer.addView(next)
        suggestionSeparator = View(this).apply { setBackgroundColor(getColor(R.color.companion_border)) }
        footer.addView(suggestionSeparator, LinearLayout.LayoutParams(1.dp, 32.dp).apply {
            marginStart = 12.dp
            marginEnd = 12.dp
        })
        suggestions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(suggestions)
        footerScroll.addView(footer)
        root.addView(footerScroll)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) root.requestApplyInsets()
        updateSuggestions()
        updateSectionButtons()
        renderToolbar()
    }

    private fun toolbarButton(icon: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(getColor(R.color.companion_primary_text))
        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        setBackgroundResource(ripple.resourceId)
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun renderToolbar() {
        title.text = server?.let(TeletextServers::displayName) ?: "Select a server"
        subtitle.text = when {
            requestId != null -> transferStatus
            previewShown -> "${pageLabel(previewPage)} incomplete"
            !ready -> connectionStatus
            server == null -> "Choose a server"
            displayedPage != null -> pageLabel(displayedPage)
            else -> "Choose a page"
        }
        val retrieving = requestId != null
        toolbarAction.setImageResource(if (retrieving) R.drawable.ic_close else R.drawable.ic_refresh)
        toolbarAction.contentDescription = if (retrieving) "Cancel page retrieval" else "Refresh page"
        toolbarAction.isEnabled = retrieving || (ready && server != null)
    }

    private fun updateSuggestions() {
        val choices = mutableListOf(IndexPage(INDEX_PAGE, "Index"))
        choices.addAll(indexPages.filter { it.number != INDEX_PAGE })
        pageAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, choices)
        pageInput.setAdapter(pageAdapter)
        suggestions.removeAllViews()
        IndexPages.featured(indexPages, if (previewShown) previewPage else displayedPage).forEach { page ->
            suggestions.addView(button(page.toString()) { startRequest(page.number) })
        }
        suggestionSeparator.visibility = if (suggestions.childCount == 0) View.GONE else View.VISIBLE
        footerScroll.scrollTo(0, 0)
    }

    private fun updateSectionButtons() {
        if (previewShown) {
            previous.isEnabled = previewSectionIndex > 0
            next.isEnabled = previewSectionIndex + 1 < previewSectionCount
        } else {
            previous.isEnabled = pageFile != null && sectionIndex > 0
            next.isEnabled = pageFile != null && sectionIndex + 1 < sectionOffsets.size
        }
    }

    private fun submitPageNumber() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(pageInput.windowToken, 0)
        pageInput.clearFocus()
        val page = pageInput.text.toString().toIntOrNull()
        if (page == null || page !in 100..999) showError("Enter a page from 100 to 999")
        else startRequest(page)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private fun goBack() {
        cancelRequest(showMessage = false)
        failureDialog?.dismiss()
        startActivity(Intent(this, ServerActivity::class.java)
            .putExtra(ServerActivity.EXTRA_FROM_COMPANION, true))
        finish()
    }

    private fun navigateSystemBack() {
        if (requestId != null) {
            cancelRequest(showMessage = false)
            if (displayedPage == null) goBack()
            return
        }
        if (previewShown) {
            cancelRequest(showMessage = false)
            if (displayedPage == null) goBack()
            return
        }
        val previousPage = pageHistory.previous() ?: return goBack()
        if (previousPage.file.isFile) {
            pageHistory.pop()
            restoreCachedPage(previousPage)
        } else if (ready && server != null) {
            // Android may remove cache files under storage pressure.
            startRequest(previousPage.number, fromHistory = true)
        } else {
            showError("Previous page is no longer cached")
        }
    }

    private fun restoreCachedPage(page: CachedPage) {
        pageFile?.delete()
        pageFile = page.file
        displayedPage = page.number
        requestedPage = page.number
        sectionOffsets.clear()
        sectionOffsets.addAll(page.sectionOffsets)
        if (page.number == INDEX_PAGE) indexPages = readIndexPages(page.file)
        updateSuggestions()
        pageInput.setText(page.number.toString(), false)
        showSection(page.sectionIndex)
        contentScroll.post {
            if (pageFile == page.file) contentScroll.scrollTo(0, page.scrollY)
        }
        renderToolbar()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = navigateSystemBack()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectServerFromIntent(intent)
    }

    private fun selectServerFromIntent(intent: Intent) {
        val key = intent.getStringExtra(ServerActivity.EXTRA_SERVER_KEY) ?: return
        val selected = contacts.firstOrNull { TeletextServers.key(it) == key }
            ?: run {
                val publicKey = intent.getByteArrayExtra(ServerActivity.EXTRA_SERVER_PUBLIC_KEY)
                    ?: return
                val name = intent.getStringExtra(ServerActivity.EXTRA_SERVER_NAME) ?: return
                MeshCoreBle.Contact(publicKey, name).takeIf { TeletextServers.key(it) == key }
                    ?: return
            }
        selectServer(selected)
        if (ready) startRequest(INDEX_PAGE)
    }

    private fun selectServer(selected: MeshCoreBle.Contact) {
        if (server?.let(TeletextServers::key) != TeletextServers.key(selected)) {
            cancelRequest(showMessage = false)
            failureDialog?.dismiss()
            clearPageState()
        }
        server = selected
        renderToolbar()
    }

    private fun clearPageState() {
        pageFile?.delete()
        pageFile = null
        displayedPage = null
        pageHistory.clear()
        requestedPage = null
        content.text = "Loading index…"
        sectionOffsets.clear()
        sectionOffsets.add(0L)
        sectionIndex = 0
        indexPages = emptyList()
        pageInput.setText("", false)
        updateSuggestions()
        updateSectionButtons()
    }

    private fun startRequest(page: Int, fromHistory: Boolean = false) {
        if (!ready || server == null) {
            showError("Connect a companion and choose a server first")
            return
        }
        failureDialog?.dismiss()
        cancelRequest(showMessage = false)
        requestedPage = page
        requestFromHistory = fromHistory
        attempts = 0
        sendAttempt()
    }

    private fun sendAttempt() {
        val selected = server ?: return
        restorePreviousPageFromPreview()
        assembler?.close()
        var id: String
        do { id = "%04X".format(random.nextInt(65536)) } while (id == lastIssuedId)
        lastIssuedId = id
        requestId = id
        assembler = TransferAssembler(cacheDir, id)
        lastProgress = -1
        requestedMissing = null
        missingRequestCount = 0
        transferStatus = "Sending ${pageLabel(requestedPage)} request (attempt ${attempts + 1}/3)…"
        renderToolbar()
        ble.sendDirect(selected.prefix, TeletextProtocol.request(id, requestedPage), awaitAck = true) { ok ->
            if (requestId == id) {
                if (ok) {
                    if (lastProgress < 0) {
                        transferStatus = "Request delivered; waiting for server…"
                        renderToolbar()
                    }
                    resetTimeout()
                } else if (lastProgress < 0) {
                    retryOrFail("No radio delivery confirmation from server; check that it has this companion as a contact")
                }
            }
        }
    }

    private fun resetTimeout() {
        timeout?.let(handler::removeCallbacks)
        val id = requestId ?: return
        val timer = Runnable {
            if (requestId == id && !requestMissingChunk(retry = true))
                retryOrFail("Transfer stopped before completion")
        }
        timeout = timer
        handler.postDelayed(timer, 180_000)
    }

    private fun requestMissingChunk(retry: Boolean = false): Boolean {
        val id = requestId ?: return false
        val selected = server ?: return false
        val transfer = assembler ?: return false
        val missing = transfer.firstMissingChunk() ?: return false
        if (!retry && !transfer.hasEnd) return false
        if (requestedMissing != missing) {
            requestedMissing = missing
            missingRequestCount = 0
        } else if (!retry) return true
        if (missingRequestCount >= 2) return false
        missingRequestCount++
        transferStatus = "Received ${transfer.receivedChunks} / ${transfer.totalChunks} chunks; " +
            "requesting chunk ${missing + 1} again…"
        renderToolbar()
        resetTimeout()
        ble.sendDirect(selected.prefix, TeletextProtocol.retryChunk(id, missing), awaitAck = true) { ok ->
            if (requestId == id && requestedMissing == missing && !ok)
                retryOrFail("Could not request missing chunk")
        }
        return true
    }

    private fun retryOrFail(reason: String) {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        if (attempts < 2 && ready && server != null) {
            requestId?.let { ble.sendDirect(server!!.prefix, TeletextProtocol.cancel(it)) }
            attempts++
            sendAttempt()
        } else {
            val failedPage = requestedPage ?: INDEX_PAGE
            val wasHistoryRequest = requestFromHistory
            finishFailedRequest(reason, failedPage, wasHistoryRequest)
        }
    }

    private fun finishFailedRequest(
        reason: String,
        page: Int,
        fromHistory: Boolean,
        sendCancel: Boolean = true,
    ) {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        cancelPreviewUpdate()
        renderPreview()
        if (previewShown) {
            if (sendCancel && ready && server != null) {
                requestId?.let { ble.sendDirect(server!!.prefix, TeletextProtocol.cancel(it)) }
            }
            requestId = null
            requestFromHistory = false
            transferStatus = ""
            renderToolbar()
        } else {
            cancelRequest(sendCancel = sendCancel, showMessage = false)
        }
        showRetrievalFailure(reason, page, fromHistory)
    }

    private fun cancelRequest(sendCancel: Boolean = true, showMessage: Boolean = true) {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        if (sendCancel && ready && server != null) {
            requestId?.let { ble.sendDirect(server!!.prefix, TeletextProtocol.cancel(it)) }
        }
        assembler?.close()
        assembler = null
        requestId = null
        requestedMissing = null
        missingRequestCount = 0
        requestFromHistory = false
        transferStatus = ""
        restorePreviousPageFromPreview()
        renderToolbar()
        if (showMessage) Toast.makeText(this, "Transfer cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun schedulePreview() {
        if (previewUpdate != null) return
        val id = requestId ?: return
        val update = Runnable {
            previewUpdate = null
            if (requestId == id) renderPreview()
        }
        previewUpdate = update
        handler.postDelayed(update, 120)
    }

    private fun cancelPreviewUpdate() {
        previewUpdate?.let(handler::removeCallbacks)
        previewUpdate = null
    }

    private fun showPreviewSection(index: Int) {
        if (!previewShown || index !in 0 until previewSectionCount) return
        previewSectionIndex = index
        previewFollowTail = index == previewSectionCount - 1
        renderPreview(resetScroll = true)
    }

    private fun renderPreview(resetScroll: Boolean = false) {
        val transfer = assembler ?: return
        val target = if (previewFollowTail) Int.MAX_VALUE else previewSectionIndex
        val snapshot = try { transfer.preview(target) } catch (_: Exception) { null } ?: return
        val wasShown = previewShown
        val oldScrollY = contentScroll.scrollY
        val atBottom = oldScrollY + contentScroll.height >= content.height - 24.dp
        if (!wasShown) {
            previousPageScrollY = oldScrollY
            previewShown = true
            previewPage = requestedPage
            pageInput.setText((previewPage ?: INDEX_PAGE).toString(), false)
            updateSuggestions()
        }
        previewSectionIndex = snapshot.index
        previewSectionCount = snapshot.count
        val rendered = MarkdownRenderer.render(snapshot.text) { page -> startRequest(page) }
        val width = (content.width - content.paddingLeft - content.paddingRight)
            .takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - 32.dp)
        var marker = rendered.indexOf(MISSING_CHUNK_MARKER)
        while (marker >= 0) {
            rendered.setSpan(MissingChunkSpan(width, getColor(R.color.companion_secondary_text), 12.dp),
                marker, marker + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            marker = rendered.indexOf(MISSING_CHUNK_MARKER, marker + 1)
        }
        content.text = rendered
        updateSectionButtons()
        renderToolbar()
        val followBottom = previewFollowTail && (!wasShown || resetScroll || atBottom)
        contentScroll.post {
            if (previewShown && previewSectionIndex == snapshot.index) {
                when {
                    followBottom -> contentScroll.fullScroll(View.FOCUS_DOWN)
                    resetScroll -> contentScroll.scrollTo(0, 0)
                    else -> contentScroll.scrollTo(0, oldScrollY)
                }
            }
        }
    }

    private fun resetPreviewState() {
        cancelPreviewUpdate()
        previewShown = false
        previewPage = null
        previewSectionIndex = 0
        previewSectionCount = 0
        previewFollowTail = true
        previousPageScrollY = null
    }

    private fun restorePreviousPageFromPreview() {
        cancelPreviewUpdate()
        if (!previewShown) return
        val restoreY = previousPageScrollY ?: 0
        resetPreviewState()
        if (pageFile != null) {
            showSection(sectionIndex)
            contentScroll.post {
                if (!previewShown && pageFile != null) contentScroll.scrollTo(0, restoreY)
            }
        } else {
            content.text = if (server == null) "Choose a server to start reading." else "Choose a page."
        }
        updateSuggestions()
        updateSectionButtons()
    }

    private fun showRetrievalFailure(reason: String, page: Int, fromHistory: Boolean) {
        failureDialog?.dismiss()
        failureDialog = AlertDialog.Builder(this)
            .setTitle("Could not retrieve ${pageLabel(page)}")
            .setMessage(reason)
            .setPositiveButton("Retry", null)
            .setNegativeButton("Dismiss", null)
            .create().also { dialog ->
                dialog.setOnDismissListener { if (failureDialog === dialog) failureDialog = null }
                dialog.show()
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).apply {
                    isEnabled = ready && server != null
                    setOnClickListener {
                        if (ready && server != null) {
                            dialog.dismiss()
                            startRequest(page, fromHistory)
                        }
                    }
                }
            }
    }

    override fun onState(message: String, ready: Boolean) {
        val becameReady = ready && !this.ready
        connectionStatus = message
        this.ready = ready
        if (!ready && requestId != null) {
            val failedPage = requestedPage ?: INDEX_PAGE
            val wasHistoryRequest = requestFromHistory
            finishFailedRequest("Connection lost. Retry after reconnecting.", failedPage,
                wasHistoryRequest, sendCancel = false)
        }
        failureDialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = ready && server != null
        renderToolbar()
        if (becameReady && server != null && failureDialog == null && !previewShown && !selectingFromIntent) {
            startRequest(INDEX_PAGE)
        }
    }

    override fun onCompanions(devices: List<MeshCoreBle.CompanionDevice>) = Unit

    override fun onScanFinished(message: String) = Unit

    override fun onContacts(contacts: List<MeshCoreBle.Contact>) {
        this.contacts = contacts.filter(TeletextServers::includes)
        val saved = getPreferences(MODE_PRIVATE).getString(TeletextServers.preferenceKey, null)
        val selected = this.contacts.firstOrNull { TeletextServers.key(it) == saved }
        if (selected != null) selectServer(selected)
        else if (server != null) {
            cancelRequest(showMessage = false)
            failureDialog?.dismiss()
            server = null
            clearPageState()
            content.text = "Choose a server to start reading."
            renderToolbar()
        }
    }

    override fun onDirectMessage(senderPrefix: String, text: String) {
        val selected = server ?: return
        if (!senderPrefix.equals(selected.prefixHex, ignoreCase = true)) return
        val response = TeletextProtocol.parse(text) ?: return
        if (response.id != requestId) return
        val result = assembler?.accept(response) ?: return
        when (result) {
            is TransferAssembler.Result.Progress -> {
                if (response is TeletextProtocol.Data && response.sequence == requestedMissing) {
                    requestedMissing = null
                    missingRequestCount = 0
                }
                if (result.bytes > lastProgress || response is TeletextProtocol.End) {
                    lastProgress = result.bytes
                    transferStatus = if (result.total == null)
                        "Received ${result.chunks} chunks…"
                    else "Received ${result.chunks} / ${result.total} chunks…"
                    renderToolbar()
                    resetTimeout()
                    schedulePreview()
                }
                if (assembler?.hasEnd == true) requestMissingChunk()
            }
            is TransferAssembler.Result.Complete -> {
                timeout?.let(handler::removeCallbacks)
                timeout = null
                cancelPreviewUpdate()
                val oldPageScrollY = previousPageScrollY ?: contentScroll.scrollY
                resetPreviewState()
                assembler = null
                requestId = null
                transferStatus = ""
                if (requestedPage == INDEX_PAGE) {
                    indexPages = readIndexPages(result.file)
                }
                val openedPage = requestedPage ?: INDEX_PAGE
                val previousFile = pageFile
                if (requestFromHistory) {
                    if (pageHistory.previous()?.number == openedPage) {
                        pageHistory.pop()?.file?.delete()
                    }
                    previousFile?.delete()
                } else if (previousFile != null && displayedPage != null) {
                    pageHistory.record(CachedPage(displayedPage!!, previousFile,
                        sectionOffsets.toList(), sectionIndex, oldPageScrollY), openedPage)
                } else {
                    previousFile?.delete()
                }
                pageFile = result.file
                displayedPage = openedPage
                requestFromHistory = false
                updateSuggestions()
                sectionOffsets.clear()
                sectionOffsets.add(0L)
                sectionIndex = 0
                pageInput.setText((displayedPage ?: INDEX_PAGE).toString(), false)
                showSection(0)
                renderToolbar()
            }
            is TransferAssembler.Result.Failure -> {
                if (result.reason == "NF" || result.reason == "BAD" || result.reason == "IO") {
                    val reason = when (result.reason) {
                        "NF" -> "Page not found"
                        "BAD" -> "Invalid request"
                        else -> "Server could not read the page"
                    }
                    val failedPage = requestedPage ?: INDEX_PAGE
                    val wasHistoryRequest = requestFromHistory
                    finishFailedRequest(reason, failedPage, wasHistoryRequest, sendCancel = false)
                } else retryOrFail(result.reason)
            }
            TransferAssembler.Result.Ignored -> Unit
        }
    }

    private fun showSection(index: Int) {
        val file = pageFile ?: return
        if (index < 0 || index >= sectionOffsets.size) return
        val start = sectionOffsets[index]
        val (text, end) = readSection(file, start)
        if (end < file.length() && sectionOffsets.size == index + 1) sectionOffsets.add(end)
        sectionIndex = index
        content.text = MarkdownRenderer.render(text) { page -> startRequest(page) }
        updateSectionButtons()
        contentScroll.scrollTo(0, 0)
    }

    private fun pageLabel(page: Int?): String =
        if (page == null || page == INDEX_PAGE) "Index (100)" else "Page $page"

    private fun readIndexPages(file: File): List<IndexPage> = try {
        file.bufferedReader().use { IndexPages.parse(it.lineSequence()) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun readSection(file: File, start: Long): Pair<String, Long> {
        RandomAccessFile(file, "r").use { source ->
            source.seek(start)
            val remaining = (source.length() - start).coerceAtLeast(0)
            val size = minOf(32 * 1024L, remaining).toInt()
            if (size == 0) return "" to start
            val raw = ByteArray(size)
            source.readFully(raw)
            var length = raw.size
            if (start + length < file.length()) {
                val newline = raw.indexOfLast { it == '\n'.code.toByte() }
                if (newline > raw.size / 2) length = newline + 1
                else {
                    while (length > 0) {
                        try {
                            StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(raw, 0, length))
                            break
                        } catch (_: Exception) { length-- }
                    }
                }
            }
            return String(raw, 0, length, StandardCharsets.UTF_8) to start + length
        }
    }

    override fun onError(message: String) {
        if (requestId != null) {
            transferStatus = message
            renderToolbar()
        } else showError(message)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        failureDialog?.dismiss()
        cancelRequest(sendCancel = false, showMessage = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        ble.removeListener(this)
        pageFile?.delete()
        pageHistory.clear()
        super.onDestroy()
    }
}
