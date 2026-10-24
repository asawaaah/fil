package com.asawaaah.fil.data.topics

import java.net.URI
import java.text.Normalizer

/**
 * Guesses the topics of an article on the phone, without AI or network, from three clues:
 * the section in its web address (/sport/, /deportes/…), the feed's categories when they are
 * known section names, and words of the title and excerpt. Unknown categories ("Actualités",
 * "Non classé") are ignored. A topic needs a score of [THRESHOLD]: one strong clue or two weak ones.
 */
object TopicClassifier {
    /** Bump when the dictionaries change: stored articles are classified again. */
    const val VERSION = 2

    private const val THRESHOLD = 2
    private const val SECTION = 3
    private const val STRONG = 2
    private const val WEAK = 1

    // Section names (in addresses and feed categories), folded: lower case, no accents, "-" kept.
    private val sections: Map<String, String> = buildMap {
        fun section(topic: String, vararg names: String) = names.forEach { this[it] = topic }
        section("sport", "sport", "sports", "deportes", "deporte", "desporto", "esportes", "sportif", "mehr-sport", "sport-mehr", "olympia", "jeux-olympiques", "jo", "rmcsport", "sports-us")
        section("sport.football", "football", "foot", "fussball", "futbol", "soccer", "calcio", "ligue-1", "ligue1", "premier-league", "bundesliga", "laliga", "la-liga", "champions-league", "ligue-des-champions", "mercato", "coupe-du-monde-de-football")
        section("sport.rugby", "rugby", "top-14", "xv-de-france")
        section("sport.tennis", "tennis", "tenis", "roland-garros", "wimbledon")
        section("sport.cyclisme", "cyclisme", "cycling", "radsport", "ciclismo", "tour-de-france", "velo")
        section("sport.auto", "formule-1", "formula-1", "formel-1", "f1", "motorsport", "motogp", "moto-gp", "rallye", "sports-mecaniques")
        section("sport.basket", "basket", "basketball", "nba", "baloncesto", "pro-a")
        section("sport.autres", "handball", "athletisme", "natation", "ski", "biathlon", "judo", "boxe", "golf", "voile", "american-football", "nfl", "football-americain")
        section("economie", "economie", "economy", "economia", "wirtschaft", "business", "finance", "finances", "bourse", "marches", "markets", "entreprises", "emploi", "argent", "mes-finances")
        section("tech", "tech", "technology", "technologie", "technologies", "tecnologia", "netzwelt", "numerique", "pixels", "gadgets", "high-tech", "informatique", "intelligence-artificielle")
        section("sciences", "science", "sciences", "wissenschaft", "ciencia", "espace", "space", "sciences-et-techno")
        section("sante", "sante", "health", "gesundheit", "salud", "medecine")
        section("environnement", "planete", "environnement", "environment", "klima", "climate", "clima", "ecologie", "medio-ambiente", "climat")
        section("international", "international", "monde", "world", "ausland", "internacional", "europe", "afrique", "ameriques", "asie-pacifique", "proche-orient", "europa")
        section("politique", "politique", "politics", "politik", "politica", "elections", "election-presidentielle")
        section("societe", "societe", "society", "gesellschaft", "sociedad", "faits-divers", "justice", "education", "police-justice")
        section("culture", "culture", "cultura", "kultur", "cinema", "musique", "music", "livres", "books", "arts", "series", "television", "theatre", "kino", "musik")
        section("people", "people", "celebrites", "stars", "gente", "royaute", "tele-realite")
        section("jeux", "jeux-video", "gaming", "games", "videogames", "video-games", "jeuxvideo")
    }

    // Whole sites about one topic.
    private val sites = mapOf(
        "lequipe.fr" to "sport", "eurosport.fr" to "sport", "eurosport.com" to "sport", "marca.com" to "sport",
        "as.com" to "sport", "mundodeportivo.com" to "sport", "gazzetta.it" to "sport", "sport.fr" to "sport",
        "footmercato.net" to "sport.football", "sofoot.com" to "sport.football", "kicker.de" to "sport.football",
        "maxifoot.fr" to "sport.football", "foot01.com" to "sport.football", "onzemondial.com" to "sport.football",
        "rugbyrama.fr" to "sport.rugby", "tennisactu.net" to "sport.tennis", "cyclismactu.net" to "sport.cyclisme",
        "jeuxvideo.com" to "jeux", "gamekult.com" to "jeux", "ign.com" to "jeux", "polygon.com" to "jeux",
    )

