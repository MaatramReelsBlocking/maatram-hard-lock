package com.maatram.hardlock

/**
 * The top 10 mobile games Maatram can lock in one tap. Locking works like any other picked app:
 * the Focus Shield sends a game's package to the home screen while a Hard Lock runs.
 * A few games ship under more than one package (regional or older builds), so each has a list.
 */
object Games {
    data class Game(val name: String, val logo: Int, val packages: Set<String>)

    val TOP10: List<Game> = listOf(
        Game("BGMI", R.drawable.game_bgmi, setOf("com.pubg.imobile", "com.tencent.ig")),
        Game("Free Fire MAX", R.drawable.game_free_fire_max, setOf("com.dts.freefiremax", "com.dts.freefireth")),
        Game("Call of Duty Mobile", R.drawable.game_call_of_duty_mobile, setOf("com.activision.callofduty.shooter")),
        Game("Minecraft", R.drawable.game_minecraft, setOf("com.mojang.minecraftpe")),
        Game("Roblox", R.drawable.game_roblox, setOf("com.roblox.client")),
        Game("Clash of Clans", R.drawable.game_clash_of_clans, setOf("com.supercell.clashofclans")),
        Game("Clash Royale", R.drawable.game_clash_royale, setOf("com.supercell.clashroyale")),
        Game("Brawl Stars", R.drawable.game_brawl_stars, setOf("com.supercell.brawlstars")),
        Game("Subway Surfers", R.drawable.game_subway_surfers, setOf("com.kiloo.subwaysurf")),
        Game("Candy Crush", R.drawable.game_candy_crush, setOf("com.king.candycrushsaga")),
    )

    val ALL: Set<String> = TOP10.flatMap { it.packages }.toSet()
}
