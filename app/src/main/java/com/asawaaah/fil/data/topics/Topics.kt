package com.asawaaah.fil.data.topics

/** A news topic, like the sections of Google News. Sub-topics ("sport.football") have a [parent]. */
data class Topic(val id: String, val label: String, val parent: String? = null)

object Topics {
    val all = listOf(
        Topic("international", "International"),
        Topic("politique", "Politique"),
        Topic("economie", "Économie"),
        Topic("tech", "Tech"),
        Topic("sciences", "Sciences"),
        Topic("sante", "Santé"),
        Topic("environnement", "Environnement"),
        Topic("societe", "Société"),
        Topic("culture", "Culture"),
        Topic("people", "People"),
        Topic("jeux", "Jeux vidéo"),
        Topic("sport", "Sport"),
        Topic("sport.football", "Football", "sport"),
        Topic("sport.rugby", "Rugby", "sport"),
        Topic("sport.tennis", "Tennis", "sport"),
        Topic("sport.cyclisme", "Cyclisme", "sport"),
        Topic("sport.auto", "Sports mécaniques", "sport"),
        Topic("sport.basket", "Basket", "sport"),
        Topic("sport.autres", "Autres sports", "sport"),
    )

    private val byId = all.associateBy { it.id }

    fun get(id: String): Topic? = byId[id]
    fun label(id: String): String = byId[id]?.label ?: id
    fun children(id: String): List<Topic> = all.filter { it.parent == id }
    val roots: List<Topic> get() = all.filter { it.parent == null }

    /** Stored form: "sport.football,societe". */
    fun parse(stored: String?): List<String> = stored?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    fun format(ids: List<String>): String? = ids.takeIf { it.isNotEmpty() }?.joinToString(",")

    /** Hidden when one of its topics is hidden, or that topic's parent ("Sport" hides "Football"). */
    fun isHidden(topics: List<String>, hidden: Set<String>): Boolean =
        hidden.isNotEmpty() && topics.any { it in hidden || byId[it]?.parent in hidden }

    /** The hidden topic responsible, for the "12 articles masqués (Football)" line. */
    fun hidingTopic(topics: List<String>, hidden: Set<String>): String? =
        topics.firstNotNullOfOrNull { t ->
            when {
                t in hidden -> t
                byId[t]?.parent in hidden -> byId[t]?.parent
                else -> null
            }
        }
}