    // Title/excerpt words, folded. Phrases are matched as whole words.
    private val words: Map<String, Pair<String, Int>> = buildMap {
        fun strong(topic: String, vararg w: String) = w.forEach { put(it, topic to STRONG) }
        fun weak(topic: String, vararg w: String) = w.forEach { put(it, topic to WEAK) }
        strong(
            "sport.football", "football", "footballeur", "footballeuse", "footballeurs", "fussball", "futbol", "soccer",
            "ligue 1", "ligue des champions", "champions league", "ligue europa", "europa league", "ligue conference",
            "premier league", "bundesliga", "laliga", "la liga", "serie a", "coupe de france de football", "psg",
            "paris saint germain", "paris sg", "mercato", "ballon d or", "fifa", "uefa", "real madrid", "fc barcelone",
            "barca", "bayern munich", "fc bayern", "olympique de marseille", "olympique lyonnais", "as monaco", "losc",
            "manchester united", "manchester city", "liverpool fc", "arsenal fc", "chelsea fc", "juventus", "atletico madrid",
            "mbappe", "dembele", "deschamps", "les bleus", "equipe de france de football", "bvb", "borussia dortmund",
        )
        weak("sport.football", "attaquant", "buteur", "gardien de but", "penalty", "entraineur", "trainer", "torwart", "stade", "coupe du monde")
        strong("sport.rugby", "rugby", "top 14", "xv de france", "six nations", "tournoi des six nations", "all blacks", "pro d2")
        strong(
            "sport.tennis", "tennis", "roland garros", "wimbledon", "us open", "open d australie", "atp", "wta",
            "djokovic", "nadal", "alcaraz", "sinner", "coupe davis",
        )
        strong("sport.cyclisme", "cyclisme", "tour de france cycliste", "giro", "vuelta", "pogacar", "vingegaard", "peloton", "radsport")
        weak("sport.cyclisme", "tour de france", "etape")
        strong("sport.auto", "formule 1", "formula 1", "formel 1", "grand prix de", "motogp", "verstappen", "rallye", "24 heures du mans", "nascar")
        strong("sport.basket", "basket", "basketball", "nba", "wembanyama", "euroligue", "euroleague")
        strong("sport.autres", "handball", "athletisme", "biathlon", "judo", "natation", "ski alpin", "boxe", "jeux olympiques", "paralympiques", "footballamericain", "nfl", "super bowl")
        weak("sport", "match", "championnat", "victoire", "defaite", "finale", "demi finale", "qualification", "medaille", "selectionneur", "sportif")
        strong("jeux", "jeu video", "jeux video", "playstation", "xbox", "nintendo", "switch 2", "videogame", "video game", "steam deck")
        strong("tech", "intelligence artificielle", "openai", "chatgpt", "smartphone", "iphone", "android", "cybersecurite", "cyberattaque", "logiciel", "startup", "start up", "semi conducteurs", "puce", "nvidia", "microsoft", "google", "apple")
        strong("sciences", "nasa", "astronomie", "astronomes", "physiciens", "paleontologie", "fossile", "telescope", "exoplanete", "esa")
        weak("sciences", "chercheurs", "scientifiques", "etude publiee", "espace")
        strong("sante", "cancer", "vaccin", "epidemie", "hopital", "medecins", "maladie", "covid", "grippe", "alzheimer", "sante publique")
        strong("environnement", "rechauffement climatique", "changement climatique", "biodiversite", "pollution", "cop 30", "canicule", "secheresse", "emissions de co2")
        weak("environnement", "climat", "climatique")
        strong("economie", "inflation", "pib", "croissance economique", "bourse", "cac 40", "taux d interet", "banque centrale", "bce", "chomage", "licenciements", "faillite", "resultats financiers")
        weak("economie", "entreprise", "entreprises", "salaires", "budget", "impots")
        strong("politique", "assemblee nationale", "senat", "gouvernement", "premier ministre", "elysee", "motion de censure", "elections legislatives", "presidentielle", "parti socialiste", "rassemblement national", "la france insoumise")
        weak("politique", "ministre", "depute", "deputes", "election", "elections")
        strong("international", "guerre en ukraine", "ukraine", "gaza", "israel", "onu", "otan", "kremlin", "maison blanche", "pekin")
        weak("international", "diplomatie", "frappes")
        strong("culture", "festival de cannes", "cinema", "film", "roman", "album", "exposition", "musee", "concert", "serie netflix", "theatre")
        strong("people", "celebrite", "celebrites", "mariage princier", "famille royale", "tele realite")
    }

