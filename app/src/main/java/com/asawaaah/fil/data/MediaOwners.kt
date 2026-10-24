package com.asawaaah.fil.data

import android.content.Context
import com.asawaaah.fil.data.feeds.Urls

/** A final owner of a media: a person, a company or a public body. */
data class Owner(val name: String, val isPerson: Boolean)

/**
 * Who owns a French media, from the data bundled with the app (built by tools/build-owners.mjs).
 * [chains] show how: "Xavier Niel › (100 %) NJJ › (53 %) Le Monde libre › … › Le Monde".
 * [recent] is false for media only known from the older 2021 list.
 */
data class MediaOwnership(
    val media: String,
    val owners: List<Owner>,
    val chains: List<String>,
    val recent: Boolean,
) {
    /** "Xavier Niel +1" */
    val shortLabel: String
        get() = owners.first().name + if (owners.size > 1) " +${owners.size - 1}" else ""
}

/** Lookup of a source's owners by its web address. Nothing is fetched: the data ships with the app. */
class MediaOwners(private val byDomain: Map<String, MediaOwnership>) {

    fun lookup(vararg urls: String?): MediaOwnership? {
        for (url in urls) {
            var host = Urls.host(url)?.lowercase() ?: continue
            // "rmc.bfmtv.com" first, then "bfmtv.com".
            while (true) {
                byDomain[host]?.let { return it }
                val dot = host.indexOf('.')
                if (dot < 0 || host.indexOf('.', dot + 1) < 0) break
                host = host.substring(dot + 1)
            }
        }
        return null
    }

    companion object {
        const val ATTRIBUTION =
            "Données : « Médias français, qui possède quoi », Le Monde diplomatique et Acrimed (licence ODC-By), " +
                "et « Qui possède les médias », Design & Human."

        fun parse(text: String): MediaOwners {
            val map = HashMap<String, MediaOwnership>()
            for (line in text.lineSequence()) {
                if (!line.startsWith("M\t")) continue
                val f = line.split('\t')
                if (f.size < 6) continue
                val owners = f[2].split('|').filter { it.isNotBlank() }.map {
                    Owner(it.substringAfter(':').trim(), isPerson = it.startsWith("P:"))
                }
                if (owners.isEmpty()) continue
                val ownership = MediaOwnership(
                    media = f[1],
                    owners = owners,
                    chains = f[3].split('|').filter { it.isNotBlank() },
                    recent = f[5].trim() == "mdiplo",
                )
                f[4].split(',').map { it.trim().lowercase().removePrefix("www.") }.filter { it.isNotEmpty() }
                    .forEach { map.putIfAbsent(it, ownership) }
            }
            return MediaOwners(map)
        }

        fun load(context: Context): MediaOwners = runCatching {
            context.assets.open("media_owners.tsv").bufferedReader().use { parse(it.readText()) }
        }.getOrElse { MediaOwners(emptyMap()) }
    }
}
