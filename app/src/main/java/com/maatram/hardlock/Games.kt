package com.maatram.hardlock

/**
 * The top 10 mobile games Maatram can lock in one tap. Locking works like any other picked app:
 * the Focus Shield sends a game's package to the home screen while a Hard Lock runs.
 * A few games ship under more than one package (regional or older builds), so each has a list.
 */
object Games {
    data class Game(val name: String, val packages: Set<String>)

    val TOP10: List<Game> = listOf(
        Game("BGMI", setOf("com.pubg.imobile", "com.tencent.ig")),
        Game("Free Fire MAX", setOf("com.dts.freefiremax", "com.dts.freefireth")),
        Game("Call of Duty Mobile", setOf("com.activision.callofduty.shooter")),
        Game("Minecraft", setOf("com.mojang.minecraftpe")),
        Game("Roblox", setOf("com.roblox.client")),
        Game("Clash of Clans", setOf("com.supercell.clashofclans")),
        Game("Clash Royale", setOf("com.supercell.clashroyale")),
        Game("Brawl Stars", setOf("com.supercell.brawlstars")),
        Game("Subway Surfers", setOf("com.kiloo.subwaysurf")),
        Game("Candy Crush", setOf("com.king.candycrushsaga")),
    )

    val ALL: Set<String> = TOP10.flatMap { it.packages }.toSet()
}
