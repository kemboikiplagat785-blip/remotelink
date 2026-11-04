package com.acme.remotesupport

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.acme.remotesupport.data.ServiceLocator
import com.acme.remotesupport.service.ScreenCaptureService
import com.acme.remotesupport.ui.AppCoordinator
import com.acme.remotesupport.ui.about.AboutScreen
import com.acme.remotesupport.ui.auth.AuthScreen
import io.github.jan.supabase.auth.status.SessionStatus
import com.acme.remotesupport.ui.control.RemoteControlScreen
import com.acme.remotesupport.ui.devices.DeviceGridScreen
import com.acme.remotesupport.ui.profile.ProfileScreen
import com.acme.remotesupport.ui.subscription.SubscriptionScreen
import com.acme.remotesupport.ui.theme.RemoteSupportTheme

object Routes {
    const val AUTH = "auth"
    const val DEVICES = "devices"
    const val CONTROL = "control/{deviceId}"
    const val PROFILE = "profile"
    const val SUBSCRIPTION = "subscription"
    const val ABOUT = "about"
    fun control(deviceId: String) = "control/$deviceId"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resumeCapture = intent?.getBooleanExtra(EXTRA_RESUME_CAPTURE, false) == true
        setContent {
            RemoteSupportTheme {
                // Gives every screen a themed background and content color.
                Surface(Modifier.fillMaxSize()) { AppRoot(resumeCapture = resumeCapture) }
            }
        }
    }

    companion object {
        /** Set by the post-reboot notification so the app re-prompts to resume sharing. */
        const val EXTRA_RESUME_CAPTURE = "resume_capture"
    }
}

@Composable
private fun AppRoot(resumeCapture: Boolean = false) {
    val status by ServiceLocator.auth.sessionStatus.collectAsState()
    if (status is SessionStatus.Initializing) {
        // Restoring a saved session: wait, so a signed-in user doesn't flash through sign-in.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val uid = ServiceLocator.auth.currentUserId
    AppNav(uid = if (status is SessionStatus.Authenticated) uid else null, resumeCapture = resumeCapture)
}

@Composable
private fun AppNav(uid: String?, resumeCapture: Boolean = false) {
    val authed = uid != null
    // Bound during composition: the destinations below read these repositories as they compose.
    if (uid != null) ServiceLocator.bind(uid)

    val nav = rememberNavController()
    val context = LocalContext.current
    val startDestination = remember { if (authed) Routes.DEVICES else Routes.AUTH }

    // Without this, Android 13+ hides the notification telling the user their screen is shared.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Target-side consent prompt: a controller wants to start a session on this device.
    val incoming by AppCoordinator.incoming.collectAsState()
    incoming?.let { session ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Allow remote session?") },
            text = {
                Text(
                    "Another device signed in to your account wants to view and control " +
                        "this device. Screen sharing starts only after you approve the next " +
                        "system prompt. You can stop the session at any time. \"Always allow\" " +
                        "skips this dialog for that device next time.",
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        AppCoordinator.approveAlways(context, session)
                    }) { Text("Always allow") }
                    TextButton(onClick = {
                        AppCoordinator.approve(context, session)
                    }) { Text("Allow once") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppCoordinator.decline(session.id, session.controllerDevice)
                }) { Text("Decline") }
            },
        )
    }

    NavHost(
        navController = nav,
        startDestination = startDestination,
    ) {
        composable(Routes.AUTH) { AuthScreen() }
        composable(Routes.DEVICES) {
            DeviceGridScreen(
                onOpenDevice = { id -> nav.navigate(Routes.control(id)) },
                onOpenProfile = { nav.navigate(Routes.PROFILE) },
                onOpenSubscription = { nav.navigate(Routes.SUBSCRIPTION) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.CONTROL) { backStack ->
            val deviceId = backStack.arguments?.getString("deviceId").orEmpty()
            RemoteControlScreen(targetDeviceId = deviceId, onBack = { nav.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.PROFILE) {
            ProfileScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SUBSCRIPTION) {
            SubscriptionScreen(onBack = { nav.popBackStack() })
        }
    }

    // Session status is the single source of truth for signed-in vs signed-out navigation.
    LaunchedEffect(uid) {
        if (uid != null) {
            AppCoordinator.onAuthenticated(context, uid)
            if (nav.currentDestination?.route == Routes.AUTH) {
                nav.navigate(Routes.DEVICES) { popUpTo(Routes.AUTH) { inclusive = true } }
            }
            // Resumed from the post-reboot notification: re-show the system capture prompt
            // so sharing comes back with one tap. No capture starts without that approval.
            if (resumeCapture && !ScreenCaptureService.capturing) {
                com.acme.remotesupport.service.RequestCaptureActivity.request(context, null, null)
            }
        } else {
            AppCoordinator.onSignOut()
            if (nav.currentDestination?.route != Routes.AUTH) {
                nav.navigate(Routes.AUTH) { popUpTo(nav.graph.id) { inclusive = true } }
            }
        }
    }
}
