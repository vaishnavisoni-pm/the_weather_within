package com.vaishnavi.theweatherwithin

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot()
        }
    }
}

// ── Palette ──────────────────────────────────────────────────
private val Ink        = Color(0xFF2B2440)
private val Soft       = Color(0xFF6F6595)
private val Accent     = Color(0xFF6A5A9E)
private val AccentSoft = Color(0xFFECE4F8)
private val CardColor  = Color(0xF2FFFFFF)

private val SkyTop     = Color(0xFFA9C9DD)
private val SkyMid     = Color(0xFFDCC4A8)
private val SkyBottom  = Color(0xFFF0DCC4)

private val ClearColor    = Color(0xFF7EA9C9)
private val OvercastColor = Color(0xFFC99A6A)
private val StormColor    = Color(0xFFD6897F)
private val NeutralColor  = Color(0xFFDAD3E6)

private val UserName = "Vaishnavi"

// ── Data ─────────────────────────────────────────────────────
data class Question(
    val itemId: String,
    val dimension: String,
    val label: String,
    val facet: String,
    val text: String,
    val reverse: Boolean = false
)

private val dimensionOrder: List<Pair<String, String>> =
    itemBank.map { it.key to it.label }

data class Option(val weather: String, val meaning: String)

private val weatherWords = listOf("Clear", "Few clouds", "Overcast", "Rainy", "Stormy")
private val subtitlesNormal = listOf("not at all", "a little", "somewhat", "quite a bit", "completely")

/**
 * Builds this question's 5 options. The weather word always stays in the
 * same visual position (Clear top, Stormy bottom) — only the subtitle
 * explanation flips for positively-worded (reverse) questions, so
 * "Clear" always means good, regardless of how the sentence is phrased.
 * Tapping Clear (position 0) always produces load=0, Stormy (position 4)
 * always produces load=100 — itemLoad() no longer needs a reverse flag,
 * the flip already happened here, before the tap is even recorded.
 */
fun optionsFor(reverse: Boolean): List<Option> {
    val subtitles = if (reverse) subtitlesNormal.reversed() else subtitlesNormal
    return weatherWords.zip(subtitles) { weather, meaning -> Option(weather, meaning) }
}

fun itemLoad(answer: Int): Int = answer * 25

fun loadWeatherWord(load: Int): String = when {
    load < 0  -> "—"
    load < 34 -> "Clear"
    load < 67 -> "Overcast"
    else      -> "Stormy"
}

fun loadStateColor(load: Int): Color = when {
    load < 0  -> NeutralColor
    load < 34 -> ClearColor
    load < 67 -> OvercastColor
    else      -> StormColor
}

fun loadWeatherIcon(load: Int): ImageVector = when {
    load < 0  -> Icons.Filled.Cloud
    load < 34 -> Icons.Filled.WbSunny
    load < 67 -> Icons.Filled.Cloud
    load < SAFETY_ACUTE_LEVEL -> Icons.Filled.Umbrella
    else      -> Icons.Filled.Thunderstorm
}

fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else      -> "Good evening"
    }
}

