package com.asawaaah.fil.data.topics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicClassifierTest {
    private fun topics(url: String?, title: String, categories: List<String> = emptyList(), fr: String? = null) =
        TopicClassifier.classify(url, categories, title, fr)

    @Test
    fun sectionInTheAddress() {
        assertEquals(
            "sport.football",
            topics("https://www.spiegel.de/sport/fussball/bayern-muenchen-gewinnt-a-1234.html", "Kompany und die Bayern").first(),
        )
        assertEquals("sport", topics("https://elpais.com/deportes/2026-10-24/una-noche.html", "Una noche para recordar").first())
        assertEquals("economie", topics("https://www.lemonde.fr/economie/article/2026/10/24/x_1_2.html", "Les marchés hésitent").first())
        assertEquals("sport.football", topics("https://rmcsport.bfmtv.com/football/ligue-1/psg-x_AV-1.html", "Le PSG s'impose").first())
    }

    @Test
    fun sportSectionPlusTitleGivesTheSport() {
        assertEquals(
            listOf("sport.football"),
            topics("https://www.lemonde.fr/sport/article/2026/10/24/x_1_2.html", "Ligue des champions : le PSG renverse Liverpool").take(1),
        )
        assertEquals("sport.tennis", topics("https://www.lemonde.fr/sport/article/2026/x.html", "Alcaraz s'offre un nouveau titre").first())
    }

    @Test
    fun footballTitlesInFourLanguages() {
        assertEquals("sport.football", topics(null, "Mercato : l'OM recrute un attaquant").first())
        assertEquals("sport.football", topics(null, "Premier League: Arsenal beat Chelsea in derby").first())
        assertEquals("sport.football", topics(null, "Bundesliga: BVB verliert gegen Leverkusen").first())
        assertEquals("sport.football", topics(null, "El Real Madrid gana en LaLiga", fr = "Le Real Madrid gagne en LaLiga").first())
    }

    @Test
    fun americanFootballIsNotFootball() {
        assertEquals(
            "sport.autres",
            topics("https://www.spiegel.de/sport/american-football/atlanta-falcons-a-1.html", "American Football: Neues Logo").first(),
        )
    }

    @Test
    fun sitesAboutOneSport() {
        assertEquals("sport.football", topics("https://www.footmercato.net/a1234-transfert", "Un transfert surprise").first())
        assertEquals("sport", topics("https://www.lequipe.fr/Tous-sports/Actualites/x/1", "Une soirée historique").first())
    }

    @Test
    fun blogCategoriesAreIgnored() {
        assertEquals(
            emptyList<String>(),
            topics("https://synfie.fr/2026/10/assemblee-generale/", "Assemblée générale 2026", listOf("Actualités", "Non classé", "Adhérents")),
        )
    }

    @Test
    fun feedCategoriesThatAreSections() {
        assertEquals("sport.rugby", topics("https://example.com/2026/x", "Le XV a tenu bon", listOf("Rugby")).first())
        assertEquals("tech", topics("https://example.com/x", "Une nouvelle puce", listOf("Technologie")).first())
    }

    @Test
    fun avoidsFalseFootball() {
        val reform = topics("https://www.lefigaro.fr/politique/x-20261024", "Le but de la réforme des retraites, selon le ministre")
        assertFalse(reform.any { it.startsWith("sport") })
        assertFalse(topics(null, "Om, le son sacré de la méditation").any { it.startsWith("sport") })
        assertFalse(topics(null, "Une victoire pour les droits des salariés").any { it.startsWith("sport") })
        // Seen on real feeds: "l'arsenal juridique" is not the London club.
        assertFalse(
            TopicClassifier.classify(
                "https://www.les-infostrateges.com/actu/e-reputation-contenus-negatifs",
                emptyList(),
                "E-réputation et contenus négatifs : droit de réponse",
                excerpt = "L'arsenal juridique pour agir est assez large.",
            ).any { it.startsWith("sport") },
        )
        assertFalse(topics(null, "Many people use AI at work").contains("people"))
    }

    @Test
    fun hidingSportHidesFootball() {
        assertTrue(Topics.isHidden(listOf("sport.football"), setOf("sport")))
        assertTrue(Topics.isHidden(listOf("culture", "sport.football"), setOf("sport.football")))
        assertFalse(Topics.isHidden(listOf("sport.tennis"), setOf("sport.football")))
        assertFalse(Topics.isHidden(emptyList(), setOf("sport")))
        assertEquals("sport", Topics.hidingTopic(listOf("sport.rugby"), setOf("sport")))
    }

    @Test
    fun storedForm() {
        assertEquals(listOf("sport.football", "societe"), Topics.parse("sport.football, societe"))
        assertEquals(null, Topics.format(emptyList()))
    }
}
