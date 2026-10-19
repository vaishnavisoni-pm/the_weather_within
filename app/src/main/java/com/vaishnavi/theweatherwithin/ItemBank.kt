package com.vaishnavi.theweatherwithin

/**
 * THE FULL ITEM BANK — ported from item_bank_v2.json.
 *
 * Structure: 6 dimensions, each with exactly 3 facets, each facet with
 * exactly 4 daily-eligible items (verified in Python before this port).
 * Facet ORDER within each dimension matters: it's what "facet slot" in
 * the daily rotation refers to (index 0, 1, 2).
 */

data class BankItem(
    val itemId: String,
    val text: String,
    val reverse: Boolean
)

data class BankFacet(
    val key: String,
    val items: List<BankItem>   // always exactly 4
)

data class BankDimension(
    val key: String,
    val label: String,          // shown to the user, e.g. "Body"
    val facets: List<BankFacet> // always exactly 3, in fixed order
)

val itemBank: List<BankDimension> = listOf(

    BankDimension("physical", "Body", listOf(
        BankFacet("fatigue", listOf(
            BankItem("PH-F1", "I felt physically drained.", false),
            BankItem("PH-F2", "I had the energy I needed to get through the day.", true),
            BankItem("PH-F3", "Even small physical tasks felt like too much effort.", false),
            BankItem("PH-F4", "I ran out of steam long before the day was over.", false)
        )),
        BankFacet("recovery", listOf(
            BankItem("PH-R1", "I woke up still tired.", false),
            BankItem("PH-R2", "My sleep left me feeling restored.", true),
            BankItem("PH-R3", "I struggled to fall asleep or stay asleep.", false),
            BankItem("PH-R4", "Rest did not seem to help me recover.", false)
        )),
        BankFacet("somatic", listOf(
            BankItem("PH-S1", "I had physical symptoms like headaches, tension, or stomach trouble.", false),
            BankItem("PH-S2", "My body felt tense or achy for no clear reason.", false),
            BankItem("PH-S3", "My appetite was off — eating too much, too little, or not caring.", false),
            BankItem("PH-S4", "I felt physically well.", true)
        ))
    )),

    BankDimension("mental", "Mind", listOf(
        BankFacet("attention", listOf(
            BankItem("MN-A1", "I struggled to concentrate.", false),
            BankItem("MN-A2", "My thinking felt clear and sharp.", true),
            BankItem("MN-A3", "My mind kept wandering away from what I was doing.", false),
            BankItem("MN-A4", "I had to re-read or re-do things because I wasn't taking them in.", false)
        )),
        BankFacet("load", listOf(
            BankItem("MN-L1", "I forgot things I would normally remember.", false),
            BankItem("MN-L2", "Making even small decisions felt exhausting.", false),
            BankItem("MN-L3", "I felt mentally overloaded, like there was no room left.", false),
            BankItem("MN-L4", "I made careless mistakes.", false)
        )),
        BankFacet("detachment", listOf(
            BankItem("MN-D1", "I couldn't stop thinking about things I needed to do.", false),
            BankItem("MN-D2", "I was able to switch off and be present.", true),
            BankItem("MN-D3", "My mind kept replaying conversations or problems.", false),
            BankItem("MN-D4", "Even when resting, my head was still working.", false)
        ))
    )),

    BankDimension("emotional", "Mood", listOf(
        BankFacet("depletion", listOf(
            BankItem("EM-D1", "I felt emotionally drained.", false),
            BankItem("EM-D2", "I had nothing left to give anyone.", false),
            BankItem("EM-D3", "I felt emotionally steady and resourced.", true),
            BankItem("EM-D5", "Even small emotional demands felt like too much to handle today.", false)
        )),
        BankFacet("reactivity", listOf(
            BankItem("EM-R1", "I was irritable or short-tempered.", false),
            BankItem("EM-R2", "Small things upset me more than they should have.", false),
            BankItem("EM-R3", "I felt low or heavy for much of the day.", false),
            BankItem("EM-R4", "I felt close to tears, or actually cried.", false)
        )),
        BankFacet("blunting", listOf(
            BankItem("EM-B1", "I felt flat or numb, not really feeling much at all.", false),
            BankItem("EM-B2", "Things that normally move me left me cold.", false),
            BankItem("EM-B3", "I felt genuinely glad about something today.", true),
            BankItem("EM-B4", "I felt distant from my own feelings.", false)
        ))
    )),

    BankDimension("social", "People", listOf(
        BankFacet("strain", listOf(
            BankItem("SO-S1", "Being around people felt draining rather than restoring.", false),
            BankItem("SO-S2", "I dreaded having to interact with people.", false),
            BankItem("SO-S3", "I had to force myself to seem normal around others.", false),
            BankItem("SO-S4", "I enjoyed someone's company today.", true)
        )),
        BankFacet("connection", listOf(
            BankItem("SO-C1", "I felt lonely, even around other people.", false),
            BankItem("SO-C2", "I felt supported by the people around me.", true),
            BankItem("SO-C3", "I felt disconnected from people who matter to me.", false),
            BankItem("SO-C4", "There was someone I could have talked to honestly.", true)
        )),
        BankFacet("withdrawal", listOf(
            BankItem("SO-W1", "I wanted to be left alone, even by people I care about.", false),
            BankItem("SO-W2", "The thought of replying to messages felt like a burden.", false),
            BankItem("SO-W3", "I felt like withdrawing from everyone.", false),
            BankItem("SO-W5", "I looked forward to catching up with people today.", true)
        ))
    )),

    BankDimension("professional", "Work", listOf(
        BankFacet("work_exhaustion", listOf(
            BankItem("PR-E1", "Work left me completely used up.", false),
            BankItem("PR-E2", "I dreaded the thought of the next working day.", false),
            BankItem("PR-E3", "I had to push myself hard to do my work at all.", false),
            BankItem("PR-E4", "I finished work with energy still left for my own life.", true)
        )),
        BankFacet("cynicism", listOf(
            BankItem("PR-C1", "I felt detached from my work — just going through the motions.", false),
            BankItem("PR-C2", "I stopped caring about the outcome of what I was doing.", false),
            BankItem("PR-C3", "I felt cynical about whether any of it matters.", false),
            BankItem("PR-C4", "I felt genuinely engaged with my work.", true)
        )),
        BankFacet("efficacy", listOf(
            BankItem("PR-F1", "I felt effective at my work.", true),
            BankItem("PR-F2", "I felt confident in my ability to do my job.", true),
            BankItem("PR-F3", "I felt useless or ineffective at work.", false),
            BankItem("PR-F5", "I doubted my ability to handle what work threw at me today.", false)
        ))
    )),

    BankDimension("personal", "Self", listOf(
        BankFacet("meaning", listOf(
            BankItem("PE-M1", "I felt like I was just getting through the days, not living them.", false),
            BankItem("PE-M2", "What I did today felt meaningful to me.", true),
            BankItem("PE-M3", "I felt disconnected from the person I want to be.", false),
            BankItem("PE-M4", "I wondered what the point of it all is.", false)
        )),
        BankFacet("autonomy", listOf(
            BankItem("PE-A1", "My life felt like it belonged to other people's demands.", false),
            BankItem("PE-A2", "I felt guilty when I tried to rest.", false),
            BankItem("PE-A3", "I had some say over how my day went.", true),
            BankItem("PE-A5", "I felt trapped by my own schedule today.", false)
        )),
        BankFacet("vitality", listOf(
            BankItem("PE-V1", "I had no interest in things I usually enjoy.", false),
            BankItem("PE-V2", "I felt curious or interested in something.", true),
            BankItem("PE-V3", "Even the things I love felt like a chore.", false),
            BankItem("PE-V5", "I felt disconnected from the things that usually make me feel like myself.", false)
        ))
    ))
)

