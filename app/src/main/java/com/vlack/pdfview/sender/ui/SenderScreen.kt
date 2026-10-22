package com.vlack.pdfview.sender.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlack.pdfview.sender.MainViewModel
import com.vlack.pdfview.sender.R
import com.vlack.pdfview.sender.data.DocumentIO
import com.vlack.pdfview.sender.model.ConnectionState
import com.vlack.pdfview.sender.model.PickedDocument
import com.vlack.pdfview.sender.model.SendFormat
import com.vlack.pdfview.sender.model.SenderUiState
import com.vlack.pdfview.sender.model.TransferState
import com.vlack.pdfview.sender.ui.theme.DocsViewSenderTheme
import java.util.Locale

private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "text/plain",
    "text/markdown",
    "text/x-markdown",
)

@Composable
fun SenderRoute(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val pickDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { !it }) viewModel.notify(R.string.error_bluetooth_permission)
    }

    // The Samsung Accessory Service reads the outgoing file itself, so the file has to live in shared
    // storage. Android 11+ needs "all files access" for that, which is granted in system settings.
    val storageSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }

    fun ensureStorageAccess(): Boolean {
        if (DocumentIO.hasSharedStorageAccess(context)) return true
        viewModel.notify(R.string.error_storage_permission)
        requestStorageAccess(context, storageSettingsLauncher::launch, storagePermissionLauncher::launch)
        return false
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    val message = state.message
    val resources = LocalResources.current
    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        val text = if (current.args.isEmpty()) {
            resources.getString(current.textRes)
        } else {
            resources.getString(current.textRes, *current.args.toTypedArray())
        }
        snackbarHostState.showSnackbar(
            message = text,
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        viewModel.onMessageShown(current.id)
    }

    SenderScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onFileNameChange = viewModel::onFileNameChange,
        onContentChange = viewModel::onContentChange,
        onFormatChange = viewModel::onFormatChange,
        onPickDocument = { if (ensureStorageAccess()) pickDocument.launch(DOCUMENT_MIME_TYPES) },
        onClearDocument = viewModel::clearPickedDocument,
        onConnect = viewModel::connect,
        onSubmit = { if (ensureStorageAccess()) viewModel.submit() },
        onCancelTransfer = viewModel::cancelTransfer,
    )
}

