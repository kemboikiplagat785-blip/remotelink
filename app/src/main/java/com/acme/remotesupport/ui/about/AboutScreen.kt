package com.acme.remotesupport.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.acme.remotesupport.BuildConfig
import com.acme.remotesupport.R
import kotlinx.coroutines.delay

private const val SUPPORT_PHONE = "0701135362"
private const val SUPPORT_WHATSAPP = "254701135362"

private val HeroTop = Color(0xFF2F6BFF)
private val HeroBottom = Color(0xFF12307F)

private class Feature(val icon: ImageVector, val title: String, val detail: String)

private val features = listOf(
    Feature(Icons.Filled.GridView, "Live wall", "See all your phones' screens at once, live."),
    Feature(Icons.Filled.TouchApp, "Full control", "Tap a phone to take over its whole screen."),
    Feature(Icons.Filled.VpnKey, "Unattended access", "Reach your own phone with a PIN, even with nobody there."),
    Feature(Icons.Filled.Autorenew, "Stays connected", "Recovers by itself when the network drops, on Wi-Fi or mobile data."),
    Feature(Icons.Filled.VerifiedUser, "You stay in charge", "Nothing is shared until you approve it, and you can stop anytime."),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val appName = stringResource(R.string.app_name).replace('_', ' ')

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Hero(appName, onBack)
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Reveal(order = 0) { SupportCard() }
            Reveal(order = 1) { FeaturesCard() }
            Reveal(order = 2) {
                Text(
                    "$appName · Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Hero(appName: String, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .background(Brush.verticalGradient(listOf(HeroTop, HeroBottom))),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PulsingLogo()
            Text(
                appName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "All your phones, one screen.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A phone sending out signal rings, the app's idea in one picture. */
@Composable
private fun PulsingLogo() {
    val pulse = rememberInfiniteTransition(label = "logo pulse")
    Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
        repeat(2) { ring ->
            val progress by pulse.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(ring * 1200),
                ),
                label = "ring $ring",
            )
            Box(
                Modifier
                    .size(128.dp)
                    .graphicsLayer {
                        val size = 0.55f + 0.45f * progress
                        scaleX = size
                        scaleY = size
                        alpha = 1f - progress
                    }
                    .background(Color.White.copy(alpha = 0.35f), CircleShape),
            )
        }
        Box(
            Modifier
                .size(72.dp)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = HeroTop,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun SupportCard() {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SupportAgent,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    "Need help?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                "For support or any problem, reach us on:",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { copyNumber(context) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    SUPPORT_PHONE,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy number",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ContactAction(Icons.Filled.Call, "Call", Modifier.weight(1f)) {
                    open(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$SUPPORT_PHONE")))
                }
                ContactAction(Icons.Filled.Sms, "SMS", Modifier.weight(1f)) {
                    open(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$SUPPORT_PHONE")))
                }
                ContactAction(Icons.AutoMirrored.Filled.Chat, "WhatsApp", Modifier.weight(1f)) {
                    open(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$SUPPORT_WHATSAPP")))
                }
            }
        }
    }
}

@Composable
private fun ContactAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FeaturesCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "What it does",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            features.forEach { feature ->
                Row {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            feature.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(feature.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            feature.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Slides a section into place, each one slightly after the one above it. */
@Composable
private fun Reveal(order: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100L * order)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 },
    ) { content() }
}

private fun copyNumber(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Support number", SUPPORT_PHONE))
    Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
}

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show() }
}
