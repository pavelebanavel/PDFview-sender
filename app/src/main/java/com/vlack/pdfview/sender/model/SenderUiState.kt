package com.vlack.pdfview.sender.model

import androidx.annotation.StringRes

/** File format the composed document is saved as. */
enum class SendFormat(val extension: String) {
    Txt("txt"),
    Md("md"),
}

/** Connection state of the Samsung Accessory service. */
enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
}

/** A document that was picked from the device and is sent as is. */
data class PickedDocument(
    val name: String,
    val sizeBytes: Long,
    val path: String,
    /** True when [path] is a private copy made by this app (safe to delete), false for the user's own file. */
    val staged: Boolean = true,
)

sealed interface TransferState {
    data object Idle : TransferState
    data class Sending(val percent: Int) : TransferState
}

/** One-off message shown in a snackbar. */
data class UserMessage(
    @param:StringRes val textRes: Int,
    val args: List<Any> = emptyList(),
    val id: Long = 0L,
)

data class SenderUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val fileName: String = "",
    val content: String = "",
    val format: SendFormat = SendFormat.Txt,
    val pickedDocument: PickedDocument? = null,
    val transfer: TransferState = TransferState.Idle,
    val isImporting: Boolean = false,
    val fileNameError: Int? = null,
    val contentError: Int? = null,
    val message: UserMessage? = null,
) {
    val isSending: Boolean
        get() = transfer is TransferState.Sending

    /** Fields are ignored while a ready document is selected. */
    val isComposing: Boolean
        get() = pickedDocument == null

    val canPickDocument: Boolean
        get() = !isSending && !isImporting
}
