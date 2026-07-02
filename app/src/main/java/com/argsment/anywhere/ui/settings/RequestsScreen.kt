package com.argsment.anywhere.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.argsment.anywhere.R
import com.argsment.anywhere.viewmodel.VpnViewModel
import com.argsment.anywhere.vpn.RouteAction
import com.argsment.anywhere.vpn.util.RequestEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(
    viewModel: VpnViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val requests by viewModel.requests.collectAsState()

    var selectMode by remember { mutableStateOf(false) }
    val selection = remember { mutableStateMapOf<String, Unit>() }

    DisposableEffect(Unit) {
        viewModel.setRequestRecordingEnabled(true)
        onDispose { viewModel.setRequestRecordingEnabled(false) }
    }

    BackHandler {
        if (selectMode) selectMode = false else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.requests)) }, // Assuming stringResource exists, fallback if not
                navigationIcon = {
                    IconButton(onClick = { if (selectMode) selectMode = false else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (selectMode) {
                        val label = if (selection.isEmpty()) {
                            stringResource(R.string.cancel)
                        } else {
                            stringResource(R.string.copy_count, selection.size)
                        }
                        TextButton(onClick = {
                            if (selection.isEmpty()) {
                                selectMode = false
                            } else {
                                copyToClipboard(
                                    context,
                                    requests.filter { selection.contains(it.id) }.joinToString("\n") { it.formatted() }
                                )
                                selection.clear()
                                selectMode = false
                            }
                        }) { Text(label) }
                    } else {
                        TextButton(onClick = { selectMode = true }) {
                            Text(stringResource(R.string.select))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (requests.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        stringResource(R.string.no_recent_logs),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
            ) {
                // Newest first.
                val reversed = requests.asReversed()
                items(reversed, key = { it.id }) { entry ->
                    RequestRow(
                        entry = entry,
                        selectMode = selectMode,
                        selected = selection.contains(entry.id),
                        onToggleSelect = {
                            if (selection.contains(entry.id)) selection.remove(entry.id)
                            else selection[entry.id] = Unit
                        },
                        onCopy = { copyToClipboard(context, entry.formatted()) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun RequestRow(
    entry: RequestEntry,
    selectMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onCopy: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (selectMode) {
                    Modifier
                        .clickable { onToggleSelect() }
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent
                        )
                } else {
                    Modifier.clickable { showMenu = true }
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val (icon, tint) = when (entry.routeTarget) {
            is RouteAction.Direct -> Icons.Filled.ArrowForward to Color(0xFF2196F3) // Blue
            is RouteAction.Proxy -> Icons.Filled.Security to Color(0xFF4CAF50) // Green
            is RouteAction.Reject -> Icons.Filled.Block to Color(0xFFF44336) // Red
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(16.dp)
                .padding(top = 2.dp)
        )

        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = "${entry.protocolName} ${entry.host}:${entry.port}",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            )
            Spacer(Modifier.size(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(entry.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                val targetText = when (entry.routeTarget) {
                    is RouteAction.Direct -> "DIRECT"
                    is RouteAction.Proxy -> "PROXY"
                    is RouteAction.Reject -> "REJECT"
                }
                Text(
                    text = targetText,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                properties = PopupProperties(focusable = true)
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy)) },
                    onClick = {
                        onCopy()
                        showMenu = false
                    }
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Anywhere Requests", text))
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTime(timestampMs: Long): String = timeFormatter.format(Date(timestampMs))

private fun RequestEntry.formatted(): String {
    val time = formatTime(timestamp)
    val targetText = when (routeTarget) {
        is RouteAction.Direct -> "DIRECT"
        is RouteAction.Proxy -> "PROXY"
        is RouteAction.Reject -> "REJECT"
    }
    return "$time [$protocolName] $host:$port -> $targetText"
}
