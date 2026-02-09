package com.onguard.presentation.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

fun String.toColor() = Color(android.graphics.Color.parseColor(if (this.startsWith("#")) this else "#$this"))

val UnprotectBGGradientStart = "#EA5029".toColor()
val ProtectBGGradientStart = "#2979ea".toColor()
val UnprotectDarkBGGradientStart = "#5E0B0B".toColor()
val ProtectDarkBGGradientStart = "#143D95".toColor()
val BGGradientEnd = "#FFFFFF".toColor()

val BrandGradientRed = Brush.verticalGradient(
    colors = listOf(UnprotectBGGradientStart, BGGradientEnd)
)

val BrandGradientBlue = Brush.verticalGradient(
    colors = listOf(ProtectBGGradientStart, BGGradientEnd)
)

val BrandGradientDarkRed = Brush.verticalGradient(
    colors = listOf(UnprotectDarkBGGradientStart, BGGradientEnd)
)

val BrandGradientDarkBlue = Brush.verticalGradient(
    colors = listOf(ProtectDarkBGGradientStart, BGGradientEnd)
)

val RiskHigh = "#EA5029".toColor()
val RiskMedium = "#FF7A05".toColor()
val RiskLow =  "#FFA705".toColor()
val RiskSafe =  "#0AAF00".toColor()
val SearhKeyword = "#0AAF00".toColor()
val SearhTime = "#0090FF".toColor()

val TextWhite = Color.White
val TextPrimary = Color(0xFF212121)
val TextSecondary = Color(0xFF757575)

val CardBackground = Color.White
val BackgroundLight = Color(0xFFF5F5F5)