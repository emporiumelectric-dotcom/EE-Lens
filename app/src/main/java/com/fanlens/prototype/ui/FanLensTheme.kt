package com.fanlens.prototype.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/* Hallmark · pre-emit critique: P5 H5 E5 S5 R5 V5
 * Hallmark · macrostructure: Workbench + Catalogue · genre: modern-minimal
 * palette: warm-white paper · charcoal ink · Electric Emporium red signal
 */
private val ElectricEmporiumColors = lightColorScheme(
    primary = FanLensColors.BrandRed,
    onPrimary = FanLensColors.Paper,
    background = FanLensColors.Paper,
    onBackground = FanLensColors.Ink,
    surface = FanLensColors.Paper,
    onSurface = FanLensColors.Ink,
    surfaceVariant = FanLensColors.PaperRaised,
    onSurfaceVariant = FanLensColors.InkMuted,
    outline = FanLensColors.Rule
)

internal object FanLensColors {
    val Paper = Color(0xFFFFFBFA)
    val PaperRaised = Color(0xFFF5F1F0)
    val Ink = Color(0xFF101923)
    val InkMuted = Color(0xFF59616A)
    val Rule = Color(0xFFD9D5D4)
    val BrandRed = Color(0xFFFF0017)
    val CameraScrim = Color(0xA6101923)
    val CameraInk = Color(0xFFFDF9F7)
    val TrackingBase = Color(0x99FDF9F7)
    val TrackingGlow = Color(0x66101923)

    // Compatibility tokens for the preserved prototype screen.
    val Backdrop = Ink
    val HeaderScrim = CameraScrim
    val OnSurface = CameraInk
    val OnSurfaceMuted = Color(0xFFD4D0CE)
    val Guide = TrackingBase
}

@Composable
fun FanLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ElectricEmporiumColors, content = content)
}
