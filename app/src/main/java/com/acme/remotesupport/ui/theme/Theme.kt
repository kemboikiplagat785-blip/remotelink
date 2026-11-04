package com.acme.remotesupport.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF2F6BFF)
private val BrandDark = Color(0xFF1B49C7)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = BrandDark,
)

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Color(0xFF8FB0FF),
)

@Composable
fun RemoteSupportTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
