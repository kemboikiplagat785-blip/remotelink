package com.acme.remotesupport.ui.devices

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.acme.remotesupport.accessibility.ControlAccessibilityService
import com.acme.remotesupport.data.local.ControllerPinStore
import com.acme.remotesupport.data.remote.DeviceDto
import com.acme.remotesupport.ui.control.LinkStatus
import com.acme.remotesupport.ui.control.LiveTile
import com.acme.remotesupport.ui.control.LiveWall

private val LiveGreen = Color(0xFF2E7D32)
private val Idle = Color(0xFF9E9E9E)

/** The live wall: every paired phone's screen at once; tap a live one to control it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceGridScreen(
    onOpenDevice: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenAbout: () -> Unit,
    vm: DeviceGridViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val tiles by LiveWall.tiles.collectAsState()
    val context = LocalContext.current
    // Until this phone knows its own id, it can't tell itself apart from the others.
    val selfKnown = state.selfDeviceId != null
    val others = if (selfKnown) state.devices.filter { it.id != state.selfDeviceId } else emptyList()
    val reachable = others.filter { it.online && !it.blocked }
    var managed by remember { mutableStateOf<DeviceDto?>(null) }
    var pinPrompt by remember { mutableStateOf<DeviceDto?>(null) }

    // Connect, asking for the PIN first if this is an unattended device we don't have one for.
    fun connectTo(device: DeviceDto) {
        if (device.unattended && ControllerPinStore.get(context, device.id) == null) {
            pinPrompt = device
        } else {
            LiveWall.connect(context, device.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                actions = {
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Filled.Info, contentDescription = "About")
                    }
                    IconButton(onClick = onOpenSubscription) {
                        Icon(Icons.Filled.Star, contentDescription = "Subscription")
                    }
                    IconButton(onClick = onOpenProfile) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "Profile")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { ControlAccessBanner() }

            if (!selfKnown) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (others.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "No other phones yet. Sign in to this account on another phone to pair it.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (tiles.isEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { reachable.forEach { LiveWall.connect(context, it.id) } },
                                enabled = reachable.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("View all online phones live") }
                            Text(
                                "Tip: long-press a phone to rename or remove it.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Button(
                            onClick = { LiveWall.stopAll() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Stop all") }
                    }
                }
                items(others, key = { it.id }) { device ->
                    val tile = tiles[device.id]
                    PhoneTile(
                        device = device,
                        tile = tile,
                        onClick = {
                            when {
                                device.blocked -> managed = device
                                tile?.status == LinkStatus.LIVE -> onOpenDevice(device.id)
                                tile == null || tile.status.isOver -> connectTo(device)
                            }
                        },
                        onLongClick = { managed = device },
                    )
                }
            }
        }
    }

    pinPrompt?.let { device ->
        ConnectPinDialog(
            device = device,
            onDismiss = { pinPrompt = null },
            onConfirm = { pin ->
                // Kept only after the target accepts it (see LiveWall); passed in for this connect.
                LiveWall.connect(context, device.id, pin)
                pinPrompt = null
            },
        )
    }

    managed?.let { device ->
        ManageDeviceDialog(
            device = device,
            onDismiss = { managed = null },
            onRename = { vm.rename(device.id, it) },
            onToggleBlock = { vm.setBlocked(device.id, !device.blocked) },
            onRemove = {
                LiveWall.stop(device.id)
                vm.delete(device.id)
                managed = null
            },
        )
    }
}

private val LinkStatus.isOver: Boolean
    get() = this !in setOf(LinkStatus.WAITING, LinkStatus.CONNECTING, LinkStatus.LIVE)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneTile(
    device: DeviceDto,
    tile: LiveTile?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val live = tile?.status == LinkStatus.LIVE
    Card(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .background(Color.Black)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            tile?.track?.let { com.acme.remotesupport.ui.control.VideoSurface(it, Modifier.fillMaxSize()) }
            if (!live) {
                Text(
                    tileHint(device, tile),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(8.dp)
                        .background(if (live || device.online) LiveGreen else Idle, shape = CircleShape),
                )
                Text(tileLabel(device, tile), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ManageDeviceDialog(
    device: DeviceDto,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onToggleBlock: () -> Unit,
    onRemove: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(device.name) }
    var confirmRemove by remember { mutableStateOf(false) }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${device.name}?") },
            text = { Text("This removes the phone from your account. If it signs in again it will reappear.") },
            confirmButton = {
                TextButton(onClick = onRemove) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.name) },
        text = {
            if (renaming) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Device name") },
                    singleLine = true,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(device.model ?: device.manufacturer ?: "Android device")
                    Text(
                        if (device.online) "Online" else "Offline",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        confirmButton = {
            if (renaming) {
                TextButton(onClick = {
                    if (newName.isNotBlank()) onRename(newName.trim())
                    onDismiss()
                }) { Text("Save") }
            } else {
                TextButton(onClick = { confirmRemove = true }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            if (renaming) {
                TextButton(onClick = { renaming = false }) { Text("Cancel") }
            } else {
                Row {
                    TextButton(onClick = { renaming = true }) { Text("Rename") }
                    TextButton(onClick = { onToggleBlock(); onDismiss() }) {
                        Text(if (device.blocked) "Unblock" else "Block")
                    }
                }
            }
        },
    )
}

@Composable
private fun ConnectPinDialog(
    device: DeviceDto,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val valid = pin.length in 4..8
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN for ${device.name}") },
        text = {
            Column {
                Text(
                    "This device is set up for unattended access. Enter its PIN once; " +
                        "it's saved on this phone for next time.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8) pin = it.filter(Char::isDigit) },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pin) }, enabled = valid) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun tileLabel(device: DeviceDto, tile: LiveTile?): String = when {
    device.blocked -> "Blocked"
    tile?.status == LinkStatus.LIVE && tile.canControl == false -> "Live, view only"
    tile?.status == LinkStatus.LIVE -> "Live"
    device.online -> "Online"
    else -> "Offline"
}

private fun tileHint(device: DeviceDto, tile: LiveTile?): String = when (tile?.status) {
    LinkStatus.WAITING -> "Waiting for approval on that phone…"
    LinkStatus.CONNECTING -> "Connecting…"
    LinkStatus.LIVE -> ""
    LinkStatus.DECLINED -> "Declined. Tap to ask again."
    LinkStatus.NO_RESPONSE -> "No response. Tap to ask again."
    LinkStatus.ENDED -> "Session ended. Tap to reconnect."
    LinkStatus.ERROR -> tile.message?.let { "$it Tap to retry." } ?: "Connection failed. Tap to retry."
    null -> when {
        device.blocked -> "Blocked. Long-press to manage."
        device.online -> "Tap to view live"
        else -> "Open the app on that phone, then tap to view live"
    }
}

/** Android only lets the user switch this on, and without it a phone can be viewed but not controlled. */
@Composable
private fun ControlAccessBanner() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(ControlAccessibilityService.instance != null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = ControlAccessibilityService.instance != null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (enabled) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "To let your other phones control this one, turn on control access in Accessibility settings.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("Open settings")
            }
        }
    }
}
