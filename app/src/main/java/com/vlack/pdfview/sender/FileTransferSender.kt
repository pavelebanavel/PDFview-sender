package com.vlack.pdfview.sender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.samsung.android.sdk.SsdkUnsupportedException
import com.samsung.android.sdk.accessory.SAAgent
import com.samsung.android.sdk.accessory.SAPeerAgent
import com.samsung.android.sdk.accessory.SASocket
import com.samsung.android.sdk.accessoryfiletransfer.SAFileTransfer
import com.samsung.android.sdk.accessoryfiletransfer.SAft

/**
 * Service-provider side of the Samsung Accessory file transfer profile used by the
 * Docs View / PDFview watch app. The protocol itself is unchanged, but the service no longer
 * talks to the UI through PendingIntent result codes: the screen observes a [Listener] instead.
 */
class FileTransferSender : SAAgent(TAG, ServiceConnection::class.java) {

    interface Listener {
        fun onConnectionStateChanged(connected: Boolean)
        fun onTransferProgress(percent: Int)
        fun onTransferCompleted()
        fun onTransferCancelled()
        fun onTransferFailed(errorCode: Int)
        fun onMessage(@StringRes resId: Int)
    }

    private val binder = SenderBinder()

    @Volatile
    var listener: Listener? = null

    private var transactionId = -1
    private var lastErrorCode = SAFileTransfer.ERROR_NONE
    private var peerAgent: SAPeerAgent? = null
    private var fileTransfer: SAFileTransfer? = null
    private var sdkReady = false

