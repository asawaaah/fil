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

    fun summarize(
        title: String,
        articleText: String,
        language: String = "fr",
        length: SummaryLength = SummaryLength.MEDIUM,
    ): Flow<SummaryEvent> = channelFlow {
        val started = System.currentTimeMillis()
        engine.use(onLoading = { send(SummaryEvent.LoadingModel) }) { eng, model ->
            send(SummaryEvent.Reading)
            val config = ConversationConfig(
                systemInstruction = Contents.of(if (language == "fr") SYSTEM_PROMPT else SYSTEM_PROMPT_OTHER),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.3),
                maxOutputToken = length.maxTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            eng.createConversation(config).use { conversation ->
                val raw = StringBuilder()
                try {
                    conversation.sendMessageAsync(buildPrompt(title, articleText, model, language, length)).collect { message ->
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

        fun buildPrompt(
            title: String,
            text: String,
            model: LlmModel,
            language: String = "fr",
            length: SummaryLength = SummaryLength.MEDIUM,
        ): String {
            val body = truncate(text, MAX_INPUT_CHARS)
            val noThink = if (model.family == ModelFamily.QWEN) " /no_think" else ""
            if (language != "fr") {
                val name = languageName(language)
                val task = when (length) {
                    SummaryLength.SHORT -> """
                        Summarize the article below in $name, in one or two sentences (40 words at most).
                        Rules:
                        - Give only the essentials: who, what, why.
                        - Write only in $name, the language of the article.
                        - No bullet points, no introduction.
                    """
                    SummaryLength.MEDIUM -> """
                        Summarize the article below in $name, in 3 to 5 key points.
                        Rules:
                        - Each point starts with "•" and is one or two sentences long.
                        - Write only in $name, the language of the article.
                        - No introduction and no conclusion: only the points.
                    """
                    SummaryLength.LONG -> """
                        Write a detailed summary of the article below in $name, in 6 to 8 points.
                        Rules:
                        - Each point starts with "•" and is two or three sentences long.
                        - Give the context, the important facts and figures, the people quoted and the consequences.
                        - Follow the order of the article.
                        - Write only in $name, the language of the article.
                        - No introduction and no conclusion: only the points.
                    """
                }.trimIndent()
                return "$task\n\nTitle: $title\n\nArticle:\n$body$noThink"
            }
            val task = when (length) {
                SummaryLength.SHORT -> """
                    Résume l'article ci-dessous en français, en une ou deux phrases (40 mots au maximum).
                    Règles :
                    - Va à l'essentiel : qui, quoi, pourquoi.
                    - Écris uniquement en français, même si l'article est dans une autre langue.
                    - Pas de liste à puces, pas d'introduction.
                """
                SummaryLength.MEDIUM -> """
                    Résume l'article ci-dessous en français, en 3 à 5 points clés.
                    Règles :
                    - Chaque point commence par « • » et tient en une ou deux phrases.
                    - Écris uniquement en français, même si l'article est dans une autre langue.
                    - Pas d'introduction ni de conclusion : seulement les points.
                """
                SummaryLength.LONG -> """
                    Fais un résumé détaillé de l'article ci-dessous en français, en 6 à 8 points.
                    Règles :
                    - Chaque point commence par « • » et tient en deux ou trois phrases.
                    - Donne le contexte, les faits et chiffres importants, les personnes citées et les conséquences.
                    - Suis l'ordre de l'article.
                    - Écris uniquement en français, même si l'article est dans une autre langue.
                    - Pas d'introduction ni de conclusion : seulement les points.
                """
            }.trimIndent()
            return "$task\n\nTitre : $title\n\nArticle :\n$body$noThink"
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
