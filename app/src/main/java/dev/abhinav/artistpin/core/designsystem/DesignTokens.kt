package dev.abhinav.artistpin.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tokens from the map-first redesign handoff.
 *
 * The handoff specifies one composition — dark detail pages, light chrome over the map — so every
 * colour here is a pair: the specified value, and its counterpart in the other mode. They are
 * composable getters rather than constants so a single call site serves both, and they sit
 * alongside the Material theme rather than inside it because these screens are laid out against
 * named surfaces (page, card, dock) that a ColorScheme has no slot for.
 */
object Pin {

    // ---- Detail screens (artist, show) --------------------------------------------------

    /** Page background. */
    val Page: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFF0D0E10), Color(0xFFF6F6F8))

    /** Card and ticket-stub surface. */
    val Card: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFF17181C), Color(0xFFFFFFFF))

    val OnPage: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFFFFFFFF), Color(0xFF17181C))
    val OnPageStrong: Color @Composable @ReadOnlyComposable get() = pick(Color(0xD9FFFFFF), Color(0xD9000000))
    val OnPageSecondary: Color @Composable @ReadOnlyComposable get() = pick(Color(0x8CFFFFFF), Color(0x8C000000))
    val OnPageTertiary: Color @Composable @ReadOnlyComposable get() = pick(Color(0x73FFFFFF), Color(0x73000000))
    val OnPageFaint: Color @Composable @ReadOnlyComposable get() = pick(Color(0x4DFFFFFF), Color(0x59000000))

    val Hairline: Color @Composable @ReadOnlyComposable get() = pick(Color(0x1AFFFFFF), Color(0x14000000))
    val HairlineSoft: Color @Composable @ReadOnlyComposable get() = pick(Color(0x14FFFFFF), Color(0x0F000000))
    val BorderSoft: Color @Composable @ReadOnlyComposable get() = pick(Color(0x24FFFFFF), Color(0x1F000000))
    val BorderStrong: Color @Composable @ReadOnlyComposable get() = pick(Color(0x38FFFFFF), Color(0x2E000000))

    /** Lineup card and other barely-there fills. */
    val Fill: Color @Composable @ReadOnlyComposable get() = pick(Color(0x0DFFFFFF), Color(0x0A000000))
    val TileFill: Color @Composable @ReadOnlyComposable get() = pick(Color(0x08FFFFFF), Color(0x05000000))
    val Chip: Color @Composable @ReadOnlyComposable get() = pick(Color(0x1FFFFFFF), Color(0x14000000))

    /** The venue thumbnail on the ticket stub. */
    val VenueTile: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFF20242A), Color(0xFFE4E7EC))

    /** Floating back button over the hero. */
    val BackCircle: Color @Composable @ReadOnlyComposable get() = pick(Color(0x800A0B0D), Color(0xB3FFFFFF))
    val OnBackCircle: Color @Composable @ReadOnlyComposable get() = pick(Color.White, Color(0xFF17181C))

    // ---- Chrome floating over the map ---------------------------------------------------

    /** The dock. Opaque, so map tiles never read through the navigation. */
    val Dock: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFF17181C), Color(0xFFFFFFFF))

    /** Pills and circles in the top row. */
    val Chrome: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFF1E2024), Color(0xFFFFFFFF))

    /** The Artists tab sheet. */
    val Sheet: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFF0D0E10), Color(0xFFFAFAFC))

    val OnChrome: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFFFFFFFF), Color(0xFF17181C))
    val OnChromeSecondary: Color @Composable @ReadOnlyComposable get() = pick(Color(0x99FFFFFF), Color(0x85000000))
    val OnChromeTertiary: Color @Composable @ReadOnlyComposable get() = pick(Color(0x80FFFFFF), Color(0x73000000))
    val HairlineChrome: Color @Composable @ReadOnlyComposable get() = pick(Color(0x1AFFFFFF), Color(0x12000000))
    val SelectedChrome: Color @Composable @ReadOnlyComposable get() = pick(Color(0x1FFFFFFF), Color(0x0F000000))

    /** Scrim behind the status bar, matched to the map style underneath it. */
    val StatusScrimTop: Color @Composable @ReadOnlyComposable get() = pick(Color(0xEB0D0E10), Color(0xEBFAFAFC))
    val StatusScrimBottom: Color @Composable @ReadOnlyComposable get() = pick(Color(0x000D0E10), Color(0x00FAFAFC))

    // ---- Fixed across both modes --------------------------------------------------------

    /** Pins, the dock's Add action, the newest timeline dot, the Add-photos pill. */
    val Accent = Color(0xFF14497A)
    val AccentGlow = Color(0x4714497A)
    val SpotifyGreen = Color(0xFF1DB954)
    val OnSpotify = Color(0xFF08110B)
    /**
     * Salmon reads as a warning on near-black; on white it reads as a tint. Light mode gets a
     * proper red so Delete looks like the thing you can't undo.
     */
    val Destructive: Color @Composable @ReadOnlyComposable get() = pick(Color(0xFFF08B7A), Color(0xFFB3261E))
    val DestructiveBorder: Color @Composable @ReadOnlyComposable get() = pick(Color(0x4DF08B7A), Color(0x59B3261E))

    // ---- Radii --------------------------------------------------------------------------

    val RadiusChip = 8.dp
    val RadiusThumb = 9.dp
    val RadiusTile = 12.dp
    val RadiusButton = 14.dp
    val RadiusCard = 16.dp
    val RadiusStub = 20.dp
    val RadiusDock = 26.dp
}

@Composable
@ReadOnlyComposable
private fun pick(dark: Color, light: Color): Color = if (isSystemInDarkTheme()) dark else light

/** The prototype's sheet/height easing: `cubic-bezier(.32,.72,0,1)` at 280ms. */
const val PIN_MOTION_MILLIS = 280
