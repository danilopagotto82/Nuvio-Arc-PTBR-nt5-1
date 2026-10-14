package com.nuvio.tv.domain.model

enum class AppIconOption(val key: String, val entitlement: CosmeticEntitlement? = null) {
    ORIGINAL("original"),
    ARCTIC_BLUE("arctic_blue", CosmeticEntitlement.ARCTIC_BLUE_THEME),
    EMERALD("emerald"),
    ROSE_GOLD("rose_gold", CosmeticEntitlement.ROSE_GOLD_THEME),
    COPPER("copper"),
    GRAPHITE("graphite", CosmeticEntitlement.GRAPHITE_THEME),
    GOLD("gold", CosmeticEntitlement.GOLD_THEME),
    JADE("jade", CosmeticEntitlement.JADE_THEME),
    GLASS("glass"),
    CRIMSON("crimson"),
    OCEAN("ocean"),
    VIOLET("violet"),
    EMERALD_GREEN("emerald_green"),
    AMBER("amber"),
    ROSE("rose"),
    WHITE("white")
}

/** Supporter colours follow the matching supporter theme; an icon already in use stays listed. */
fun availableAppIcons(entitlements: CosmeticEntitlements, selected: AppIconOption): List<AppIconOption> =
    AppIconOption.entries.filter { it.entitlement == null || entitlements.includes(it.entitlement) || it == selected }
