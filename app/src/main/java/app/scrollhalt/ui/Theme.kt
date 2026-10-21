package app.scrollhalt.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Deep green of the launcher icon background, used behind the header mark in both themes. */
val BrandGreen: Color = Color(0xFF0F3D3A)

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color(0xFFF5EFE3),
    primaryContainer = Color(0xFFD6EAE5),
    onPrimaryContainer = BrandGreen,
    secondary = Color(0xFFF2B23A),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFFFCEBC6),
    onSecondaryContainer = Color(0xFF3A2A05),
    background = Color(0xFFFAF8F3),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFEFECE4),
    onSurfaceVariant = Color(0xFF5B5952),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF4F1EA),
    surfaceContainerHighest = Color(0xFFE4E0D6),
    outline = Color(0xFF8A877E),
    outlineVariant = Color(0xFFDDD9CF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FC7BC),
    onPrimary = Color(0xFF00201D),
    primaryContainer = Color(0xFF17403B),
    onPrimaryContainer = Color(0xFFBFE8E0),
    secondary = Color(0xFFF2B23A),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF4A3810),
    onSecondaryContainer = Color(0xFFFBE2AE),
    background = Color(0xFF0B1312),
    onBackground = Color(0xFFECE8DF),
    surface = Color(0xFF121C1B),
    onSurface = Color(0xFFECE8DF),
    surfaceVariant = Color(0xFF1C2826),
    onSurfaceVariant = Color(0xFFA9B2AE),
    surfaceContainerLowest = Color(0xFF0B1312),
    surfaceContainerLow = Color(0xFF121C1B),
    surfaceContainer = Color(0xFF121C1B),
    surfaceContainerHigh = Color(0xFF1C2826),
    surfaceContainerHighest = Color(0xFF26332F),
    outline = Color(0xFF7D8884),
    outlineVariant = Color(0xFF2C3A37),
)

private val AppShapes = Shapes(
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun ScrollhaltTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        typography = base.copy(titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold)),
        content = content,
    )
}
