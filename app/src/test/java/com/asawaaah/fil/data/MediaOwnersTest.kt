package com.asawaaah.fil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaOwnersTest {
    private val sample = """
        # comment
        M	Le Monde	P:Xavier Niel|P:Madison Cox	Xavier Niel › (53 %) Le Monde libre › Le Monde	lemonde.fr	mdiplo
        M	BFM TV	P:Rodolphe Saadé	Rodolphe Saadé › (73 %) CMA CGM › (100 %) BFM TV	bfmtv.com	mdiplo
        M	RMC	O:CMA CGM		rmc.bfmtv.com	mdiplo
        M	vieux.fr	?:Quelqu'un		vieux.fr	qpm2021
    """.trimIndent()

    private val owners = MediaOwners.parse(sample)

    @Test
    fun findsBySiteOrFeedAddress() {
        assertEquals("Le Monde", owners.lookup("https://www.lemonde.fr/rss/une.xml")?.media)
        assertEquals("Le Monde", owners.lookup(null, "https://lemonde.fr")?.media)
        assertNull(owners.lookup("https://www.reddit.com/r/android/.rss"))
    }

    @Test
    fun subdomainWinsOverItsParent() {
        assertEquals("RMC", owners.lookup("https://rmc.bfmtv.com/actualites/")?.media)
        assertEquals("BFM TV", owners.lookup("https://www.bfmtv.com/rss/")?.media)
        assertEquals("BFM TV", owners.lookup("https://video.bfmtv.com/")?.media)
    }

    @Test
    fun ownersAndLabels() {
        val m = owners.lookup("lemonde.fr".let { "https://$it" })!!
        assertEquals("Xavier Niel +1", m.shortLabel)
        assertTrue(m.owners.all { it.isPerson })
        assertTrue(m.recent)
        assertFalse(owners.lookup("https://vieux.fr")!!.recent)
        assertEquals(1, m.chains.size)
    }

    @Test
    fun bundledDataParsesAndKnowsTheBigMedia() {
        val file = File("src/main/assets/media_owners.tsv")
        if (!file.exists()) return
        val real = MediaOwners.parse(file.readText())
        for (site in listOf("https://www.lemonde.fr", "https://www.lefigaro.fr", "https://www.liberation.fr", "https://www.bfmtv.com")) {
            assertNotNull(site, real.lookup(site))
        }
    }
}
