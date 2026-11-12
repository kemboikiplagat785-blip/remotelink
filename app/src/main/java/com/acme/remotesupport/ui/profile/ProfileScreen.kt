package com.acme.remotesupport.ui.profile

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.acme.remotesupport.accessibility.ControlAccessibilityService
import com.acme.remotesupport.data.ServiceLocator
import com.acme.remotesupport.data.local.PowerExemption
import com.acme.remotesupport.data.local.UnattendedAccess
import com.acme.remotesupport.service.RequestCaptureActivity
import com.acme.remotesupport.service.ScreenCaptureService
import com.acme.remotesupport.ui.control.LiveWall
import com.acme.remotesupport.ui.AppCoordinator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val email = ServiceLocator.auth.currentEmail ?: "—"
    var feedback by remember { mutableStateOf("") }
    var feedbackSent by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Signed in as", style = MaterialTheme.typography.labelMedium)
                    Text(email, style = MaterialTheme.typography.titleMedium)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Allow this device to be controlled", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "To let an approved session control this device, enable the " +
                            "Remote clone accessibility service. You can turn it off anytime.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    val enabled = ControlAccessibilityService.isEnabled
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (enabled) "Accessibility enabled — manage" else "Enable accessibility service")
                    }
                }
            }

            UnattendedAccessCard()

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Feedback", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = feedback,
                        onValueChange = { feedback = it; feedbackSent = false },
                        label = { Text("Your message") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { ServiceLocator.misc.sendFeedback(feedback) }
                                feedback = ""; feedbackSent = true
                            }
                        },
                        enabled = feedback.isNotBlank(),
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(if (feedbackSent) "Sent — thank you" else "Send feedback") }
                }
            }

            OutlinedButton(
                onClick = {
                    // Sharing must not outlive the account that approved it. Navigation
                    // back to sign-in follows the session status.
                    LiveWall.stopAll()
                    ScreenCaptureService.stop(context)
                    scope.launch {
                        runCatching {
                            AppCoordinator.selfDeviceId.value?.let { ServiceLocator.devices.setOnline(it, false) }
                        }
                        runCatching { ServiceLocator.auth.signOut() }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign out") }
        }
    }
}

/**
 * Lets the owner enrol THIS phone for unattended access: set a PIN once, then a trusted
 * controller can reconnect without a live accept here — gated by that PIN. Android still
 * shows its own screen-capture prompt once per session's first capture after a reboot,
 * which "Start sharing now" triggers.
 */
@Composable
private fun UnattendedAccessCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selfId by AppCoordinator.selfDeviceId.collectAsState()
    var enabled by remember { mutableStateOf(UnattendedAccess.isEnabled(context)) }
    var showPinDialog by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Unattended access", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = enabled,
                    onCheckedChange = { want ->
                        if (want) {
                            showPinDialog = true
                        } else {
                            UnattendedAccess.disable(context)
                            enabled = false
                            ScreenCaptureService.stop(context)
                            scope.launch {
                                runCatching { selfId?.let { ServiceLocator.devices.setUnattended(it, false) } }
                            }
                        }
                    },
                )
            }
            Text(
                "Let your other phones reconnect to this one without someone tapping " +
                    "Accept here each time. You set a PIN once; a controller must enter it to " +
                    "connect. A sharing notification stays visible while it's on, and after a " +
                    "restart you tap once to resume.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (enabled) {
                OutlinedButton(
                    onClick = { RequestCaptureActivity.request(context, null, null) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (ScreenCaptureService.capturing) "Sharing is on" else "Start sharing now") }
                TextButton(onClick = { showPinDialog = true }) { Text("Change PIN") }
            }
        }
    }

    if (showPinDialog) {
        PinSetupDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                UnattendedAccess.setPin(context, pin)
                enabled = true
                showPinDialog = false
                scope.launch {
                    runCatching { selfId?.let { ServiceLocator.devices.setUnattended(it, true) } }
                }
                // Keep the capture service alive while the phone is idle, so a trusted
                // controller can reconnect later with no one here to tap Accept.
                PowerExemption.request(context)
                // Prompt to start sharing straight away so the device is reachable now.
                RequestCaptureActivity.request(context, null, null)
            },
        )
    }
}

@Composable
private fun PinSetupDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val digits = pin.length in 4..8 && pin.all { it.isDigit() }
    val match = pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "4–8 digits. Your other phones enter this to connect without a tap here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8) pin = it.filter(Char::isDigit) },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (it.length <= 8) confirm = it.filter(Char::isDigit) },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = confirm.isNotEmpty() && !match,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = digits && match) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