    private val fileTransferCallback = object : SAFileTransfer.EventListener {
        override fun onProgressChanged(transId: Int, progress: Int) {
            if (transId != transactionId) return
            listener?.onTransferProgress(progress.coerceIn(0, 100))
        }

        override fun onTransferCompleted(transId: Int, fileName: String?, errorCode: Int) {
            Log.d(TAG, "onTransferCompleted: id=$transId file=$fileName error=$errorCode")
            lastErrorCode = errorCode
            transactionId = -1
            when (errorCode) {
                SAFileTransfer.ERROR_NONE -> listener?.onTransferCompleted()
                SAFileTransfer.ERROR_PEER_AGENT_REJECTED,
                SAFileTransfer.ERROR_CONNECTION_LOST -> listener?.onTransferCancelled()
                else -> listener?.onTransferFailed(errorCode)
            }
        }

        override fun onTransferRequested(id: Int, fileName: String?) {
            // The sender never receives files.
        }

        override fun onCancelAllCompleted(errorCode: Int) {
            Log.d(TAG, "onCancelAllCompleted: error=$errorCode")
            when (errorCode) {
                SAFileTransfer.ERROR_NONE -> listener?.onTransferCancelled()
                SAFileTransfer.ERROR_TRANSACTION_NOT_FOUND ->
                    listener?.onMessage(R.string.onCancelAllCompletedNotFound)
                SAFileTransfer.ERROR_NOT_SUPPORTED ->
                    listener?.onMessage(R.string.onCancelAllCompletedNotSupported)
                else -> listener?.onTransferFailed(errorCode)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startRunningInForeground()

        sdkReady = try {
            SAft().initialize(this)
            fileTransfer = SAFileTransfer(this, fileTransferCallback)
            true
        } catch (e: SsdkUnsupportedException) {
            val message = when (e.type) {
                SsdkUnsupportedException.DEVICE_NOT_SUPPORTED -> R.string.DEVICE_NOT_SUPPORTED
                SsdkUnsupportedException.LIBRARY_NOT_INSTALLED -> R.string.LIBRARY_NOT_INSTALLED
                else -> R.string.UNKNOWN_ERROR
            }
            listener?.onMessage(message)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Cannot initialize Samsung Accessory SDK", e)
            listener?.onMessage(R.string.CANNOT_INIT)
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { fileTransfer?.close() }
        fileTransfer = null
        listener = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun connect() {
        if (!sdkReady || fileTransfer == null) {
            listener?.onMessage(R.string.CANNOT_INIT)
            listener?.onConnectionStateChanged(false)
            return
        }

        val peer = peerAgent
        if (peer != null) {
            requestServiceConnection(peer)
        } else {
            findPeerAgents()
        }
    }

    /**
     * @return true when the transfer was queued.
     */
    fun sendFile(path: String): Boolean {
        val transfer = fileTransfer
        val peer = peerAgent
        if (!sdkReady || transfer == null || peer == null) {
            listener?.onMessage(R.string.NO_PEER_FOUND_ON_SEND)
            listener?.onConnectionStateChanged(false)
            findPeerAgents()
            return false
        }

        return try {
            val file = java.io.File(path)
            Log.d(TAG, "sendFile: $path exists=${file.exists()} canRead=${file.canRead()} size=${file.length()}")
            val id = transfer.send(peer, path)
            if (id < 0) {
                listener?.onMessage(R.string.error_send_failed)
                false
            } else {
                transactionId = id
                true
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "sendFile", e)
            listener?.onMessage(R.string.ILLEGAL_ARGUMENT)
            false
        } catch (e: RuntimeException) {
            Log.e(TAG, "sendFile", e)
            listener?.onMessage(R.string.error_send_failed)
            false
        }
    }

    fun cancelFileTransfer() {
        val transfer = fileTransfer ?: return
        if (transactionId < 0) return
        runCatching { transfer.cancel(transactionId) }
            .onFailure { listener?.onMessage(R.string.ILLEGAL_ARGUMENT) }
    }

    override fun onFindPeerAgentsResponse(peerAgents: Array<SAPeerAgent>?, result: Int) {
        val peers = peerAgents.orEmpty()
        if (peers.isEmpty()) {
            Log.w(TAG, "No peer agent found: $result")
            listener?.onMessage(R.string.NO_AGENT_FOUND)
            listener?.onConnectionStateChanged(false)
            return
        }

        peerAgent = peers.last()
        requestServiceConnection(peers.last())
    }

    override fun onPeerAgentsUpdated(peerAgents: Array<SAPeerAgent>?, result: Int) {
        val peers = peerAgents.orEmpty()
        if (peers.isNotEmpty()) {
            peerAgent = peers.last()
        }

        if (result == SAAgent.PEER_AGENT_UNAVAILABLE) {
            if (lastErrorCode != SAFileTransfer.ERROR_CONNECTION_LOST) {
                cancelFileTransfer()
            }
            peerAgent = null
            listener?.onConnectionStateChanged(false)
        }
    }

    override fun onServiceConnectionResponse(peerAgent: SAPeerAgent?, socket: SASocket?, result: Int) {
        Log.i(TAG, "onServiceConnectionResponse: result=$result socket=$socket")
        when {
            socket != null -> {
                this.peerAgent = peerAgent
                listener?.onConnectionStateChanged(true)
            }
            result == SAAgent.CONNECTION_ALREADY_EXIST -> {
                this.peerAgent = peerAgent
                listener?.onConnectionStateChanged(true)
            }
            else -> {
                listener?.onMessage(R.string.CONNECTION_NOT_MADE)
                listener?.onConnectionStateChanged(false)
            }
        }
    }

    override fun onError(peerAgent: SAPeerAgent?, errorMessage: String?, errorCode: Int) {
        Log.e(TAG, "onError: $errorMessage ($errorCode)")
        listener?.onMessage(R.string.CONNECTION_NOT_MADE)
        listener?.onConnectionStateChanged(false)
    }

    private fun startRunningInForeground() {
        // A connectedDevice foreground service needs a granted Bluetooth permission on Android 14+.
        // On the very first launch the runtime dialog has not been answered yet, so skip quietly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Bluetooth permission not granted yet, skipping foreground mode")
            return
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            // Without Bluetooth permissions Android 14+ rejects the foreground service.
            // The bound service still works while the app is in the foreground.
            Log.w(TAG, "Cannot start foreground service", it)
        }
    }

    inner class SenderBinder : Binder() {
        val service: FileTransferSender
            get() = this@FileTransferSender
    }

    inner class ServiceConnection : SASocket(ServiceConnection::class.java.name) {
        override fun onServiceConnectionLost(reason: Int) {
            Log.e(TAG, "onServiceConnectionLost: $reason")
        }

        override fun onReceive(channelId: Int, data: ByteArray?) = Unit

        override fun onError(channelId: Int, errorMessage: String?, errorCode: Int) {
            Log.e(TAG, "socket error: $errorMessage ($errorCode)")
        }
    }

    companion object {
        private const val TAG = "FileTransferSender"
        private const val CHANNEL_ID = "transfer"
        private const val NOTIFICATION_ID = 1
    }
}
