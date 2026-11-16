package com.hollow.vpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/**
 * Expressive Material 3 grouped list container shapes (24.dp outer corners, 6.dp inner joints).
 */
object GroupedItemShapes {
    val Single: Shape = RoundedCornerShape(24.dp)
    val Top: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
    val Middle: Shape = RoundedCornerShape(6.dp)
    val Bottom: Shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp)

    fun forIndex(index: Int, count: Int): Shape = when {
        count <= 1 -> Single
        index == 0 -> Top
        index == count - 1 -> Bottom
        else -> Middle
    }
}
