package com.asawaaah.fil.ai

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

sealed interface SummaryEvent {
    data object LoadingModel : SummaryEvent
    data object Reading : SummaryEvent
    data class Partial(val text: String) : SummaryEvent

    /** [millis] is the whole wait, model loading included. */
    data class Done(val text: String, val model: LlmModel, val millis: Long, val accelerator: Accelerator?) : SummaryEvent
}

/** Writes a short summary of an article, in French or in the article's own language ([language]). */
class Summarizer(private val engine: LlmEngine) {

    fun summarize(title: String, articleText: String, language: String = "fr"): Flow<SummaryEvent> = channelFlow {
        val started = System.currentTimeMillis()
        engine.use(onLoading = { send(SummaryEvent.LoadingModel) }) { eng, model ->
            send(SummaryEvent.Reading)
            val config = ConversationConfig(
                systemInstruction = Contents.of(if (language == "fr") SYSTEM_PROMPT else SYSTEM_PROMPT_OTHER),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.3),
                maxOutputToken = 512,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            eng.createConversation(config).use { conversation ->
                val raw = StringBuilder()
                try {
                    conversation.sendMessageAsync(buildPrompt(title, articleText, model, language)).collect { message ->
                        val chunk = message.toString()
                        // Handle both cumulative and incremental streaming.
                        if (raw.isNotEmpty() && chunk.length >= raw.length && chunk.startsWith(raw)) {
                            raw.setLength(0)
                        }
                        raw.append(chunk)
                        val visible = clean(raw.toString())
                        if (visible.isNotEmpty()) send(SummaryEvent.Partial(visible))
                    }
                } catch (e: CancellationException) {
                    runCatching { conversation.cancelProcess() }
                    throw e
                }
                send(SummaryEvent.Done(clean(raw.toString()), model, System.currentTimeMillis() - started, engine.accelerator))
            }
        }
    }

    companion object {
        const val MAX_INPUT_CHARS = 7000

        private const val SYSTEM_PROMPT =
            "Tu es un assistant de lecture. Tu résumes fidèlement des articles en français, " +
                "sans rien inventer et sans donner ton avis."

        private const val SYSTEM_PROMPT_OTHER =
            "You are a reading assistant. You summarize articles faithfully, without inventing anything " +
                "and without giving your opinion."

        /** English name of a language code, used in the prompt: "Spanish", "German"... */
        fun languageName(code: String): String =
            java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { code }

        fun buildPrompt(title: String, text: String, model: LlmModel, language: String = "fr"): String {
            val body = truncate(text, MAX_INPUT_CHARS)
            val noThink = if (model.family == ModelFamily.QWEN) " /no_think" else ""
            if (language != "fr") {
                val name = languageName(language)
                return """
                    Summarize the article below in $name, in 3 to 5 key points.
                    Rules:
                    - Each point starts with "•" and is one or two sentences long.
                    - Write only in $name, the language of the article.
                    - No introduction and no conclusion: only the points.

                    Title: $title

                    Article:
                    $body$noThink
                """.trimIndent()
            }
            return """
                Résume l'article ci-dessous en français, en 3 à 5 points clés.
                Règles :
                - Chaque point commence par « • » et tient en une ou deux phrases.
                - Écris uniquement en français, même si l'article est dans une autre langue.
                - Pas d'introduction ni de conclusion : seulement les points.

                Titre : $title

                Article :
                $body$noThink
            """.trimIndent()
        }

        fun truncate(text: String, max: Int): String {
            if (text.length <= max) return text
            val cut = text.lastIndexOf("\n\n", max).takeIf { it > max / 2 } ?: text.lastIndexOf(' ', max)
            return text.substring(0, if (cut > 0) cut else max) + "\n[…]"
        }

        /** Removes reasoning blocks and markdown, and normalizes bullets. */
        fun clean(raw: String): String {
            var s = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            val openThink = s.indexOf("<think>")
            if (openThink >= 0) s = s.substring(0, openThink)
            return s.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { line ->
                    line.replace("**", "")
                        .replace(Regex("^([-*•·]|\\d+[.)])\\s*"), "• ")
                }
                .joinToString("\n")
                .trim()
        }
    }
}