fun todayDateKey(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

fun formatMonthLabel(monthKey: String): String {
    return try {
        val parser = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val formatter = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        formatter.format(parser.parse(monthKey)!!)
    } catch (e: Exception) {
        monthKey
    }
}

// ── Scoring engine ───────────────────────────────────────────
private const val ALPHA = 0.25

fun computeDimensionScores(estimates: List<FacetEstimateEntity>): Map<String, Int> {
    val dims = itemBank.map { it.key }
    return dims.associateWith { dim ->
        val vals = estimates.filter { it.dimension == dim }.map { it.estimate }
        if (vals.isEmpty()) -1 else Math.round(vals.average()).toInt()
    }
}

fun computeOverall(dimensionScores: Map<String, Int>): Int {
    val known = dimensionScores.values.filter { it >= 0 }
    if (known.isEmpty()) return -1
    val mean = known.average()
    val max = known.max().toDouble()
    return Math.round(0.7 * mean + 0.3 * max).toInt()
}

// ── Confidence ───────────────────────────────────────────────
private const val SUFFICIENCY_CHECKINS = 14.0
private const val STALE_AFTER_DAYS = 6L

fun computeConfidence(
    estimates: List<FacetEstimateEntity>,
    dailyCheckInCount: Int,
    now: Long
): Double {
    val sufficiency = (dailyCheckInCount / SUFFICIENCY_CHECKINS).coerceIn(0.0, 1.0)

    val totalFacets = itemBank.sumOf { it.facets.size }
    val staleAfterMillis = STALE_AFTER_DAYS * 24 * 60 * 60 * 1000
    val freshCount = estimates.count { (now - it.lastUpdated) <= staleAfterMillis }
    val freshness = if (totalFacets > 0) freshCount.toDouble() / totalFacets else 0.0

    return 0.5 * sufficiency + 0.5 * freshness
}

// ── Insights ─────────────────────────────────────────────────
data class Insight(
    val priority: Int,
    val kind: String,
    val text: String,
    val dimension: String? = null,
    val dimension2: String? = null
)

private const val TREND_WINDOW = 7
private const val TREND_DELTA = 6
private const val HIGH_DIM_THRESHOLD = 50
private const val STREAK_DAYS = 5
private const val RISER_DELTA = 8
private const val SAFETY_SUSTAINED_DAYS = 10
private const val SAFETY_SUSTAINED_LEVEL = 67
private const val SAFETY_ACUTE_LEVEL = 80
private const val SAFETY_ACUTE_DAYS = 4
private const val LOW_CONFIDENCE = 0.35

private val dimensionActions = mapOf(
    "physical"     to "protect a wind-down window before bed tonight",
    "mental"       to "try one single-task stretch — close the other tabs",
    "emotional"    to "give the feeling a name, out loud or on paper",
    "social"       to "a low-effort message to one person you trust can help",
    "professional" to "guard one small boundary tomorrow — even a no-meetings slot",
    "personal"     to "carve out fifteen minutes that are only yours"
)

private fun trailingStreak(values: List<Int>, threshold: Int): Int {
    var n = 0
    for (v in values.asReversed()) {
        if (v >= threshold) n++ else break
    }
    return n
}

private fun recentVsPrior(values: List<Int>, window: Int = TREND_WINDOW): Pair<Double, Double>? {
    if (values.size < 2) return null
    val recent = values.takeLast(window).average()
    val prior = if (values.size >= window * 2) {
        val start = values.size - window * 2
        val end = values.size - window
        values.subList(start, end).average()
    } else {
        val earlier = if (values.size > window) values.dropLast(window) else values.take(1)
        earlier.average()
    }
    return recent to prior
}

suspend fun computeInsights(context: Context): Pair<List<Insight>, String?> {
    val dao = AppDatabase.get(context).checkInDao()

    val overallHistory = dao.getDailyCheckInsAscending().map { it.overallLoad }
    val dailyCount = dao.countDaily()
    val estimates = dao.getAllFacetEstimates()
    val confidence = computeConfidence(estimates, dailyCount, System.currentTimeMillis())

    val allSnapshots = dao.getAllSnapshotsDescending()
    val byDimension: Map<String, List<Int>> = itemBank.associate { dim ->
        val history = allSnapshots
            .filter { it.dimension == dim.key }
            .sortedBy { it.dateKey }
            .map { it.score }
        dim.key to history
    }

    val currentDimScores = computeDimensionScores(estimates)
    val found = mutableListOf<Insight>()

    run {
        val sustained = trailingStreak(overallHistory, SAFETY_SUSTAINED_LEVEL)
        val acute = trailingStreak(overallHistory, SAFETY_ACUTE_LEVEL)
        if (sustained >= SAFETY_SUSTAINED_DAYS || acute >= SAFETY_ACUTE_DAYS) {
            found.add(
                Insight(
                    0, "safety",
                    "Your load has stayed high for a while now. This app tracks " +
                            "patterns, not diagnoses — but if it's felt this heavy for a " +
                            "stretch, talking to a doctor or someone you trust can " +
                            "genuinely help."
                )
            )
        }
    }

    val thin = confidence < LOW_CONFIDENCE
    if (thin) {
        found.add(
            Insight(
                1, "learning",
                "Still getting to know your rhythm here — the more you check in, " +
                        "the sharper these will get."
            )
        )
    }

    recentVsPrior(overallHistory)?.let { (recent, prior) ->
        val delta = recent - prior
        if (delta > TREND_DELTA) {
            found.add(
                Insight(
                    2, "trend_up",
                    "Your overall load has been climbing over the past week " +
                            "(up about ${Math.round(delta)} points)."
                )
            )
        } else if (delta < -TREND_DELTA) {
            found.add(
                Insight(
                    6, "trend_down",
                    "Your overall load has eased over the past week " +
                            "(down about ${Math.round(-delta)} points) — worth noticing what helped."
                )
            )
        }
    }

    var bestStreak: Pair<String, Int>? = null
    for ((dim, history) in byDimension) {
        val streak = trailingStreak(history, HIGH_DIM_THRESHOLD)
        if (streak >= STREAK_DAYS && (bestStreak == null || streak > bestStreak!!.second)) {
            bestStreak = dim to streak
        }
    }
    bestStreak?.let { (dim, streak) ->
        val label = itemBank.first { it.key == dim }.label
        found.add(
            Insight(
                3, "streak",
                "$label has been your heaviest area — elevated for $streak days running.",
                dim
            )
        )
    }

    val risers = mutableListOf<Pair<String, Double>>()
    for ((dim, history) in byDimension) {
        recentVsPrior(history)?.let { (recent, prior) ->
            val delta = recent - prior
            if (delta > RISER_DELTA) risers.add(dim to delta)
        }
    }
    risers.maxByOrNull { it.second }?.let { (dim, delta) ->
        val label = itemBank.first { it.key == dim }.label
        found.add(
            Insight(
                4, "riser",
                "$label has been climbing lately (up about ${Math.round(delta)} points).",
                dim
            )
        )
    }

    if (risers.size >= 2) {
        val sorted = risers.sortedByDescending { it.second }
        val dimA = sorted[0].first
        val dimB = sorted[1].first
        val labelA = itemBank.first { it.key == dimA }.label
        val labelB = itemBank.first { it.key == dimB }.label
        found.add(
            Insight(
                5, "comovement",
                "$labelA and $labelB have been rising together. They often move as " +
                        "a pair — it can be worth noticing what they share for you.",
                dimA, dimB
            )
        )
    }

    val improvers = mutableListOf<Pair<String, Double>>()
    for ((dim, history) in byDimension) {
        recentVsPrior(history)?.let { (recent, prior) ->
            val delta = recent - prior
            if (delta < -RISER_DELTA) improvers.add(dim to delta)
        }
    }
    improvers.minByOrNull { it.second }?.let { (dim, delta) ->
        val label = itemBank.first { it.key == dim }.label
        found.add(
            Insight(
                7, "improvement",
                "$label has eased off recently (down about ${Math.round(-delta)} points). Good to see.",
                dim
            )
        )
    }

    currentDimScores.filter { it.value >= 0 }.maxByOrNull { it.value }?.let { (dim, score) ->
        if (score >= HIGH_DIM_THRESHOLD) {
            val label = itemBank.first { it.key == dim }.label
            found.add(Insight(8, "dominant", "$label is currently your heaviest area.", dim))
        }
    }

    val safety = found.filter { it.kind == "safety" }
    val learning = found.filter { it.kind == "learning" }
    val others = found.filter { it.kind != "safety" && it.kind != "learning" }
        .sortedBy { it.priority }

    val selected = mutableListOf<Insight>()
    selected.addAll(safety)

    if (learning.isNotEmpty() && dailyCount < TREND_WINDOW) {
        return (selected + learning.take(1)) to null
    }

    val used = mutableSetOf<String>()
    val deduped = mutableListOf<Insight>()
    for (insight in others) {
        val dims = listOfNotNull(insight.dimension, insight.dimension2).toSet()
        if (dims.isNotEmpty() && dims.all { it in used }) continue
        deduped.add(insight)
        used.addAll(dims)
    }
    selected.addAll(deduped)

    val actionInsight = selected.firstOrNull {
        it.kind !in setOf("safety", "learning", "improvement") && it.dimension != null
    }
    val suggestion = actionInsight?.dimension?.let { dim ->
        dimensionActions[dim]?.let { "One small thing you could try: $it." }
    }

    return selected to suggestion
}

// ── Journal extraction: STUB (keyword-based; real model swap-in TBD) ─
data class ExtractedTag(
    val dimension: String,
    val facet: String,
    val evidence: String,
    val suggestedLoad: Int,
    val confidence: Double
)

private data class KeywordRule(
    val keyword: String,
    val dimension: String,
    val facet: String,
    val load: Int
)

private val stubKeywordRules = listOf(
    KeywordRule("exhausted", "physical", "fatigue", 85),
    KeywordRule("drained", "physical", "fatigue", 80),
    KeywordRule("couldn't sleep", "physical", "recovery", 80),
    KeywordRule("didn't sleep", "physical", "recovery", 80),
    KeywordRule("headache", "physical", "somatic", 65),
    KeywordRule("couldn't focus", "mental", "attention", 80),
    KeywordRule("overwhelmed", "mental", "load", 85),
    KeywordRule("too much", "mental", "load", 70),
    KeywordRule("kept thinking about work", "mental", "detachment", 75),
    KeywordRule("nothing left to give", "emotional", "depletion", 85),
    KeywordRule("emotionally drained", "emotional", "depletion", 85),
    KeywordRule("snapped at", "emotional", "reactivity", 75),
    KeywordRule("irritable", "emotional", "reactivity", 70),
    KeywordRule("felt numb", "emotional", "blunting", 75),
    KeywordRule("didn't feel like talking", "social", "strain", 70),
    KeywordRule("avoided people", "social", "strain", 75),
    KeywordRule("felt lonely", "social", "connection", 80),
    KeywordRule("wanted to be alone", "social", "withdrawal", 70),
    KeywordRule("dreading work", "professional", "work_exhaustion", 85),
    KeywordRule("work drained me", "professional", "work_exhaustion", 85),
    KeywordRule("don't care anymore", "professional", "cynicism", 80),
    KeywordRule("pointless", "personal", "meaning", 80),
    KeywordRule("no interest", "personal", "vitality", 75),
    KeywordRule("felt trapped", "personal", "autonomy", 75),
    KeywordRule("slept well", "physical", "recovery", 15),
    KeywordRule("felt rested", "physical", "recovery", 15),
    KeywordRule("full of energy", "physical", "fatigue", 10),
    KeywordRule("focused well", "mental", "attention", 15),
    KeywordRule("clear headed", "mental", "attention", 15),
    KeywordRule("felt supported", "social", "connection", 15),
    KeywordRule("great time with", "social", "connection", 15),
    KeywordRule("enjoyed my work", "professional", "cynicism", 15),
    KeywordRule("felt meaningful", "personal", "meaning", 15),
    KeywordRule("excited about", "personal", "vitality", 15)
)

fun extractTagsStub(text: String): List<ExtractedTag> {
    val lower = text.lowercase()
    val found = mutableListOf<ExtractedTag>()
    val usedFacets = mutableSetOf<Pair<String, String>>()

    for (rule in stubKeywordRules) {
        val key = rule.dimension to rule.facet
        if (key in usedFacets) continue
        val idx = lower.indexOf(rule.keyword)
        if (idx >= 0) {
            val start = maxOf(0, idx - 15)
            val end = minOf(text.length, idx + rule.keyword.length + 15)
            var snippet = text.substring(start, end).trim()
            if (start > 0) snippet = "…$snippet"
            if (end < text.length) snippet = "$snippet…"

            found.add(
                ExtractedTag(
                    dimension = rule.dimension,
                    facet = rule.facet,
                    evidence = snippet,
                    suggestedLoad = rule.load,
                    confidence = 0.78
                )
            )
            usedFacets.add(key)
        }
    }

    return found
}

// ── Saving: daily check-in (EWMA blend) ──────────────────────
suspend fun saveCheckIn(
    context: Context,
    todaysQuestions: List<Question>,
    answers: List<Int>
): Int {
    val dao = AppDatabase.get(context).checkInDao()
    val now = System.currentTimeMillis()
    val dateKey = todayDateKey()

    val answerRows = answers.mapIndexed { i, raw ->
        val q = todaysQuestions[i]
        AnswerEntity(
            checkInId = 0,
            itemId = q.itemId,
            dimension = q.dimension,
            facet = q.facet,
            rawAnswer = raw,
            reverse = q.reverse,
            load = itemLoad(raw)
        )
    }

    val currentEstimates = dao.getAllFacetEstimates()
        .associateBy { it.dimension to it.facet }

    val updatedFacets = answerRows.map { row ->
        val key = row.dimension to row.facet
        val prev = currentEstimates[key]?.estimate
        val newEstimate = if (prev == null) {
            row.load.toDouble()
        } else {
            ALPHA * row.load + (1 - ALPHA) * prev
        }
        FacetEstimateEntity(row.dimension, row.facet, newEstimate, now)
    }

    val merged = currentEstimates.toMutableMap()
    updatedFacets.forEach { merged[it.dimension to it.facet] = it }

    val dimensionScores = computeDimensionScores(merged.values.toList())
    val overall = computeOverall(dimensionScores).let { if (it < 0) 0 else it }

    val snapshots = dimensionScores
        .filter { it.value >= 0 }
        .map { (dim, score) -> DimensionSnapshotEntity(dateKey, dim, score, now) }

    dao.saveCheckInWithFacets(
        CheckInEntity(
            timestamp = now,
            dateKey = dateKey,
            kind = "daily",
            overallLoad = overall,
            flaggedCareless = false
        ),
        answerRows,
        updatedFacets,
        snapshots
    )

    return dao.count()
}

// ── Saving: onboarding (DIRECT AVERAGE per facet, not EWMA) ──
suspend fun saveAnchor(
    context: Context,
    questions: List<Question>,
    answers: List<Int>
): Int {
    val dao = AppDatabase.get(context).checkInDao()
    val now = System.currentTimeMillis()
    val dateKey = todayDateKey()

    val answerRows = answers.mapIndexed { i, raw ->
        val q = questions[i]
        AnswerEntity(
            checkInId = 0,
            itemId = q.itemId,
            dimension = q.dimension,
            facet = q.facet,
            rawAnswer = raw,
            reverse = q.reverse,
            load = itemLoad(raw)
        )
    }

    val seededFacets = answerRows
        .groupBy { it.dimension to it.facet }
        .map { (key, rows) ->
            FacetEstimateEntity(
                dimension = key.first,
                facet = key.second,
                estimate = rows.map { it.load }.average(),
                lastUpdated = now
            )
        }

    val dimensionScores = computeDimensionScores(seededFacets)
    val overall = computeOverall(dimensionScores).let { if (it < 0) 0 else it }

    val snapshots = dimensionScores
        .filter { it.value >= 0 }
        .map { (dim, score) -> DimensionSnapshotEntity(dateKey, dim, score, now) }

    dao.saveCheckInWithFacets(
        CheckInEntity(
            timestamp = now,
            dateKey = dateKey,
            kind = "anchor",
            overallLoad = overall,
            flaggedCareless = false
        ),
        answerRows,
        seededFacets,
        snapshots
    )

    return dao.count()
}

// ── Sky ──────────────────────────────────────────────────────
@Composable
fun SkyBackground(weatherLoad: Int = -1) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(SkyTop, SkyMid, SkyBottom),
                    startY = 0f, endY = 2200f
                )
            )
    ) {
        if (weatherLoad >= 0) {
            Icon(
                imageVector = loadWeatherIcon(weatherLoad),
                contentDescription = null,
                tint = loadStateColor(weatherLoad).copy(alpha = 0.6f),
                modifier = Modifier
                    .size(280.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 60.dp, y = 20.dp)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize().blur(40.dp)) {
                drawCircle(
                    Color(0x55FFF3E0),
                    size.width * 0.34f,
                    Offset(size.width * 0.80f, size.height * 0.12f)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x00FAF7F2), Color(0x55FAF7F2),
                            Color(0xE0FAF7F2), Color(0xFFFAF7F2)
                        )
                    )
                )
        )
    }
}

