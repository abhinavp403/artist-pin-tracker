package dev.abhinav.artistpin.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BluePrimaryDark,
    onPrimary = BlueOnPrimaryDark,
    primaryContainer = BluePrimaryContainerDark,
    onPrimaryContainer = BlueOnPrimaryContainerDark,
    secondary = BlueSecondaryDark,
    onSecondary = BlueOnSecondaryDark,
    secondaryContainer = BlueSecondaryContainerDark,
    onSecondaryContainer = BlueOnSecondaryContainerDark,
    tertiary = BlueTertiaryDark,
    onTertiary = BlueOnTertiaryDark,
    tertiaryContainer = BlueTertiaryContainerDark,
    onTertiaryContainer = BlueOnTertiaryContainerDark,
    background = BlueBackgroundDark,
    onBackground = BlueOnBackgroundDark,
    surface = BlueBackgroundDark,
    onSurface = BlueOnBackgroundDark,
    surfaceVariant = BlueSurfaceVariantDark,
    onSurfaceVariant = BlueOnSurfaceVariantDark,
    outline = BlueOutlineDark,
    outlineVariant = BlueOutlineVariantDark,
    surfaceContainerLowest = BlueSurfaceContainerLowestDark,
    surfaceContainerLow = BlueSurfaceContainerLowDark,
    surfaceContainer = BlueSurfaceContainerDark,
    surfaceContainerHigh = BlueSurfaceContainerHighDark,
    surfaceContainerHighest = BlueSurfaceContainerHighestDark,
    surfaceBright = BlueSurfaceBrightDark,
    surfaceDim = BlueSurfaceDimDark,
    surfaceTint = BluePrimaryDark,
    inversePrimary = BlueInversePrimaryDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

private val LightColorScheme = lightColorScheme(
    primary = BluePrimaryLight,
    onPrimary = BlueOnPrimaryLight,
    primaryContainer = BluePrimaryContainerLight,
    onPrimaryContainer = BlueOnPrimaryContainerLight,
    secondary = BlueSecondaryLight,
    onSecondary = BlueOnSecondaryLight,
    secondaryContainer = BlueSecondaryContainerLight,
    onSecondaryContainer = BlueOnSecondaryContainerLight,
    tertiary = BlueTertiaryLight,
    onTertiary = BlueOnTertiaryLight,
    tertiaryContainer = BlueTertiaryContainerLight,
    onTertiaryContainer = BlueOnTertiaryContainerLight,
    background = BlueBackgroundLight,
    onBackground = BlueOnBackgroundLight,
    surface = BlueBackgroundLight,
    onSurface = BlueOnBackgroundLight,
    surfaceVariant = BlueSurfaceVariantLight,
    onSurfaceVariant = BlueOnSurfaceVariantLight,
    outline = BlueOutlineLight,
    outlineVariant = BlueOutlineVariantLight,
    surfaceContainerLowest = BlueSurfaceContainerLowestLight,
    surfaceContainerLow = BlueSurfaceContainerLowLight,
    surfaceContainer = BlueSurfaceContainerLight,
    surfaceContainerHigh = BlueSurfaceContainerHighLight,
    surfaceContainerHighest = BlueSurfaceContainerHighestLight,
    surfaceBright = BlueSurfaceBrightLight,
    surfaceDim = BlueSurfaceDimLight,
    surfaceTint = BluePrimaryLight,
    inversePrimary = BlueInversePrimaryLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

@Composable
fun ArtistPinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Off by default: on Android 12+ dynamic colour derives the scheme from the user's wallpaper,
     * which replaces the blue palette entirely. Pass true to opt back into Material You.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
