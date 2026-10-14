package me.bubblespark.tidilo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),      // Expressive cards & dialogs
    large = RoundedCornerShape(28.dp),       // Expressive floating surfaces & sheets
    extraLarge = RoundedCornerShape(36.dp)   // Prominent pill containers
)
