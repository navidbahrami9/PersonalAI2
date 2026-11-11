package com.personalai.app

import java.security.SecureRandom

/** Short randomized Persian phrase to reduce simple replay of a fixed enrollment phrase. */
object RayanVoiceChallenge {
    private val random = SecureRandom()
    private val words = listOf(
        "باران", "کتاب", "پنجره", "دریا", "آفتاب",
        "سیب", "چراغ", "کوه", "سفر", "آبی",
        "بهار", "خانه", "جاده", "شیشه", "پرنده",
        "چای", "ستاره", "آینه", "باغ", "ابر",
        "قلم", "رودخانه", "صبح", "سپید", "درخت",
        "گل", "دشت", "ماهی", "ماه", "خورشید"
    )

    @Synchronized
    fun next(): String {
        val chosen = LinkedHashSet<String>()
        while (chosen.size < 3) chosen.add(words[random.nextInt(words.size)])
        return chosen.joinToString("، ")
    }
}
