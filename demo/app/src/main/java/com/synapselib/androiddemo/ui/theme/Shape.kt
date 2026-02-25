package com.synapselib.androiddemo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp), // Standard for Cards and list items
    large = RoundedCornerShape(28.dp),  // Expressive curve for Floating Action Buttons
    extraLarge = RoundedCornerShape(32.dp) // Highly rounded for Bottom Sheets and large Dialogs
)