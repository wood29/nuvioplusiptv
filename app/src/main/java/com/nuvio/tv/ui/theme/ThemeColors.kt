package com.nuvio.tv.ui.theme

import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors

data class ThemeColorPalette(
    val secondary: Color,
    val secondaryVariant: Color,
    val onSecondary: Color = NuvioPrimitives.white,
    val onSecondaryVariant: Color = NuvioPrimitives.white,
    val accentGradient: List<Color> = listOf(secondary),
    val focusRing: Color,
    val focusRingGradient: List<Color> = listOf(focusRing),
    val focusBackground: Color,
    val background: Color = NuvioPrimitives.neutral950,
    val backgroundElevated: Color = NuvioPrimitives.neutral900,
    val backgroundCard: Color = NuvioPrimitives.neutral825,
    val surface: Color = NuvioPrimitives.neutral875,
    val surfaceVariant: Color = NuvioPrimitives.neutral800,
    val panel: Color = NuvioPrimitives.neutral900,
    val overlay: Color = Color(0xD9000000),
    val field: Color = NuvioPrimitives.neutral850,
    val menu: Color = NuvioPrimitives.neutral875,
    val modal: Color = NuvioPrimitives.neutral900,
    val playerOverlay: Color = Color(0xCC000000)
)

object ThemeColors {
    val Crimson = ThemeColorPalette(
        secondary = NuvioPrimitives.red500,
        secondaryVariant = NuvioPrimitives.red600,
        focusRing = NuvioPrimitives.red300,
        focusBackground = Color(0xFF3D1A1A),
        backgroundCard = Color(0xFF241A1A)
    )

    val Ocean = ThemeColorPalette(
        secondary = NuvioPrimitives.blue500,
        secondaryVariant = NuvioPrimitives.blue700,
        focusRing = NuvioPrimitives.blue300,
        focusBackground = Color(0xFF1A2D3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1A1F24)
    )

    val Violet = ThemeColorPalette(
        secondary = NuvioPrimitives.violet500,
        secondaryVariant = NuvioPrimitives.violet700,
        focusRing = NuvioPrimitives.violet300,
        focusBackground = Color(0xFF2D1A3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1F1A24)
    )

    val Emerald = ThemeColorPalette(
        secondary = NuvioPrimitives.green500,
        secondaryVariant = NuvioPrimitives.green700,
        focusRing = NuvioPrimitives.green300,
        focusBackground = Color(0xFF1A3D1E),
        backgroundCard = Color(0xFF1A241A)
    )

    val Amber = ThemeColorPalette(
        secondary = NuvioPrimitives.amber500,
        secondaryVariant = NuvioPrimitives.amber700,
        focusRing = NuvioPrimitives.amber300,
        focusBackground = Color(0xFF3D2D1A),
        background = Color(0xFF0F0D0D),
        backgroundElevated = Color(0xFF1E1A1A),
        backgroundCard = Color(0xFF24201A)
    )

    val Rose = ThemeColorPalette(
        secondary = NuvioPrimitives.rose500,
        secondaryVariant = NuvioPrimitives.rose700,
        focusRing = NuvioPrimitives.rose300,
        focusBackground = Color(0xFF3D1A2D),
        backgroundCard = Color(0xFF241A1F)
    )

    val White = ThemeColorPalette(
        secondary = NuvioPrimitives.neutral100,
        secondaryVariant = NuvioPrimitives.neutral200,
        onSecondary = NuvioPrimitives.neutral925,
        onSecondaryVariant = NuvioPrimitives.neutral925,
        focusRing = NuvioPrimitives.white,
        focusBackground = Color(0xFF303030),
        backgroundCard = NuvioPrimitives.neutral850
    )

    /**
     * Nuvio Plus: the NowSeen look (deep navy, white focus ring). The accent is the NowSeen green, so
     * switches that are on, the Download button and other "active" states keep a colour; it was white,
     * which made on and off switches look the same.
     */
    val NowSeen = ThemeColorPalette(
        secondary = Color(0xFF7FD18B),
        secondaryVariant = Color(0xFF5DB86B),
        onSecondary = Color(0xFF0A0D12),
        onSecondaryVariant = Color(0xFF0A0D12),
        focusRing = Color(0xFFF2F4F8),
        focusBackground = Color(0xFF1B222D),
        background = Color(0xFF0A0D12),
        backgroundElevated = Color(0xFF10151D),
        backgroundCard = Color(0xFF121822),
        surface = Color(0xFF10151D),
        surfaceVariant = Color(0xFF1B222D),
        panel = Color(0xFF0C1016),
        field = Color(0xFF161C26),
        menu = Color(0xFF10151D),
        modal = Color(0xFF0C1016),
        playerOverlay = Color(0xD90A0D12)
    )

    /** Nuvio Plus: TiviMate's look: dark grey, a blue highlight. */
    val TiviMateClassic = ThemeColorPalette(
        secondary = Color(0xFF2F80ED),
        secondaryVariant = Color(0xFF1E63C4),
        onSecondary = Color.White,
        onSecondaryVariant = Color.White,
        focusRing = Color(0xFF6AA8FF),
        focusBackground = Color(0xFF1E2A3A),
        background = Color(0xFF121417),
        backgroundElevated = Color(0xFF1A1D21),
        backgroundCard = Color(0xFF1E2226),
        surface = Color(0xFF1A1D21),
        surfaceVariant = Color(0xFF262B31),
        panel = Color(0xFF16191C),
        field = Color(0xFF20242A),
        menu = Color(0xFF1A1D21),
        modal = Color(0xFF16191C),
        playerOverlay = Color(0xD9121417)
    )

