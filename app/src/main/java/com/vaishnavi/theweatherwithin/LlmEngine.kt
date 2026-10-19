package com.vaishnavi.theweatherwithin

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Real on-device extraction — confirmed working, Session 14. The engine
 * is loaded ONCE and kept alive (not recreated per call), since loading
 * took real, noticeable time in testing. Only the first journal entry
 * after opening the app pays that cost; every one after reuses the
 * already-loaded model.
 *
 * Session 16: added a 90-second timeout around real inference (the
 * library has no built-in way to cap response length — confirmed by
 * reading its actual source — so this protects the user from an
 * open-ended wait regardless of what the model does) and a brevity
 * instruction in the prompt itself.
 *
 * Falls back gracefully: if the model isn't downloaded, times out, or
 * real inference fails for any reason, callers should fall back to
 * extractTagsStub() — this object never leaves the journal flow with
 * nothing to show.
 */
object LlmEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    // The validated prompt from burnout_spike_v2.py — same evidence rule,
    // same six dimensions, same JSON schema that scored well in testing.
    // Deliberately dimension-only, not facet-level — see the known
    // simplification noted where this gets mapped to ExtractedTag below.
    private const val SYSTEM_PROMPT = """You are a strict labelling tool for a wellbeing app. You do not diagnose, advise, or chat.

You read a short description of someone's day and identify ONLY the types of depletion for which the text gives DIRECT evidence.

THE SIX TYPES
- physical: tiredness, poor sleep, aches, illness, appetite loss, bodily depletion
- mental: concentration problems, brain fog, forgetfulness, decision fatigue, inability to switch off
- emotional: low mood, irritability, tearfulness, numbness, feeling drained or overwhelmed
- social: withdrawing from people, loneliness, dreading contact, cancelling on others
- professional: work exhaustion, dread of work, cynicism about work, feeling ineffective at work
- personal: no time or energy for one's own life, lost hobbies, lost meaning or identity outside work

THE EVIDENCE RULE — THIS IS THE MOST IMPORTANT RULE
For every type you flag, you MUST quote the exact words from the text that prove it.
If you cannot quote proof, DO NOT FLAG IT. No exceptions.

WHAT IS NOT EVIDENCE
- Mentioning work is NOT professional depletion. A normal day at work is just a day at work.
- Mentioning sleep is NOT physical depletion unless the sleep was bad or they are tired.
- Mentioning people is NOT social depletion unless they withdrew, dreaded contact, or felt lonely.
- Being tired is NOT automatically mental depletion. Only flag mental if focus/memory/thinking is mentioned.
- Do not infer a second type just because it often accompanies the first. Flag only what is written.

NEUTRAL AND GOOD DAYS
Many days are fine. If the text shows no depletion, return an empty list and primary "none".
Do not hunt for problems that are not there.

SCORES
- intensity: 0.0-1.0, how severe the depletion appears
- confidence: 0.0-1.0, how certain you are the evidence really supports it. Be honest. Use low values when you are guessing.

Keep your response as short as possible. Output the JSON and nothing else — no repetition, no restating the input, no explanation outside the JSON object.

Output ONLY a JSON object. No explanation, no markdown, no backticks.

OUTPUT SCHEMA
{"dimensions": [{"dimension": "<one of the six>", "evidence": "<exact words from the text>", "intensity": <0.0-1.0>, "confidence": <0.0-1.0>}], "primary": "<one of the six, or none>"}

Input: "Worked until midnight for the third night. My eyes are burning and I couldn't focus by the end."
Output: {"dimensions": [{"dimension": "professional", "evidence": "Worked until midnight for the third night", "intensity": 0.8, "confidence": 0.9}, {"dimension": "physical", "evidence": "My eyes are burning", "intensity": 0.7, "confidence": 0.8}, {"dimension": "mental", "evidence": "couldn't focus by the end", "intensity": 0.6, "confidence": 0.8}], "primary": "professional"}

Input: "Slept well, finished my work on time, went for a run in the evening."
Output: {"dimensions": [], "primary": "none"}

Input: "Busy day at the office, lots of meetings, but we shipped the release and I felt good about it."
Output: {"dimensions": [], "primary": "none"}"""

    private const val CONFIDENCE_FLOOR = 0.5

    /** Loads the model if it isn't already loaded. Safe to call every
     *  time — does nothing if already loaded. */
    private fun ensureLoaded(context: Context) {
        if (engine != null && conversation != null) return

        val modelPath = ModelManager.modelFile(context).absolutePath
        val engineConfig = EngineConfig(modelPath = modelPath, maxNumTokens = 2048)
        val newEngine = Engine(engineConfig)
        newEngine.initialize()
        engine = newEngine
        conversation = newEngine.createConversation()
    }

    /**
     * Runs real extraction. Callers should catch failure and fall back
     * to extractTagsStub() — this deliberately never throws past its
     * own boundary, everything comes back as Result.
     *
     * Wrapped in a 90-second timeout: if the model takes longer than
     * that to respond (the library has no way to cap this itself), we
     * give up and report failure rather than waiting indefinitely.
     */
    suspend fun extractTagsReal(context: Context, text: String): Result<List<ExtractedTag>> {
        return try {
            ensureLoaded(context)
            val conv = conversation ?: return Result.failure(Exception("Model not loaded"))

            val prompt = "$SYSTEM_PROMPT\n\nInput: \"$text\"\nOutput:"

            val response = withTimeout(90_000) {
                conv.sendMessage(prompt).toString()
            }

            val json = extractJson(response)
                ?: return Result.failure(Exception("Model reply wasn't valid JSON"))

            Result.success(parseToTags(json))
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Real extraction timed out after 90 seconds"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The original test function from Session 14 — kept alongside real
     *  extraction so the "Test the model" button on ModelSetupScreen
     *  keeps working. Reuses the SAME loaded engine/conversation as
     *  extractTagsReal(), rather than creating a separate one. */
    suspend fun testInference(context: Context): Result<String> {
        return try {
            ensureLoaded(context)
            val conv = conversation ?: return Result.failure(Exception("Model not loaded"))
            val response = conv.sendMessage("Say hello in one short sentence.")
            Result.success(response.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Same robustness as the Python harness: try direct parse first,
     *  then fall back to pulling a {...} block out of a messier reply
     *  (models sometimes wrap JSON in markdown fences or extra text). */
    private fun extractJson(raw: String): JSONObject? {
        try {
            return JSONObject(raw.trim())
        } catch (_: Exception) { /* fall through */ }

        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val matcher = Pattern.compile("\\{[\\s\\S]*\\}").matcher(cleaned)
        if (matcher.find()) {
            try {
                return JSONObject(matcher.group())
            } catch (_: Exception) { /* fall through */ }
        }
        return null
    }

    private fun parseToTags(json: JSONObject): List<ExtractedTag> {
        val dims = json.optJSONArray("dimensions") ?: return emptyList()
        val results = mutableListOf<ExtractedTag>()
        val validDimensions = itemBank.map { it.key }.toSet()

        for (i in 0 until dims.length()) {
            val entry = dims.optJSONObject(i) ?: continue
            val dimension = entry.optString("dimension", "").lowercase()
            if (dimension !in validDimensions) continue

            val confidence = entry.optDouble("confidence", 1.0)
            if (confidence < CONFIDENCE_FLOOR) continue

            val intensity = entry.optDouble("intensity", 0.7)
            val evidence = entry.optString("evidence", "")

            // Known simplification: the validated prompt is dimension-only,
            // no facet granularity — use that dimension's first facet as
            // a representative stand-in, same one the stub already uses.
            val facet = itemBank.first { it.key == dimension }.facets.first().key

            results.add(
                ExtractedTag(
                    dimension = dimension,
                    facet = facet,
                    evidence = evidence,
                    suggestedLoad = (intensity * 100).toInt().coerceIn(0, 100),
                    confidence = confidence
                )
            )
        }
        return results
    }
}