private fun requestStorageAccess(
    context: Context,
    openSettings: (Intent) -> Unit,
    requestPermission: (String) -> Unit,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        val intent = if (appIntent.resolveActivity(context.packageManager) != null) {
            appIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        runCatching { openSettings(intent) }
    } else {
        requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SenderScreen(
    state: SenderUiState,
    snackbarHostState: SnackbarHostState,
    onFileNameChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onFormatChange: (SendFormat) -> Unit,
    onPickDocument: () -> Unit,
    onClearDocument: () -> Unit,
    onConnect: () -> Unit,
    onSubmit: () -> Unit,
    onCancelTransfer: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(colors.surface, colors.surfaceContainerLow, colors.surface),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp)
                    .imePadding(),
            ) {
                val density = LocalDensity.current
                val keyboardVisible = WindowInsets.ime.getBottom(density) > 0

                // Keep the editor usable while the keyboard is open: the decorative header and
                // the connection banner step aside.
                AnimatedVisibility(
                    visible = !keyboardVisible,
                    enter = fadeIn(tween(180)) + expandVertically(tween(240)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
                ) {
                    Header(connection = state.connection)
                }

                AnimatedVisibility(
                    visible = !keyboardVisible && state.connection != ConnectionState.Connected,
                    enter = fadeIn(tween(200)) + expandVertically(tween(260)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(220)),
                ) {
                    ConnectionBanner()
                }

                FileNameField(
                    state = state,
                    onFileNameChange = onFileNameChange,
                )

                Spacer(Modifier.height(10.dp))

                ContentField(
                    state = state,
                    onContentChange = onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                Spacer(Modifier.height(14.dp))

                FormatSelector(
                    format = state.format,
                    enabled = state.isComposing && !state.isSending,
                    onFormatChange = onFormatChange,
                )

                Spacer(Modifier.height(14.dp))

                if (state.pickedDocument == null) {
                    PickDocumentButton(
                        isLoading = state.isImporting,
                        enabled = state.canPickDocument,
                        onClick = onPickDocument,
                    )
                } else {
                    SelectedDocumentCard(
                        document = state.pickedDocument,
                        enabled = !state.isSending,
                        onClear = onClearDocument,
                    )
                }

                Spacer(Modifier.height(12.dp))

                AnimatedContent(
                    targetState = state.isSending,
                    transitionSpec = {
                        (fadeIn(tween(180)) + expandVertically(tween(220)))
                            .togetherWith(fadeOut(tween(120)) + shrinkVertically(tween(180)))
                    },
                    label = "send-action",
                ) { sending ->
                    if (sending) {
                        TransferProgress(
                            percent = (state.transfer as? TransferState.Sending)?.percent ?: 0,
                            onCancel = onCancelTransfer,
                        )
                    } else {
                        PrimaryAction(
                            connection = state.connection,
                            enabled = !state.isImporting,
                            onConnect = onConnect,
                            onSubmit = onSubmit,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun Header(
    connection: ConnectionState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_watch),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.app_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        ConnectionPill(connection = connection)
    }
}

@Composable
private fun ConnectionPill(connection: ConnectionState) {
    val container = when (connection) {
        ConnectionState.Connected -> MaterialTheme.colorScheme.tertiaryContainer
        ConnectionState.Connecting -> MaterialTheme.colorScheme.secondaryContainer
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (connection) {
        ConnectionState.Connected -> MaterialTheme.colorScheme.onTertiaryContainer
        ConnectionState.Connecting -> MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (connection) {
        ConnectionState.Connected -> stringResource(R.string.status_connected_short)
        ConnectionState.Connecting -> stringResource(R.string.status_connecting_short)
        ConnectionState.Disconnected -> stringResource(R.string.status_offline_short)
    }

    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connection == ConnectionState.Connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = content,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (connection == ConnectionState.Connected) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape,
                        ),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ConnectionBanner() {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_bluetooth_searching),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.connection_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.connection_banner_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun FileNameField(
    state: SenderUiState,
    onFileNameChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val errorRes = state.fileNameError

    OutlinedTextField(
        value = state.fileName,
        onValueChange = onFileNameChange,
        enabled = state.isComposing && !state.isSending,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.file_name)) },
        placeholder = { Text(stringResource(R.string.file_name_hint)) },
        suffix = null,
        trailingIcon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = ".${state.format.extension}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        isError = errorRes != null,
        supportingText = errorRes?.let {
            { Text(stringResource(it)) }
        },
        shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Composable
private fun ContentField(
    state: SenderUiState,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorRes = state.contentError
    val characterCount = state.content.length

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (state.isComposing) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.content_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(R.plurals.characters, characterCount, characterCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = state.content,
            onValueChange = onContentChange,
            enabled = state.isComposing && !state.isSending,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            placeholder = {
                Text(
                    text = stringResource(R.string.content_hint),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            isError = errorRes != null,
            supportingText = errorRes?.let {
                { Text(stringResource(it)) }
            },
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FormatSelector(
    format: SendFormat,
    enabled: Boolean,
    onFormatChange: (SendFormat) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.format),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.format_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SendFormat.entries.forEach { candidate ->
                val selected = format == candidate
                ToggleButton(
                    checked = selected,
                    onCheckedChange = { onFormatChange(candidate) },
                    enabled = enabled,
                    modifier = Modifier
                        .height(48.dp)
                        .widthIn(min = 82.dp),
                    shapes = ToggleButtonDefaults.shapesFor(48.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) {
                    Text(
                        text = stringResource(
                            when (candidate) {
                                SendFormat.Txt -> R.string.format_txt
                                SendFormat.Md -> R.string.format_md
                            },
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PickDocumentButton(
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.opening_document))
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.choose_document),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun SelectedDocumentCard(
    document: PickedDocument,
    enabled: Boolean,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.selected_document_hint,
                        formatFileSize(document.sizeBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                )
            }

            IconButton(onClick = onClear, enabled = enabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.clear_selection),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PrimaryAction(
    connection: ConnectionState,
    enabled: Boolean,
    onConnect: () -> Unit,
    onSubmit: () -> Unit,
) {
    val connected = connection == ConnectionState.Connected
    val label = when {
        connected -> stringResource(R.string.send_to_watch)
        connection == ConnectionState.Connecting -> stringResource(R.string.status_connecting)
        else -> stringResource(R.string.connect_watch)
    }

    Button(
        onClick = if (connected) onSubmit else onConnect,
        enabled = enabled && connection != ConnectionState.Connecting,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Icon(
            painter = painterResource(if (connected) R.drawable.ic_send else R.drawable.ic_bluetooth_searching),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TransferProgress(
    percent: Int,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator(
                progress = { percent / 100f },
                modifier = Modifier.size(30.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sending),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = String.format(Locale.ROOT, "%d%%", percent),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onCancel) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.cancel),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (value >= 100) {
        String.format(Locale.ROOT, "%.0f %s", value, units[index])
    } else {
        String.format(Locale.ROOT, "%.1f %s", value, units[index])
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun SenderScreenConnectedPreview() {
    DocsViewSenderTheme {
        SenderScreen(
            state = SenderUiState(
                connection = ConnectionState.Connected,
                fileName = "Конспект",
                content = "# Лекция 4\n\nКритерий Коши сходимости ряда.\n\n" +
                    "Ряд сходится тогда и только тогда, когда для любого ε > 0 " +
                    "существует номер N, начиная с которого любой отрезок суммы ряда по модулю меньше ε.",
                format = SendFormat.Md,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onFileNameChange = {},
            onContentChange = {},
            onFormatChange = {},
            onPickDocument = {},
            onClearDocument = {},
            onConnect = {},
            onSubmit = {},
            onCancelTransfer = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun SenderScreenDisconnectedPreview() {
    DocsViewSenderTheme(darkTheme = true) {
        SenderScreen(
            state = SenderUiState(connection = ConnectionState.Disconnected),
            snackbarHostState = remember { SnackbarHostState() },
            onFileNameChange = {},
            onContentChange = {},
            onFormatChange = {},
            onPickDocument = {},
            onClearDocument = {},
            onConnect = {},
            onSubmit = {},
            onCancelTransfer = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun SenderScreenPickedPreview() {
    DocsViewSenderTheme {
        SenderScreen(
            state = SenderUiState(
                connection = ConnectionState.Connected,
                fileName = "Игнорируется",
                content = "Игнорируется",
                pickedDocument = PickedDocument(
                    name = "coursework.pdf",
                    sizeBytes = 1_240_000,
                    path = "/cache/coursework.pdf",
                ),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onFileNameChange = {},
            onContentChange = {},
            onFormatChange = {},
            onPickDocument = {},
            onClearDocument = {},
            onConnect = {},
            onSubmit = {},
            onCancelTransfer = {},
        )
    }
}
