package me.bubblespark.tidilo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Expressive M3 Light Colors
val PrimaryLight = Color(0xFF006874)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFF97F0FF)
val OnPrimaryContainerLight = Color(0xFF001F24)

val SecondaryLight = Color(0xFF4A6267)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFCDE7EC)
val OnSecondaryContainerLight = Color(0xFF051F23)

val TertiaryLight = Color(0xFF525E7D)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFDAE2FF)
val OnTertiaryContainerLight = Color(0xFF0E1A37)

val BackgroundLight = Color(0xFFF8FAF9)
val OnBackgroundLight = Color(0xFF191C1C)
val SurfaceLight = Color(0xFFF8FAF9)
val OnSurfaceLight = Color(0xFF191C1C)
val SurfaceVariantLight = Color(0xFFDBE4E6)
val OnSurfaceVariantLight = Color(0xFF3F484A)
val OutlineLight = Color(0xFF6F797A)

// Expressive M3 Dark Colors
val PrimaryDark = Color(0xFF4FD8EB)
val OnPrimaryDark = Color(0xFF00363D)
val PrimaryContainerDark = Color(0xFF004F58)
val OnPrimaryContainerDark = Color(0xFF97F0FF)

val SecondaryDark = Color(0xFFB1CBD0)
val OnSecondaryDark = Color(0xFF1C3438)
val SecondaryContainerDark = Color(0xFF334B4F)
val OnSecondaryContainerDark = Color(0xFFCDE7EC)

val TertiaryDark = Color(0xFFBAC6EA)
val OnTertiaryDark = Color(0xFF24304D)
val TertiaryContainerDark = Color(0xFF3B4664)
val OnTertiaryContainerDark = Color(0xFFDAE2FF)

val BackgroundDark = Color(0xFF0E1415)
val OnBackgroundDark = Color(0xFFDEE3E4)
val SurfaceDark = Color(0xFF0E1415)
val OnSurfaceDark = Color(0xFFDEE3E4)
val SurfaceVariantDark = Color(0xFF3F484A)
val OnSurfaceVariantDark = Color(0xFFBFC8CA)
val OutlineDark = Color(0xFF899294)

// Accent Tag Colors for Expressive Chips
val TagTech = Color(0xFF388E3C)
val TagAI = Color(0xFF7B1FA2)
val TagDesign = Color(0xFFE64A19)
val TagNews = Color(0xFF0288D1)

// Warning Colors (Semantic Amber/Gold for AI Skipped / Warnings)
val WarningLight = Color(0xFFB87700)
val WarningDark = Color(0xFFFFBA28)

object WarningColor {
    val textOrIcon: Color
        @Composable
        get() = if (isSystemInDarkTheme()) WarningDark else WarningLight
}
