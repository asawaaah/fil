package com.asawaaah.fil.ai

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException

/**
 * Translates paragraphs into French with the on-device model: slower than ML Kit, but much more
 * natural. Works chunk by chunk (a few paragraphs at a time) so the reader can follow along,
 * and a summary request can slip in between two chunks.
 */
class AiTranslator(private val engine: LlmEngine) {

    /**
     * Translates [texts]; blank entries are skipped. [onChunk] receives each chunk's results
     * (index in [texts] to translation). Paragraphs the model mangled are simply missing from the result.
     */
    suspend fun translate(
        texts: List<String>,
        languageName: String,
        onChunk: suspend (Map<Int, String>) -> Unit,
    ): Map<Int, String> {
        val result = HashMap<Int, String>()
        for (chunk in chunks(texts)) {
            // A French translation is about as long as the source: cap the output to avoid runaway text.
            val chunkChars = chunk.sumOf { texts[it].length }
            val maxTokens = (chunkChars / 2 + 150).coerceIn(200, 1500)
            val translated = engine.use(onLoading = {}) { eng, model ->
                val config = ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_PROMPT),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
                    maxOutputToken = maxTokens,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
                eng.createConversation(config).use { conversation ->
                    val raw = StringBuilder()
                    try {
                        conversation.sendMessageAsync(buildPrompt(chunk.map { texts[it] }, languageName, model)).collect { m ->
                            val piece = m.toString()
                            if (raw.isNotEmpty() && piece.length >= raw.length && piece.startsWith(raw)) raw.setLength(0)
                            raw.append(piece)
                        }
                    } catch (e: CancellationException) {
                        runCatching { conversation.cancelProcess() }
                        throw e
                    }
                    parse(raw.toString(), chunk.size)
                }
            }
            val mapped = translated.mapKeys { (local, _) -> chunk[local] }
            result += mapped
            onChunk(mapped)
        }
        return result
    }

    companion object {
        private const val MAX_CHUNK_CHARS = 1200
        private const val MAX_CHUNK_PARAGRAPHS = 8

        private const val SYSTEM_PROMPT =
            "Tu es un traducteur professionnel. Tu traduis fidèlement vers un français naturel et correct, " +
                "sans résumer, sans rien ajouter et sans commentaire."

        /** Groups the non-blank paragraphs into chunks of a reasonable size. */
        fun chunks(texts: List<String>): List<List<Int>> {
            val out = ArrayList<List<Int>>()
            var current = ArrayList<Int>()
            var size = 0
            for ((i, t) in texts.withIndex()) {
                if (t.isBlank()) continue
                if (current.isNotEmpty() && (size + t.length > MAX_CHUNK_CHARS || current.size >= MAX_CHUNK_PARAGRAPHS)) {
                    out += current
                    current = ArrayList()
                    size = 0
                }
                current += i
                size += t.length
            }
            if (current.isNotEmpty()) out += current
            return out
        }

        fun buildPrompt(paragraphs: List<String>, languageName: String, model: LlmModel): String {
            val numbered = paragraphs.mapIndexed { i, p -> "[${i + 1}] ${p.replace(Regex("\\s+"), " ").trim()}" }
            val noThink = if (model.family == ModelFamily.QWEN) "\n/no_think" else ""
            return buildString {
                append("Traduis en français ces paragraphes (langue d'origine : $languageName).\n")
                append("Règles :\n")
                append("- Garde exactement la numérotation [1], [2]… : un paragraphe traduit par numéro, dans le même ordre.\n")
                append("- Traduis tout, sans résumer ni commenter.\n")
                append("- Garde tels quels les noms propres, les marques et le code.\n\n")
                append(numbered.joinToString("\n\n"))
                append(noThink)
            }
        }

        /** Reads "[1] … [2] …" back into local index (0-based) to text. */
        fun parse(raw: String, expected: Int): Map<Int, String> {
            val text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            val marker = Regex("""(?m)^\s*\[(\d{1,2})]\s*""")
            val matches = marker.findAll(text).toList()
            val out = HashMap<Int, String>()
            matches.forEachIndexed { k, m ->
                val number = m.groupValues[1].toInt()
                val end = if (k + 1 < matches.size) matches[k + 1].range.first else text.length
                val body = text.substring(m.range.last + 1, end).trim().replace("**", "")
                if (number in 1..expected && body.isNotEmpty() && (number - 1) !in out) out[number - 1] = body
            }
            // A single paragraph often comes back without its number.
            if (out.isEmpty() && expected == 1 && text.isNotBlank()) out[0] = text.trim()
            return out
        }
    }
}
