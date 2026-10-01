package io.meshcore.teletext

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.darkrockstudios.libs.meshcore.ConnectionConfig
import com.darkrockstudios.libs.meshcore.DeviceConnection
import com.darkrockstudios.libs.meshcore.DeviceScanner
import com.darkrockstudios.libs.meshcore.ble.BlueFalconBleAdapter
import com.darkrockstudios.libs.meshcore.ble.ConnectionState
import com.darkrockstudios.libs.meshcore.ble.DiscoveredDevice
import com.darkrockstudios.libs.meshcore.ble.ScanFilter
import com.darkrockstudios.libs.meshcore.model.ReceivedMessage
import com.darkrockstudios.libs.meshcore.protocol.CommandSerializer
import com.darkrockstudios.libs.meshcore.protocol.Response
import dev.bluefalcon.BlueFalcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Adapts MeshCoreKmp's BLE and companion API to the Teletext screen. */
class MeshCoreBle(context: Context) {
    data class CompanionDevice(val device: DiscoveredDevice, val name: String, val address: String)
    data class Contact(val publicKey: ByteArray, val name: String) {
        val prefix: ByteArray get() = publicKey.copyOfRange(0, 6)
        val prefixHex: String get() = prefix.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    interface Listener {
        fun onState(message: String, ready: Boolean)
        fun onCompanions(devices: List<CompanionDevice>)
        fun onScanFinished(message: String)
        fun onContacts(contacts: List<Contact>)
        fun onContactsProgress(contacts: List<Contact>) = Unit
        fun onDirectMessage(senderPrefix: String, text: String)
        fun onError(message: String)
    }

    private val listeners = mutableSetOf<Listener>()
    private var status = "Disconnected"
    private var storedContacts = emptyList<Contact>()
    private var contactsLoaded = false
    private var partialContacts = emptyList<Contact>()
    private class ContactRefresh(val connection: DeviceConnection) {
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        var job: Job? = null
    }
    private var contactRefresh: ContactRefresh? = null

    fun hasCachedContacts() = contactsLoaded
    fun isLoadingContacts() = contactRefresh != null

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onContacts(storedContacts)
        if (contactRefresh != null) listener.onContactsProgress(partialContacts)
        listener.onState(status, ready)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun emitState(message: String, connected: Boolean) {
        status = message
        listeners.toList().forEach { it.onState(message, connected) }
    }

    private fun emitError(message: String) {
        listeners.toList().forEach { it.onError(message) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val bluetooth = context.getSystemService(BluetoothManager::class.java).adapter
    private val application = context.applicationContext as Application
    private lateinit var scanner: DeviceScanner
    private val config = ConnectionConfig(
        appName = "Teletext",
        autoFetchChannels = false,
        autoPollMessages = false,
    )
    private var connection: DeviceConnection? = null
    private var scanJob: Job? = null
    private var scanTimeout: Runnable? = null
    private var connectJob: Job? = null
    private var stateJob: Job? = null
    private var messagesJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastDevice: DiscoveredDevice? = null
    private var reconnectSeconds = 2L
    private var generation = 0
    private var manualDisconnect = false
    private var ready = false

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!bluetooth.isEnabled) {
            val message = "Turn on Bluetooth before scanning"
            listeners.toList().forEach { it.onScanFinished(message) }
            emitError(message)
            return
        }
        if (!::scanner.isInitialized) {
            scanner = DeviceScanner(BlueFalconBleAdapter(BlueFalcon(context = application)))
        }
        stopScan()
        listeners.toList().forEach { it.onCompanions(emptyList()) }
        scanner.startScan(filter = ScanFilter(), scope = scope)
        scanJob = scope.launch {
            scanner.discoveredDevices.collect { devices ->
                val companions = devices.map {
                    CompanionDevice(it, it.name ?: "Unnamed BLE device", it.identifier)
                }
                listeners.toList().forEach { it.onCompanions(companions) }
            }
        }
        val timer = Runnable {
            stopScan()
            val count = scanner.discoveredDevices.value.size
            val message = if (count == 0) "No MeshCore BLE companions found"
                else "Found $count MeshCore companion${if (count == 1) "" else "s"}"
            listeners.toList().forEach { it.onScanFinished(message) }
        }
        scanTimeout = timer
        handler.postDelayed(timer, 10_000)
    }

