package com.vlack.pdfview.sender

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vlack.pdfview.sender.data.DocumentIO
import com.vlack.pdfview.sender.model.ConnectionState
import com.vlack.pdfview.sender.model.PickedDocument
import com.vlack.pdfview.sender.model.SendFormat
import com.vlack.pdfview.sender.model.SenderUiState
import com.vlack.pdfview.sender.model.TransferState
import com.vlack.pdfview.sender.model.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SenderUiState())
    val uiState: StateFlow<SenderUiState> = _uiState.asStateFlow()

    private val messageIds = AtomicLong(0L)
    @SuppressLint("StaticFieldLeak") // A bound, same-process service, released in onCleared().
    private var senderService: FileTransferSender? = null
    private var isBound = false

    private val listener = object : FileTransferSender.Listener {
        override fun onConnectionStateChanged(connected: Boolean) {
            _uiState.update {
                it.copy(
                    connection = if (connected) ConnectionState.Connected else ConnectionState.Disconnected,
                )
            }
        }

        override fun onTransferProgress(percent: Int) {
            _uiState.update { state ->
                if (state.transfer is TransferState.Sending) {
                    state.copy(transfer = TransferState.Sending(percent))
                } else {
                    state
                }
            }
        }

        override fun onTransferCompleted() {
            _uiState.update { it.copy(transfer = TransferState.Idle) }
            showMessage(R.string.transfer_complete)
        }

        override fun onTransferCancelled() {
            _uiState.update { it.copy(transfer = TransferState.Idle) }
            showMessage(R.string.sending_cancelled)
        }

        override fun onTransferFailed(errorCode: Int) {
            _uiState.update { it.copy(transfer = TransferState.Idle) }
            showMessage(R.string.error_with_code, errorCode)
        }

        override fun onMessage(resId: Int) {
            showMessage(resId)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? FileTransferSender.SenderBinder ?: return
            isBound = true
            senderService = binder.service
            senderService?.listener = listener
            _uiState.update { it.copy(connection = ConnectionState.Connecting) }
            senderService?.connect()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            senderService = null
            _uiState.update { it.copy(connection = ConnectionState.Disconnected) }
        }
    }

    init {
        val intent = Intent(getApplication(), FileTransferSender::class.java)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun onFileNameChange(value: String) {
        if (!_uiState.value.isComposing) return
        _uiState.update {
            it.copy(
                fileName = value.replace('\n', ' ').take(96),
                fileNameError = null,
            )
        }
    }

    fun onContentChange(value: String) {
        if (!_uiState.value.isComposing) return
        _uiState.update { it.copy(content = value, contentError = null) }
    }

    fun onFormatChange(format: SendFormat) {
        if (!_uiState.value.isComposing) return
        _uiState.update { it.copy(format = format, fileNameError = null) }
    }

    fun onDocumentPicked(uri: Uri) {
        if (_uiState.value.isSending) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching { DocumentIO.importDocument(getApplication(), uri) }
            }
            result.exceptionOrNull()?.let { android.util.Log.e("MainViewModel", "importDocument", it) }
            _uiState.update { state ->
                val document = result.getOrNull()
                state.copy(
                    isImporting = false,
                    pickedDocument = document ?: state.pickedDocument,
                    fileNameError = null,
                    contentError = null,
                )
            }
            result.onFailure { showMessage(R.string.error_import_document) }
        }
    }

    fun clearPickedDocument() {
        val document = _uiState.value.pickedDocument ?: return
        viewModelScope.launch(Dispatchers.IO) {
            DocumentIO.discardStaged(getApplication(), document)
        }
        _uiState.update { it.copy(pickedDocument = null) }
    }

    fun connect() {
        val state = _uiState.value
        if (state.connection == ConnectionState.Connecting || state.connection == ConnectionState.Connected) {
            return
        }
        if (senderService == null) {
            showMessage(R.string.not_bound)
            return
        }
        _uiState.update { it.copy(connection = ConnectionState.Connecting) }
        senderService?.connect()
    }

    fun submit() {
        val state = _uiState.value
        if (state.isSending || state.isImporting) return

        if (state.connection != ConnectionState.Connected) {
            showMessage(R.string.connect_first)
            return
        }

        val picked: PickedDocument? = state.pickedDocument
        val path: String
        if (picked != null) {
            path = picked.path
        } else {
            val nameError = if (state.fileName.isBlank()) R.string.error_empty_name else null
            val contentError = if (state.content.isBlank()) R.string.error_empty_content else null
            if (nameError != null || contentError != null) {
                _uiState.update { it.copy(fileNameError = nameError, contentError = contentError) }
                return
            }

            path = try {
                DocumentIO.writeTextDocument(
                    getApplication(),
                    state.fileName,
                    state.format,
                    state.content,
                ).absolutePath
            } catch (e: IOException) {
                android.util.Log.e("MainViewModel", "writeTextDocument", e)
                showMessage(R.string.error_create_document)
                return
            }
        }

        _uiState.update {
            it.copy(
                transfer = TransferState.Sending(0),
                fileNameError = null,
                contentError = null,
            )
        }

        val queued = senderService?.sendFile(path) ?: false
        if (!queued) {
            _uiState.update { it.copy(transfer = TransferState.Idle) }
        }
    }

    fun cancelTransfer() {
        senderService?.cancelFileTransfer()
    }

    fun onMessageShown(id: Long) {
        _uiState.update { state ->
            if (state.message?.id == id) state.copy(message = null) else state
        }
    }

    fun notify(@StringRes resId: Int) = showMessage(resId)

    private fun showMessage(@StringRes resId: Int, vararg args: Any) {
        _uiState.update {
            it.copy(
                message = UserMessage(
                    textRes = resId,
                    args = args.toList(),
                    id = messageIds.incrementAndGet(),
                ),
            )
        }
    }

    override fun onCleared() {
        senderService?.listener = null
        if (isBound) {
            runCatching { getApplication<Application>().unbindService(serviceConnection) }
            isBound = false
        }
        senderService = null
        super.onCleared()
    }
}