// ── Navigation ───────────────────────────────────────────────
@Composable
fun AppRoot() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf("loading") }

    LaunchedEffect(Unit) {
        val count = AppDatabase.get(context).checkInDao().count()
        screen = if (count == 0) "onboarding" else "home"
    }

    when (screen) {
        "loading"    -> Box(modifier = Modifier.fillMaxSize()) { SkyBackground() }
        "onboarding" -> OnboardingScreen(onDone = { screen = "home" })
        "home"       -> HomeScreen(
            onCheckIn = { screen = "checkin" },
            onSeeDetails = { screen = "details" },
            onReflect = { screen = "reflect" },
            onJournal = { screen = "journal" },
            onModelSetup = { screen = "modelsetup" }
        )
        "checkin"    -> CheckInScreen(onGoHome = { screen = "home" })
        "details"    -> DetailsScreen(
            onBack = { screen = "home" },
            onSeeMonthly = { screen = "monthly" }
        )
        "reflect"    -> ReflectScreen(onBack = { screen = "home" })
        "monthly"    -> MonthlyTrendsScreen(onBack = { screen = "details" })
        "journal"    -> JournalFlow(onFinished = { screen = "home" })
        "modelsetup" -> ModelSetupScreen(onBack = { screen = "home" })
    }
}