    /** Nuvio Plus: Plex's warm look: charcoal, an amber highlight. */
    val PlexAmber = ThemeColorPalette(
        secondary = Color(0xFFE5A00D),
        secondaryVariant = Color(0xFFC2860A),
        onSecondary = Color(0xFF151515),
        onSecondaryVariant = Color(0xFF151515),
        focusRing = Color(0xFFF2F2F2),
        focusBackground = Color(0xFF2A2620),
        background = Color(0xFF121212),
        backgroundElevated = Color(0xFF1B1B1B),
        backgroundCard = Color(0xFF202020),
        surface = Color(0xFF1B1B1B),
        surfaceVariant = Color(0xFF2A2A2A),
        panel = Color(0xFF161616),
        field = Color(0xFF232323),
        menu = Color(0xFF1B1B1B),
        modal = Color(0xFF161616),
        playerOverlay = Color(0xD9121212)
    )

    /** Nuvio Plus: Cinema: pure black, a red highlight (most contrast for films). */
    val Cinema = ThemeColorPalette(
        secondary = Color(0xFFE50914),
        secondaryVariant = Color(0xFFB20710),
        onSecondary = Color.White,
        onSecondaryVariant = Color.White,
        focusRing = Color(0xFFF5F5F5),
        focusBackground = Color(0xFF2A1416),
        background = Color(0xFF000000),
        backgroundElevated = Color(0xFF0E0E0E),
        backgroundCard = Color(0xFF141414),
        surface = Color(0xFF0E0E0E),
        surfaceVariant = Color(0xFF1C1C1C),
        panel = Color(0xFF0A0A0A),
        field = Color(0xFF161616),
        menu = Color(0xFF0E0E0E),
        modal = Color(0xFF0A0A0A),
        playerOverlay = Color(0xD9000000)
    )

    /** Nuvio Plus: Apple TV Glass: light cool greys over the picture, white focus, Apple's blue highlight. */
    val Glass = ThemeColorPalette(
        secondary = Color(0xFF0A84FF),
        secondaryVariant = Color(0xFF0066CC),
        onSecondary = Color.White,
        onSecondaryVariant = Color.White,
        focusRing = Color(0xFFFFFFFF),
        focusBackground = Color(0xFF3A3F48),
        background = Color(0xFF16181D),
        backgroundElevated = Color(0xFF22252C),
        backgroundCard = Color(0xFF282C34),
        surface = Color(0xFF22252C),
        surfaceVariant = Color(0xFF30353E),
        panel = Color(0xE01C1F25),
        field = Color(0xFF2A2E36),
        menu = Color(0xE622252C),
        modal = Color(0xEB1C1F25),
        playerOverlay = Color(0xB316181D)
    )

    /** Nuvio Plus: Alpine: deep blue-green with a soft lake-blue highlight. */
    val Alpine = ThemeColorPalette(
        secondary = Color(0xFF6FC3DF),
        secondaryVariant = Color(0xFF4FA6C4),
        onSecondary = Color(0xFF08151A),
        onSecondaryVariant = Color(0xFF08151A),
        focusRing = Color(0xFFE8F4F6),
        focusBackground = Color(0xFF173238),
        background = Color(0xFF0B1416),
        backgroundElevated = Color(0xFF102022),
        backgroundCard = Color(0xFF132527),
        surface = Color(0xFF102022),
        surfaceVariant = Color(0xFF1A3033),
        panel = Color(0xFF0D1A1C),
        field = Color(0xFF142729),
        menu = Color(0xFF102022),
        modal = Color(0xFF0D1A1C),
        playerOverlay = Color(0xD90B1416)
    )

    fun getColorPalette(
        theme: AppTheme,
        customColors: CustomThemeColors = CustomThemeColors.Default
    ): ThemeColorPalette {
        return when (theme) {
            AppTheme.CUSTOM -> customColors.toColorPalette()
            AppTheme.GOLD -> SupporterThemeColors.Gold
            AppTheme.JADE -> SupporterThemeColors.Jade
            AppTheme.ROSE_GOLD -> SupporterThemeColors.RoseGold
            AppTheme.ARCTIC_BLUE -> SupporterThemeColors.ArcticBlue
            AppTheme.GRAPHITE -> SupporterThemeColors.Graphite
            AppTheme.CRIMSON -> Crimson
            AppTheme.OCEAN -> Ocean
            AppTheme.VIOLET -> Violet
            AppTheme.EMERALD -> Emerald
            AppTheme.AMBER -> Amber
            AppTheme.ROSE -> Rose
            AppTheme.WHITE -> White
            AppTheme.NOWSEEN -> NowSeen
            AppTheme.TIVIMATE -> TiviMateClassic
            AppTheme.PLEX_AMBER -> PlexAmber
            AppTheme.CINEMA -> Cinema
            AppTheme.GLASS -> Glass
            AppTheme.ALPINE -> Alpine
        }
    }
}