    // Short uppercase club names only count written in capitals ("OM", not "om").
    private val upperFootball = Regex("""(?<![\p{L}\d])(OM|OL|PSG|RCL|ASSE|OGC Nice)(?![\p{L}\d])""")

    fun classify(
        url: String?,
        categories: List<String>,
        title: String,
        titleFr: String? = null,
        excerpt: String? = null,
    ): List<String> {
        val score = HashMap<String, Int>()
        fun add(topic: String, points: Int) {
            score[topic] = (score[topic] ?: 0) + points
        }

        // 1. Site and section in the web address.
        val uri = runCatching { URI(url ?: "") }.getOrNull()
        val host = uri?.host?.lowercase()?.removePrefix("www.").orEmpty()
        sites.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.let { add(it.value, SECTION) }
        host.split('.').dropLast(2).forEach { label -> sectionOf(label)?.let { add(it, SECTION) } }
        val segments = uri?.path?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        // The last segment is usually the article's own name: words in it could mislead.
        val sectionSegments = if (segments.size > 1) segments.dropLast(1) else emptyList()
        for (seg in sectionSegments.take(4)) {
            val s = fold(seg).trim().replace(' ', '-')
            sectionOf(s)?.let { add(it, SECTION) }
        }

        // 2. Feed categories that are section names.
        for (c in categories.take(8)) {
            val f = fold(c).trim()
            sectionOf(f.replace(' ', '-'))?.let { add(it, SECTION) }
                ?: words[f]?.let { (t, w) -> add(t, maxOf(w, STRONG)) }
        }

        // 3. Words of the title (original and French) and the excerpt.
        val text = (" " + listOfNotNull(title, titleFr, excerpt?.take(300)).joinToString(" ") { fold(it) } + " ")
            .replace(" american football ", " footballamericain ")
            .replace(" football americain ", " footballamericain ")
        for ((w, tw) in words) {
            if (text.contains(" $w ")) add(tw.first, tw.second)
        }
        if (upperFootball.containsMatchIn(listOfNotNull(title, titleFr).joinToString(" "))) add("sport.football", STRONG)

        // A sport is a sport: sub-topics also count for "sport", generic sport words for nothing alone.
        val sportGeneric = score.remove("sport") ?: 0
        val sub = score.filterKeys { it.startsWith("sport.") }.maxByOrNull { it.value }
        if (sub != null && sub.value + sportGeneric >= THRESHOLD) {
            score.keys.removeAll { it.startsWith("sport.") && it != sub.key }
            score[sub.key] = sub.value + sportGeneric
        } else {
            score.keys.removeAll { it.startsWith("sport.") }
            if (sportGeneric >= THRESHOLD) score["sport"] = sportGeneric
        }
        return score.filterValues { it >= THRESHOLD }
            .entries.sortedByDescending { it.value }
            .take(2)
            .map { it.key }
    }

    private fun sectionOf(s: String): String? = sections[s] ?: sections[s.removeSuffix("s")]

    /** Lower case, no accents, "ß" → "ss", punctuation → spaces. */
    fun fold(s: String): String {
        val n = Normalizer.normalize(s.lowercase().replace("ß", "ss").replace('’', '\''), Normalizer.Form.NFD)
        val sb = StringBuilder(n.length)
        for (c in n) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c.isLetterOrDigit() -> sb.append(c)
                c == '-' -> sb.append('-')
                else -> sb.append(' ')
            }
        }
        // Hyphens inside words ("saint-germain") are spaces for phrases, kept for section names.
        return sb.toString().replace(Regex(" +"), " ").let { if (it.contains(' ')) it.replace('-', ' ') else it }
    }
}