/**
 * DAILY ROTATION.
 *
 * dayIndex = number of check-ins already completed (dao.count()), NOT the
 * calendar date. Deliberate: if it were calendar-based, a skipped day
 * would permanently lose that day's facet slot. Tied to check-in count
 * instead, rotation only advances when the person actually engages.
 *
 * facetSlot cycles 0,1,2,0,1,2,... — which of a dimension's 3 facets gets
 * asked today. cycle increments every 3 check-ins — which specific item
 * within that facet's 4-item pool gets used.
 */
fun selectDailyQuestions(dayIndex: Int): List<Question> {
    val facetSlot = dayIndex % 3
    val cycle = dayIndex / 3

    return itemBank.map { dim ->
        val facet = dim.facets[facetSlot]
        val item = facet.items[cycle % facet.items.size]
        Question(
            itemId = item.itemId,
            dimension = dim.key,
            label = dim.label,
            facet = facet.key,
            text = item.text,
            reverse = item.reverse
        )
    }
}

/**
 * OPTIONAL DEEP-DIVE question set: facets 1 and 2 of every dimension
 * (facet 0 is already covered by the mandatory quick-start onboarding
 * step, which reuses selectDailyQuestions(0) — so nothing here repeats
 * a question already asked). 3 items per facet, 2 facets, 6 dimensions
 * = 36 questions.
 */
fun buildDeepDiveQuestions(): List<Question> {
    return itemBank.flatMap { dim ->
        dim.facets.drop(1).flatMap { facet ->   // skip facet index 0
            facet.items.take(3).map { item ->
                Question(item.itemId, dim.key, dim.label, facet.key, item.text, item.reverse)
            }
        }
    }
}
/**
 * "Cover everything" — one item per facet, all 18, for an ONGOING deep-dive
 * (not onboarding). Reuses facet slot 0's items by default; since these
 * facets already have real history post-onboarding, which specific item
 * gets asked matters less than covering all of them in one sitting.
 */
fun buildFullCheckInQuestions(): List<Question> {
    return itemBank.flatMap { dim ->
        dim.facets.map { facet ->
            val item = facet.items.first()
            Question(item.itemId, dim.key, dim.label, facet.key, item.text, item.reverse)
        }
    }
}

/**
 * "Focus on one area" — all 3 facets of a single dimension, one item each.
 */
fun buildDimensionFocusQuestions(dimensionKey: String): List<Question> {
    val dim = itemBank.first { it.key == dimensionKey }
    return dim.facets.map { facet ->
        val item = facet.items.first()
        Question(item.itemId, dim.key, dim.label, facet.key, item.text, item.reverse)
    }
}