    fun stopScan() {
        scanTimeout?.let(handler::removeCallbacks)
        scanTimeout = null
        scanJob?.cancel()
        scanJob = null
        if (::scanner.isInitialized) scanner.stopScan()
    }

    fun connect(companion: CompanionDevice) {
        stopScan()
        cancelContactRefresh()
        manualDisconnect = false
        lastDevice = companion.device
        reconnectSeconds = 2
        storedContacts = emptyList()
        contactsLoaded = false
        listeners.toList().forEach { it.onContacts(emptyList()) }
        startConnection(companion.device, companion.name)
    }

    private fun startConnection(device: DiscoveredDevice, name: String) {
        val token = ++generation
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        stateJob?.cancel()
        messagesJob?.cancel()
        val previous = connection
        connection = null
        ready = false
        emitState("Connecting to $name…", false)
        connectJob = scope.launch {
            try {
                previous?.disconnect()
                val linked = scanner.connect(device, scope, config)
                if (token != generation) {
                    linked.disconnect()
                    return@launch
                }
                connection = linked
                // Subscribe before draining queued messages; SharedFlow has no replay.
                messagesJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    linked.incomingMessages.collect { deliver(it) }
                }
                stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    linked.connectionState.collect { state ->
                        when (state) {
                            is ConnectionState.Disconnected -> lostConnection(token, "Companion disconnected")
                            is ConnectionState.Error -> lostConnection(token, state.message)
                            else -> Unit
                        }
                    }
                }
                if (token != generation) return@launch
                // The server needs this companion's full public key to decrypt
                // direct requests and send replies. Receiving the server's
                // advert only teaches our companion the server's key.
                try {
                    linked.sendAdvert(flood = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    emitError("Could not advertise this companion: ${error.message}")
                }
                if (token != generation) return@launch
                ready = true
                reconnectSeconds = 2
                emitState("Connected", true)
                while (token == generation) {
                    val message = linked.pollNextMessage() ?: break
                    deliver(message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token == generation) {
                    emitError("Companion connection failed: ${error.message}")
                    lostConnection(token, "Companion disconnected")
                }
            }
        }
    }

    private fun deliver(message: ReceivedMessage) {
        if (message is ReceivedMessage.ContactMessage) {
            listeners.toList().forEach { it.onDirectMessage(message.publicKeyPrefix, message.text) }
        }
    }

    // MeshCoreKmp's getContacts() emits its StateFlow only after ContactEnd. Read
    // the same command stream so server cards can appear as contacts arrive.
    private suspend fun refreshContacts(linked: DeviceConnection): Boolean {
        val discovered = mutableListOf<Contact>()
        MeshCoreCommandQueue.forConnection(linked).executeStreaming<Response.ContactEnd>(
            CommandSerializer.getContacts(), config.commandTimeout
        ) { response ->
            when (response) {
                is Response.Contact -> {
                    discovered.add(Contact(response.publicKey, response.name))
                    if (linked === connection) {
                        partialContacts = discovered.toList()
                        listeners.toList().forEach { it.onContactsProgress(partialContacts) }
                    }
                    true
                }
                is Response.ContactEnd -> false
                else -> true
            }
        }
        if (linked !== connection) return false
        storedContacts = discovered.toList()
        partialContacts = emptyList()
        contactsLoaded = true
        listeners.toList().forEach { it.onContacts(storedContacts) }
        return true
    }