// ── Home ─────────────────────────────────────────────────────
@Composable
fun HomeScreen(
    onCheckIn: () -> Unit,
    onSeeDetails: () -> Unit,
    onReflect: () -> Unit,
    onJournal: () -> Unit,
    onModelSetup: () -> Unit
) {
    val context = LocalContext.current
    var dimScores by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var overall by remember { mutableStateOf(-1) }
    var alreadyCheckedInToday by remember { mutableStateOf(false) }
    var recentHistory by remember { mutableStateOf<List<Int>>(emptyList()) }
    var confidence by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        val dao = AppDatabase.get(context).checkInDao()
        val estimates = dao.getAllFacetEstimates()
        dimScores = computeDimensionScores(estimates)
        overall = computeOverall(dimScores)
        alreadyCheckedInToday = dao.countForDate(todayDateKey()) > 0
        recentHistory = dao.getDailyCheckInsAscending().takeLast(14).map { it.overallLoad }

        val dailyCount = dao.countDaily()
        confidence = computeConfidence(estimates, dailyCount, System.currentTimeMillis())
    }

    val band = loadWeatherWord(overall)

    val insightLine = when {
        overall < 0       -> "Log your first check-in to start seeing your weather."
        confidence < 0.35  -> "Still getting to know your rhythm — a few more check-ins and this will sharpen."
        confidence < 0.65  -> when {
            overall < 34 -> "So far, so calm — still building a fuller picture."
            overall < 67 -> "A few clouds showing, though it's early days for this reading."
            else         -> "Looking heavy so far, but this is still a rough picture — keep checking in."
        }
        else -> when {
            overall < 34 -> "Skies are calm. Nothing urgent standing out today."
            overall < 67 -> "A few clouds gathering — worth keeping an eye on."
            else         -> "It's been a heavy stretch. Be gentle with yourself tonight."
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground(weatherLoad = overall)

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(40.dp))

            Text(greeting().uppercase(), color = Soft,
                fontSize = 12.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            Text("${greeting()}, $UserName", color = Ink,
                fontSize = 25.sp, fontWeight = FontWeight.Medium)

            Spacer(Modifier.height(20.dp))

            Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                WeatherRing(overall = overall, band = band, dimLoads = dimScores)
            }

            Spacer(Modifier.height(20.dp))

            if (recentHistory.size >= 2) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Text("RECENT TREND", color = Soft,
                        fontSize = 11.sp, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(8.dp))
                    TrendLineChart(recentHistory, height = 72.dp)
                }
                Spacer(Modifier.height(14.dp))
            } else {
                Text(
                    "A couple more check-ins and your trend will start to show here.",
                    color = Soft, fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(14.dp))
            }

            Text(insightLine, color = Soft, fontSize = 14.sp, lineHeight = 20.sp)

            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.clickable { onSeeDetails() }) {
                Text("See details →", color = Accent,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Box(modifier = Modifier.clickable { onReflect() }) {
                Text("Read your reflection →", color = Accent,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Box(modifier = Modifier.clickable { onJournal() }) {
                Text("Write about your day →", color = Accent,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Box(modifier = Modifier.clickable { onModelSetup() }) {
                Text("Set up on-device AI →", color = Accent,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.weight(1f))

            if (alreadyCheckedInToday) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor, RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x336A5A9E), RoundedCornerShape(16.dp))
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓ You've checked in for today — see you tomorrow",
                        color = Soft, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Accent, RoundedCornerShape(16.dp))
                        .clickable { onCheckIn() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("How are you tonight?", color = Color.White,
                        fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun WeatherRing(overall: Int, band: String, dimLoads: Map<String, Int>) {
    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val outerR = size.minDimension / 2f
            val innerR = outerR * 0.62f
            val gap = 5f
            val sweep = 60f - gap

            dimensionOrder.forEachIndexed { i, (dim, _) ->
                val load = dimLoads[dim] ?: -1
                val start = -90f + i * 60f + gap / 2f
                drawArc(
                    color = loadStateColor(load),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = Offset(cx - outerR, cy - outerR),
                    size = Size(outerR * 2, outerR * 2)
                )
            }
            drawCircle(color = CardColor, radius = innerR, center = Offset(cx, cy))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (overall < 0) "—" else "$overall",
                color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Medium
            )
            Text(band.uppercase(), color = Soft, fontSize = 11.sp, letterSpacing = 1.5.sp)
        }
    }
}

// ── Details (per-dimension bars, + link to monthly view) ─────
@Composable
fun DetailsScreen(onBack: () -> Unit, onSeeMonthly: () -> Unit) {
    val context = LocalContext.current
    var dimScores by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        dimScores = computeDimensionScores(AppDatabase.get(context).checkInDao().getAllFacetEstimates())
        loaded = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))

            Box(modifier = Modifier.clickable { onBack() }.padding(vertical = 4.dp)) {
                Text("← Home", color = Soft, fontSize = 14.sp)
            }

            Spacer(Modifier.height(14.dp))
            Text("Your six,\nin detail", color = Ink, fontSize = 28.sp,
                fontWeight = FontWeight.Medium, lineHeight = 36.sp)

            Spacer(Modifier.height(22.dp))

            if (loaded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    dimensionOrder.forEachIndexed { i, (dim, label) ->
                        val load = dimScores[dim] ?: -1
                        DimensionBarRow(label, load)
                        if (i < dimensionOrder.size - 1) Spacer(Modifier.height(12.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Box(modifier = Modifier.clickable { onSeeMonthly() }) {
                Text("See monthly view →", color = Accent,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Monthly trends (aggregated from dimension_snapshots) ──────
@Composable
fun MonthlyTrendsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var monthly by remember { mutableStateOf<List<MonthlyDimensionAverage>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        monthly = AppDatabase.get(context).checkInDao().getMonthlyDimensionAverages()
        loaded = true
    }

    val months = monthly.map { it.month }.distinct()

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))

            Box(modifier = Modifier.clickable { onBack() }.padding(vertical = 4.dp)) {
                Text("← Back", color = Soft, fontSize = 14.sp)
            }

            Spacer(Modifier.height(14.dp))
            Text("By month", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Medium)

            Spacer(Modifier.height(22.dp))

            if (loaded && months.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor, RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Text(
                        "Once you've been checking in a little longer, monthly " +
                                "patterns will start to show here.",
                        color = Soft, fontSize = 14.sp, lineHeight = 20.sp
                    )
                }
            }

            months.forEach { month ->
                val scoresForMonth = monthly
                    .filter { it.month == month }
                    .associate { it.dimension to Math.round(it.avgScore).toInt() }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(formatMonthLabel(month), color = Ink,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    dimensionOrder.forEachIndexed { i, (dim, label) ->
                        val score = scoresForMonth[dim] ?: -1
                        DimensionBarRow(label, score)
                        if (i < dimensionOrder.size - 1) Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

// ── Reflect (deterministic insights, on demand) ───────────────
@Composable
fun ReflectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var insights by remember { mutableStateOf<List<Insight>>(emptyList()) }
    var suggestion by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (ins, sugg) = computeInsights(context)
        insights = ins
        suggestion = sugg
        loaded = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))

            Box(modifier = Modifier.clickable { onBack() }.padding(vertical = 4.dp)) {
                Text("← Home", color = Soft, fontSize = 14.sp)
            }

            Spacer(Modifier.height(14.dp))
            Text("What it's\ntelling you", color = Ink, fontSize = 28.sp,
                fontWeight = FontWeight.Medium, lineHeight = 36.sp)

            Spacer(Modifier.height(22.dp))

            if (!loaded) return@Column

            if (insights.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor, RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Text(
                        "Nothing in particular stands out yet — keep checking in and " +
                                "this will start to fill up with real patterns.",
                        color = Soft, fontSize = 14.sp, lineHeight = 20.sp
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    insights.forEachIndexed { i, insight ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("•  ", color = Accent, fontSize = 14.sp)
                            Text(insight.text, color = Ink, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                        if (i < insights.size - 1) Spacer(Modifier.height(4.dp))
                    }
                }

                suggestion?.let { text ->
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AccentSoft, RoundedCornerShape(14.dp))
                            .padding(16.dp)
                    ) {
                        Text(text, color = Accent, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun TrendLineChart(values: List<Int>, height: Dp = 120.dp) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val w = size.width
        val h = size.height

        drawRect(color = StormColor.copy(alpha = 0.08f),
            topLeft = Offset(0f, 0f), size = Size(w, h / 3f))
        drawRect(color = OvercastColor.copy(alpha = 0.08f),
            topLeft = Offset(0f, h / 3f), size = Size(w, h / 3f))
        drawRect(color = ClearColor.copy(alpha = 0.08f),
            topLeft = Offset(0f, h * 2f / 3f), size = Size(w, h / 3f))

        val n = values.size
        val points = values.mapIndexed { i, v ->
            val x = if (n == 1) w / 2f else (i.toFloat() / (n - 1)) * w
            val y = h - (v.coerceIn(0, 100) / 100f) * h
            Offset(x, y)
        }

        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }

        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }
        drawPath(fillPath, color = Accent.copy(alpha = 0.12f))

        drawPath(
            linePath,
            color = Accent,
            style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        points.forEachIndexed { i, p ->
            val isLast = (i == points.size - 1)
            if (isLast) {
                drawCircle(color = loadStateColor(values[i]), radius = 8f, center = p)
                drawCircle(color = Color.White, radius = 3f, center = p)
            } else {
                drawCircle(color = Color.White, radius = 5f, center = p)
                drawCircle(color = loadStateColor(values[i]), radius = 3.5f, center = p)
            }
        }
    }
}

@Composable
fun DimensionBarRow(label: String, load: Int) {
    val color = loadStateColor(load)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = loadWeatherIcon(load),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.width(56.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(Color(0x1A000000), RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (load < 0) 0f else (load / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(4.dp))
            )
        }

        Spacer(Modifier.width(10.dp))
        Text(
            if (load < 0) "—" else "$load",
            color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(28.dp)
        )
    }
}

// ── Daily check-in flow ──────────────────────────────────────
@Composable
fun CheckInScreen(onGoHome: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf("core") }

    var todaysQuestions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    var currentIndex by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(-1) }
    var savedCount by remember { mutableStateOf(-1) }

    val answers = remember { mutableStateListOf<Int>() }

    LaunchedEffect(Unit) {
        val dayIndex = AppDatabase.get(context).checkInDao().countDaily()
        todaysQuestions = selectDailyQuestions(dayIndex)
        loaded = true
    }

    LaunchedEffect(selected) {
        if (selected >= 0 && loaded) {
            delay(300)
            if (currentIndex < answers.size) {
                answers[currentIndex] = selected
            } else {
                answers.add(selected)
            }
            if (currentIndex < todaysQuestions.size - 1) {
                currentIndex++
                selected = if (currentIndex < answers.size) answers[currentIndex] else -1
            } else {
                scope.launch {
                    savedCount = saveCheckIn(context, todaysQuestions, answers.toList())
                    stage = "offer"
                }
            }
        }
    }

    when {
        stage == "offer" -> {
            GoDeeperOfferScreen(
                onCoverEverything = { stage = "deeper_full" },
                onFocusOne = { stage = "deeper_pick" },
                onSkip = { stage = "done" }
            )
            return
        }
        stage == "deeper_pick" -> {
            DimensionPickerScreen(
                onPicked = { dimKey -> stage = "deeper_dimension:$dimKey" },
                onBack = { stage = "offer" }
            )
            return
        }
        stage == "deeper_full" -> {
            DeeperCheckInFlow(
                questions = buildFullCheckInQuestions(),
                title = "Covering everything",
                onFinished = { stage = "done" }
            )
            return
        }
        stage.startsWith("deeper_dimension:") -> {
            val dimKey = stage.removePrefix("deeper_dimension:")
            DeeperCheckInFlow(
                questions = buildDimensionFocusQuestions(dimKey),
                title = "Focusing on ${itemBank.first { it.key == dimKey }.label}",
                onFinished = { stage = "done" }
            )
            return
        }
        stage == "done" -> {
            DoneScreen(
                savedCount = savedCount,
                onGoHome = onGoHome
            )
            return
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()

        if (!loaded) return@Box

        val question = todaysQuestions[currentIndex]

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))
            Text("TONIGHT'S CHECK-IN", color = Soft,
                fontSize = 12.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(14.dp))
            ProgressPips(total = todaysQuestions.size, done = currentIndex)

            Spacer(Modifier.weight(0.6f))

            Box(
                modifier = Modifier
                    .background(AccentSoft, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(question.label, color = Accent,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(18.dp))
            Text(question.text, color = Ink, fontSize = 26.sp,
                fontWeight = FontWeight.Medium, lineHeight = 34.sp)

            Spacer(Modifier.weight(0.5f))

            optionsFor(question.reverse).forEachIndexed { index, option ->
                OptionRow(option, selected == index) { if (selected < 0) selected = index }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(18.dp))

            if (currentIndex > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentSoft, RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0x336A5A9E), RoundedCornerShape(4.dp))
                        .clickable {
                            currentIndex--
                            while (answers.size > currentIndex) {
                                answers.removeAt(answers.size - 1)
                            }
                            selected = -1
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("← Change my last answer",
                        color = Accent, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── The "go deeper" offer, shown right after the core six ────
@Composable
fun GoDeeperOfferScreen(
    onCoverEverything: () -> Unit,
    onFocusOne: () -> Unit,
    onSkip: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("That's your six\nfor today.", color = Ink, fontSize = 30.sp,
                fontWeight = FontWeight.Medium, lineHeight = 38.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "Feeling reflective? You can go a little deeper tonight — " +
                        "no pressure at all.",
                color = Soft, fontSize = 15.sp, lineHeight = 22.sp
            )

            Spacer(Modifier.height(28.dp))

            GoDeeperOption("✳", "Cover everything", "All six areas, one question each") { onCoverEverything() }
            Spacer(Modifier.height(8.dp))
            GoDeeperOption("◔", "Focus on one area", "Really dig into a single dimension") { onFocusOne() }
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSkip() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("I'm good for tonight", color = Soft, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun GoDeeperOption(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 20.sp, color = Accent)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Soft, fontSize = 12.sp)
        }
    }
}

// ── Pick a dimension to focus on ──────────────────────────────
@Composable
fun DimensionPickerScreen(onPicked: (String) -> Unit, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))
            Box(modifier = Modifier.clickable { onBack() }.padding(vertical = 4.dp)) {
                Text("← Back", color = Soft, fontSize = 14.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text("Which area?", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(20.dp))

            dimensionOrder.forEach { (key, label) ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor, RoundedCornerShape(14.dp))
                        .clickable { onPicked(key) }
                        .padding(vertical = 16.dp, horizontal = 16.dp)
                ) {
                    Text(label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun DeeperCheckInFlow(questions: List<Question>, title: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentIndex by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(-1) }
    var saving by remember { mutableStateOf(false) }
    val answers = remember { mutableStateListOf<Int>() }

    LaunchedEffect(selected) {
        if (selected >= 0) {
            delay(300)
            if (currentIndex < answers.size) answers[currentIndex] = selected
            else answers.add(selected)

            if (currentIndex < questions.size - 1) {
                currentIndex++
                selected = if (currentIndex < answers.size) answers[currentIndex] else -1
            } else {
                saving = true
                scope.launch {
                    saveCheckIn(context, questions, answers.toList())
                    onFinished()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()
        if (saving || questions.isEmpty()) return@Box

        val question = questions[currentIndex]
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))
            Text(title.uppercase(), color = Soft,
                fontSize = 12.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(14.dp))
            ProgressPips(total = questions.size, done = currentIndex)

            Spacer(Modifier.weight(0.6f))

            Box(
                modifier = Modifier
                    .background(AccentSoft, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(question.label, color = Accent,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp))
            Text(question.text, color = Ink, fontSize = 26.sp,
                fontWeight = FontWeight.Medium, lineHeight = 34.sp)

            Spacer(Modifier.weight(0.5f))

            optionsFor(question.reverse).forEachIndexed { index, option ->
                OptionRow(option, selected == index) { if (selected < 0) selected = index }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(18.dp))

            if (currentIndex > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentSoft, RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0x336A5A9E), RoundedCornerShape(4.dp))
                        .clickable {
                            currentIndex--
                            while (answers.size > currentIndex) {
                                answers.removeAt(answers.size - 1)
                            }
                            selected = -1
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("← Change my last answer",
                        color = Accent, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun ProgressPips(total: Int, done: Int) {
    Row(modifier = Modifier.fillMaxWidth()) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .weight(1f).height(5.dp)
                    .background(
                        if (index <= done) Accent else Color(0x66FFFFFF),
                        RoundedCornerShape(3.dp)
                    )
            )
            if (index < total - 1) Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
fun OptionRow(option: Option, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) Accent else CardColor,
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = option.weather,
                color = if (isSelected) Color.White else Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "(${option.meaning})",
                color = if (isSelected) Color(0xE6FFFFFF) else Soft,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
fun DoneScreen(savedCount: Int, onGoHome: () -> Unit) {
    val saveDone = savedCount >= 0

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("All done for\ntonight.", color = Ink, fontSize = 32.sp,
            fontWeight = FontWeight.Medium, lineHeight = 40.sp)

        Spacer(Modifier.height(10.dp))
        Text("Thanks for checking in.", color = Soft, fontSize = 15.sp)

        Spacer(Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (saveDone) Accent else Color(0x886A5A9E), RoundedCornerShape(16.dp))
                .clickable(enabled = saveDone) { onGoHome() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (saveDone) "Back to home" else "Saving…",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── Onboarding: staged (quick start -> optional deep dive) ───
@Composable
fun LinearProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color(0x40FFFFFF), RoundedCornerShape(3.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(Accent, RoundedCornerShape(3.dp))
        )
    }
}

@Composable
fun AnchorSectionIntro(dimensionLabel: String, dimensionIndex: Int, total: Int, onContinue: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("SECTION ${dimensionIndex + 1} OF $total", color = Soft,
                fontSize = 12.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(14.dp))
            Text("Now, your\n$dimensionLabel", color = Ink,
                fontSize = 30.sp, fontWeight = FontWeight.Medium, lineHeight = 38.sp)
            Spacer(Modifier.height(14.dp))
            Text("A few more questions — same as always, just tap what fits.",
                color = Soft, fontSize = 15.sp, lineHeight = 22.sp)

            Spacer(Modifier.height(36.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Accent, RoundedCornerShape(16.dp))
                    .clickable { onContinue() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Continue", color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun AnchorDoneScreen(onDone: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("You're all set.", color = Ink, fontSize = 32.sp,
                fontWeight = FontWeight.Medium, lineHeight = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "That gives your weather a real starting point. From here " +
                        "it's just a minute a day.",
                color = Soft, fontSize = 15.sp, lineHeight = 22.sp
            )
            Spacer(Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Accent, RoundedCornerShape(16.dp))
                    .clickable { onDone() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("See my weather", color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Stage router: quick start -> offer -> (optional) deep dive -> home. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var stage by remember { mutableStateOf("quickstart") }

    when (stage) {
        "quickstart" -> QuickStartFlow(onFinished = { stage = "offer" })
        "offer" -> DeepDiveOfferScreen(
            onAccept = { stage = "deepdive" },
            onSkip = onDone
        )
        "deepdive" -> DeepDiveFlow(onFinished = onDone)
    }
}

@Composable
fun QuickStartFlow(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var questions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(-1) }
    var saving by remember { mutableStateOf(false) }
    val answers = remember { mutableStateListOf<Int>() }

    LaunchedEffect(Unit) {
        questions = selectDailyQuestions(0)
        loaded = true
    }

    LaunchedEffect(selected) {
        if (selected >= 0) {
            delay(300)
            if (currentIndex < answers.size) answers[currentIndex] = selected
            else answers.add(selected)

            if (currentIndex < questions.size - 1) {
                currentIndex++
                selected = if (currentIndex < answers.size) answers[currentIndex] else -1
            } else {
                saving = true
                scope.launch {
                    saveAnchor(context, questions, answers.toList())
                    onFinished()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()
        if (!loaded || saving) return@Box

        val question = questions[currentIndex]
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))
            Text("GETTING STARTED", color = Soft,
                fontSize = 12.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            Text("A one-minute start", color = Ink,
                fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            ProgressPips(total = questions.size, done = currentIndex)

            Spacer(Modifier.weight(0.6f))

            Box(
                modifier = Modifier
                    .background(AccentSoft, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(question.label, color = Accent,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp))
            Text(question.text, color = Ink, fontSize = 26.sp,
                fontWeight = FontWeight.Medium, lineHeight = 34.sp)

            Spacer(Modifier.weight(0.5f))

            optionsFor(question.reverse).forEachIndexed { index, option ->
                OptionRow(option, selected == index) { if (selected < 0) selected = index }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun DeepDiveOfferScreen(onAccept: () -> Unit, onSkip: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Nice — that's the basics in.", color = Ink,
                fontSize = 30.sp, fontWeight = FontWeight.Medium, lineHeight = 38.sp)
            Spacer(Modifier.height(14.dp))
            Text(
                "Want to give it a fuller picture? About 8 more minutes, " +
                        "totally optional — it makes your very first trends sharper.",
                color = Soft, fontSize = 15.sp, lineHeight = 22.sp
            )
            Spacer(Modifier.height(30.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Accent, RoundedCornerShape(16.dp))
                    .clickable { onAccept() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Give the fuller picture", color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSkip() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Maybe later — take me to my weather",
                    color = Soft, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun DeepDiveFlow(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var allQuestions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(-1) }
    var finished by remember { mutableStateOf(false) }
    var introShownForDim by remember { mutableStateOf(-1) }
    val answers = remember { mutableStateListOf<Int>() }

    LaunchedEffect(Unit) {
        allQuestions = buildDeepDiveQuestions()
        loaded = true
    }

    LaunchedEffect(selected) {
        if (selected >= 0) {
            delay(300)
            if (currentIndex < answers.size) answers[currentIndex] = selected
            else answers.add(selected)

            if (currentIndex < allQuestions.size - 1) {
                currentIndex++
                selected = if (currentIndex < answers.size) answers[currentIndex] else -1
            } else {
                scope.launch {
                    saveAnchor(context, allQuestions, answers.toList())
                    finished = true
                }
            }
        }
    }

    if (!loaded) {
        Box(modifier = Modifier.fillMaxSize()) { SkyBackground() }
        return
    }

    if (finished) {
        AnchorDoneScreen(onDone = onFinished)
        return
    }

    val questionsPerDim = 6
    val currentDim = currentIndex / questionsPerDim

    if (introShownForDim != currentDim) {
        AnchorSectionIntro(
            dimensionLabel = dimensionOrder[currentDim].second,
            dimensionIndex = currentDim,
            total = dimensionOrder.size,
            onContinue = { introShownForDim = currentDim }
        )
        return
    }

    val question = allQuestions[currentIndex]
    val fraction = (currentIndex + 1).toFloat() / allQuestions.size

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))

            Text(
                "${dimensionOrder[currentDim].second.uppercase()} · QUESTION ${currentIndex + 1} OF ${allQuestions.size}",
                color = Soft, fontSize = 11.sp, letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressBar(fraction)

            Spacer(Modifier.weight(0.6f))

            Text(question.text, color = Ink, fontSize = 26.sp,
                fontWeight = FontWeight.Medium, lineHeight = 34.sp)

            Spacer(Modifier.weight(0.5f))

            optionsFor(question.reverse).forEachIndexed { index, option ->
                OptionRow(option, selected == index) { if (selected < 0) selected = index }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(18.dp))

            if (currentIndex > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentSoft, RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0x336A5A9E), RoundedCornerShape(4.dp))
                        .clickable {
                            currentIndex--
                            while (answers.size > currentIndex) {
                                answers.removeAt(answers.size - 1)
                            }
                            selected = -1
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("← Change my last answer",
                        color = Accent, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Journal: write -> confirm/correct -> done ─────────────────
@Composable
fun JournalFlow(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf("write") }
    var extractedTags by remember { mutableStateOf<List<ExtractedTag>>(emptyList()) }

    when (stage) {
        "write" -> {
            JournalWriteScreen(
                onExtracted = { tags ->
                    extractedTags = tags
                    stage = if (tags.isEmpty()) "done" else "confirm"
                }
            )
        }
        "confirm" -> {
            JournalConfirmScreen(
                tags = extractedTags,
                onSaved = { stage = "done" }
            )
        }
        "done" -> {
            JournalDoneScreen(onGoHome = onFinished)
        }
    }
}

@Composable
fun JournalWriteScreen(onExtracted: (List<ExtractedTag>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val usingRealAi = remember { ModelManager.isModelAvailable(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))
            Text("WRITE ABOUT YOUR DAY", color = Soft,
                fontSize = 12.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(10.dp))
            Text("Whatever's on your mind", color = Ink,
                fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Just write — the app will gently suggest a few things it " +
                        "noticed, and you decide what's actually true.",
                color = Soft, fontSize = 14.sp, lineHeight = 20.sp
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Today was...", color = Soft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CardColor,
                    unfocusedContainerColor = CardColor,
                    focusedTextColor = Ink,
                    unfocusedTextColor = Ink,
                    focusedIndicatorColor = Accent,
                    unfocusedIndicatorColor = Color(0x336A5A9E),
                    cursorColor = Accent
                )
            )

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (text.isNotBlank() && !saving) Accent else Color(0x886A5A9E),
                        RoundedCornerShape(16.dp)
                    )
                    .clickable(enabled = text.isNotBlank() && !saving) {
                        saving = true
                        scope.launch {
                            val dao = AppDatabase.get(context).checkInDao()
                            dao.insertJournalEntry(
                                JournalEntity(
                                    text = text,
                                    dateKey = todayDateKey(),
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                            val tags = withContext(Dispatchers.IO) {
                                if (ModelManager.isModelAvailable(context)) {
                                    LlmEngine.extractTagsReal(context, text).getOrElse {
                                        extractTagsStub(text)
                                    }
                                } else {
                                    extractTagsStub(text)
                                }
                            }
                            onExtracted(tags)
                        }
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        saving && usingRealAi -> "Thinking this through — real on-device AI can take a few minutes"
                        saving -> "Reading through it…"
                        else -> "See what this suggests"
                    },
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun JournalConfirmScreen(tags: List<ExtractedTag>, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    // Journal-derived tags are always non-reverse (set explicitly below),
    // so this screen always uses the standard, non-mirrored option set.
    val journalOptions = remember { optionsFor(false) }

    val selections = remember {
        mutableStateListOf<Int?>().apply {
            tags.forEach { tag ->
                add(Math.round(tag.suggestedLoad / 25f).coerceIn(0, 4))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))
            Text("A FEW THINGS I NOTICED", color = Soft,
                fontSize = 12.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(10.dp))
            Text("Does this feel right?", color = Ink,
                fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Adjust anything that's off, or skip what doesn't fit — " +
                        "only what you confirm gets saved.",
                color = Soft, fontSize = 14.sp, lineHeight = 20.sp
            )

            Spacer(Modifier.height(20.dp))

            tags.forEachIndexed { i, tag ->
                val label = itemBank.first { it.key == tag.dimension }.label
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(AccentSoft, RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(label, color = Accent, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "\"${tag.evidence}\"",
                        color = Ink, fontSize = 15.sp, lineHeight = 21.sp,
                        fontStyle = FontStyle.Italic
                    )
                    Spacer(Modifier.height(14.dp))

                    journalOptions.forEachIndexed { optIndex, option ->
                        OptionRow(option, selections[i] == optIndex) {
                            selections[i] = optIndex
                        }
                        if (optIndex < journalOptions.size - 1) Spacer(Modifier.height(6.dp))
                    }

                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selections[i] = null }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (selections[i] == null) "Skipped — won't be saved" else "This doesn't feel accurate — skip",
                            color = Soft, fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            val confirmedCount = selections.count { it != null }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (!saving) Accent else Color(0x886A5A9E), RoundedCornerShape(16.dp))
                    .clickable(enabled = !saving) {
                        saving = true
                        scope.launch {
                            val journalQuestions = mutableListOf<Question>()
                            val journalAnswers = mutableListOf<Int>()

                            tags.forEachIndexed { i, tag ->
                                val sel = selections[i]
                                if (sel != null) {
                                    val label = itemBank.first { it.key == tag.dimension }.label
                                    journalQuestions.add(
                                        Question(
                                            itemId = "JOURNAL-${tag.dimension}-${tag.facet}-${System.currentTimeMillis()}-$i",
                                            dimension = tag.dimension,
                                            label = label,
                                            facet = tag.facet,
                                            text = tag.evidence,
                                            reverse = false
                                        )
                                    )
                                    journalAnswers.add(sel)
                                }
                            }

                            if (journalQuestions.isNotEmpty()) {
                                saveCheckIn(context, journalQuestions, journalAnswers)
                            }
                            onSaved()
                        }
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (confirmedCount > 0) "Save these reflections ($confirmedCount)" else "Continue without saving",
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun JournalDoneScreen(onGoHome: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Thanks for\nwriting that.", color = Ink, fontSize = 32.sp,
                fontWeight = FontWeight.Medium, lineHeight = 40.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Your words are saved privately, on this device only.",
                color = Soft, fontSize = 15.sp, lineHeight = 21.sp
            )
            Spacer(Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Accent, RoundedCornerShape(16.dp))
                    .clickable { onGoHome() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Back to home", color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── On-device AI model setup (real in-app download) ────────────
@Composable
fun ModelSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var available by remember { mutableStateOf(ModelManager.isModelAvailable(context)) }
    val downloadState by ModelManager.downloadState.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java))
    }

    LaunchedEffect(downloadState) {
        if (downloadState is ModelDownloadState.Success) {
            available = ModelManager.isModelAvailable(context)
        }
    }

    fun startDownload() {
        ModelManager.resetState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackground()

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(44.dp))
            Box(modifier = Modifier.clickable { onBack() }.padding(vertical = 4.dp)) {
                Text("← Back", color = Soft, fontSize = 14.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text("Set up on-device AI", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))

            when {
                available -> {
                    var testing by remember { mutableStateOf(false) }
                    var testResult by remember { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AccentSoft, RoundedCornerShape(14.dp))
                            .padding(16.dp)
                    ) {
                        Text("✓ Ready — real on-device AI is active for your journal entries.",
                            color = Accent, fontSize = 14.sp, lineHeight = 20.sp)
                    }

                    Spacer(Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (!testing) Accent else Color(0x886A5A9E), RoundedCornerShape(16.dp))
                            .clickable(enabled = !testing) {
                                testing = true
                                testResult = null
                                scope.launch(Dispatchers.IO) {
                                    val result = LlmEngine.testInference(context)
                                    withContext(Dispatchers.Main) {
                                        testing = false
                                        testResult = result.fold(
                                            onSuccess = { "✓ Model responded: \"$it\"" },
                                            onFailure = { "✗ Failed: ${it.message ?: it.javaClass.simpleName}" }
                                        )
                                    }
                                }
                            }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (testing) "Loading model — this may take a while…" else "Test the model",
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium
                        )
                    }

                    testResult?.let { msg ->
                        Spacer(Modifier.height(12.dp))
                        Text(msg, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardColor, RoundedCornerShape(14.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            "Not set up yet — journal entries currently use simple pattern " +
                                    "matching, not a real model. Downloading the model (about " +
                                    "3.9GB, one time only) unlocks genuine AI-suggested reflections.",
                            color = Soft, fontSize = 14.sp, lineHeight = 20.sp
                        )
                    }
                    val dl = downloadState
                    if (dl is ModelDownloadState.Downloading) {
                        val raw = dl.progress.toFloat()
                        val fraction = (if (raw > 1f) raw / 100f else raw).coerceIn(0f, 1f)
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardColor, RoundedCornerShape(14.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Text(
                                    "Downloading… ${(fraction * 100).toInt()}%",
                                    color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Accent
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "You can leave this screen. The download continues in the background.",
                                    color = Soft, fontSize = 12.sp, lineHeight = 18.sp
                                )
                            }
                        }
                    }
                    if (downloadState is ModelDownloadState.Failed) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Download failed: ${(downloadState as ModelDownloadState.Failed).message}. Try again.",
                            color = StormColor, fontSize = 13.sp, lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (!available && downloadState !is ModelDownloadState.Downloading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Accent, RoundedCornerShape(16.dp))
                        .clickable { startDownload() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Download model (~3.9GB)", color = Color.White,
                        fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Note: this likely won't work in the emulator — the model needs " +
                            "more memory than it's given. Try this on a real phone.",
                    color = Soft, fontSize = 12.sp, lineHeight = 18.sp
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
