package com.nuvio.tv.domain.model

enum class AppTheme(val displayName: String) {
    CUSTOM("Custom"),
    GOLD("Gold"),
    JADE("Jade"),
    ROSE_GOLD("Rose Gold"),
    ARCTIC_BLUE("Arctic Blue"),
    GRAPHITE("Graphite"),
    CRIMSON("Crimson"),
    OCEAN("Ocean"),
    VIOLET("Violet"),
    EMERALD("Emerald"),
    AMBER("Amber"),
    ROSE("Rose"),
    WHITE("White"),
    /** Nuvio Plus: the NowSeen look — deep navy, white highlight pills. */
    NOWSEEN("NowSeen"),
    /** Nuvio Plus themes, built on the NowSeen layout with their own colours. */
    TIVIMATE("TiviMate Classic"),
    PLEX_AMBER("Plex Amber"),
    CINEMA("Cinema"),
    GLASS("Apple TV Glass"),
    ALPINE("Alpine");

    /** Nuvio Plus: themes that use the NowSeen layout (guide header, list, banner). */
    val isNuvioPlus: Boolean get() = this == NOWSEEN || this == TIVIMATE || this == PLEX_AMBER || this == CINEMA || this == GLASS || this == ALPINE
}