    fun refreshContacts(done: (Boolean) -> Unit = {}) {
        val linked = connection ?: run {
            emitError("Connect a companion before loading contacts")
            done(false)
            return
        }
        contactRefresh?.takeIf { it.connection === linked }?.let {
            it.callbacks.add(done)
            return
        }
        val refresh = ContactRefresh(linked)
        refresh.callbacks.add(done)
        contactRefresh = refresh
        partialContacts = emptyList()
        refresh.job = scope.launch {
            var ok = false
            try {
                ok = refreshContacts(linked)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emitError("Could not refresh contacts: ${error.message}")
            } finally {
                if (contactRefresh === refresh) {
                    contactRefresh = null
                    refresh.callbacks.forEach { it(ok) }
                }
            }
        }
    }

    private fun cancelContactRefresh() {
        val refresh = contactRefresh ?: return
        contactRefresh = null
        refresh.job?.cancel()
        refresh.callbacks.forEach { it(false) }
        partialContacts = emptyList()
    }

    fun sendDirect(
        destinationPrefix: ByteArray,
        text: String,
        awaitAck: Boolean = false,
        done: (Boolean) -> Unit = {},
    ) {
        require(destinationPrefix.size == 6)
        require(text.toByteArray(Charsets.UTF_8).size <= TeletextProtocol.MAX_FRAME_BYTES)
        val linked = connection
        if (!ready || linked == null) {
            done(false)
            return
        }
        scope.launch {
            try {
                if (!awaitAck) {
                    linked.sendDirectMessage(destinationPrefix, text)
                    done(true)
                } else {
                    // Start collecting before sending: the ACK can arrive before
                    // sendDirectMessage returns its expected ACK code.
                    val acks = Channel<String>(Channel.UNLIMITED)
                    val ackJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        linked.acks.collect { acks.send(it) }
                    }
                    try {
                        val confirmation = linked.sendDirectMessage(destinationPrefix, text)
                        val expected = confirmation.expectedAck
                        if (expected.isEmpty()) {
                            done(true)
                        } else {
                            // MeshCore reports this value in milliseconds, despite
                            // MeshCoreKmp naming the field "suggestedTimeoutSeconds".
                            val suggestedMs = confirmation.suggestedTimeoutSeconds.toLong()
                            val timeoutMs = (suggestedMs * 1.2).toLong().coerceIn(5_000L, 60_000L)
                            val acknowledged = withTimeoutOrNull(timeoutMs) {
                                while (true) {
                                    if (acks.receive().equals(expected, ignoreCase = true)) break
                                }
                                true
                            } ?: false
                            done(acknowledged)
                        }
                    } finally {
                        ackJob.cancel()
                        acks.close()
                    }
                }
            } catch (cancelled: CancellationException) {
                done(false)
                throw cancelled
            } catch (error: Exception) {
                emitError("Could not send message: ${error.message}")
                done(false)
            }
        }
    }

    private fun lostConnection(token: Int, message: String) {
        if (token != generation || manualDisconnect) return
        cancelContactRefresh()
        generation++
        ready = false
        contactsLoaded = false
        val stale = connection
        connection = null
        connectJob?.cancel()
        stateJob?.cancel()
        messagesJob?.cancel()
        scope.launch { runCatching { stale?.disconnect() } }
        emitState(message, false)
        val device = lastDevice ?: return
        val seconds = reconnectSeconds
        reconnectSeconds = (seconds * 2).coerceAtMost(30)
        emitState("Reconnecting in $seconds seconds…", false)
        reconnectJob = scope.launch {
            delay(seconds * 1000)
            startConnection(device, device.name ?: "companion")
        }
    }

    private fun disconnectAndRelease(): Job {
        manualDisconnect = true
        cancelContactRefresh()
        generation++
        stopScan()
        reconnectJob?.cancel()
        connectJob?.cancel()
        stateJob?.cancel()
        messagesJob?.cancel()
        val linked = connection
        connection = null
        ready = false
        contactsLoaded = false
        lastDevice = null
        emitState("Disconnected", false)
        return scope.launch { runCatching { linked?.disconnect() } }
    }

    fun disconnect() {
        disconnectAndRelease()
    }

    fun close() {
        disconnectAndRelease().invokeOnCompletion { scope.cancel() }
    }
}
