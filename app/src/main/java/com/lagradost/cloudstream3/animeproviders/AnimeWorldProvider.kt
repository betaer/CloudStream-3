package com.lagradost.cloudstream3.animeproviders

import java.util.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class AnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://www.animeworld.tv"
    override var name = "AnimeWorld"
    override val lang = "it"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String?): TvType {
            return when (t?.lowercase()) {
                "movie" -> TvType.AnimeMovie
                "ova" -> TvType.OVA
                else -> TvType.Anime
            }
        }
        fun getStatus(t: String?): ShowStatus? {
            return when (t?.lowercase()) {
                "finito" -> ShowStatus.Completed
                "in corso" -> ShowStatus.Ongoing
                else -> null
            }
        }
    }

    private fun Element.toSearchResult(showEpisode: Boolean = true): AnimeSearchResponse {
        fun String.parseHref(): String {
            val h = this.split('.').toMutableList()
            h[1] = h[1].substringBeforeLast('/')
            return h.joinToString(".")
        }

        val title = this.select("a.name").text().removeSuffix(" (ITA)")
        val otherTitle = this.select("a.name").attr("data-jtitle").removeSuffix(" (ITA)")
        val url = fixUrl(this.select("a.name").attr("href").parseHref())
        val poster = this.select("a.poster img").attr("src")

        val statusElement = this.select("div.status") // .first()
        val dub = statusElement.select(".dub").isNotEmpty()
        val dubStatus = if (dub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
        val episode = statusElement.select(".ep").text().split(' ').last().toIntOrNull()
        val type = when {
            statusElement.select(".movie").isNotEmpty() -> TvType.AnimeMovie
            statusElement.select(".ova").isNotEmpty() -> TvType.OVA
            else -> TvType.Anime
        }

        return AnimeSearchResponse(
            title,
            url,
            name,
            type,
            poster,
            dubStatus = dubStatus,
            otherName = if (otherTitle != title) otherTitle else null,
            dubEpisodes = if (showEpisode && type != TvType.AnimeMovie && dub) episode else null,
            subEpisodes = if (showEpisode && type != TvType.AnimeMovie && !dub) episode else null
        )
    }

    override suspend fun getMainPage(): HomePageResponse {
        val document = app.get(mainUrl).document
        val list = ArrayList<HomePageList>()

        val widget = document.select(".widget.hotnew")
        widget.select(".tabs [data-name=\"sub\"], .tabs [data-name=\"dub\"]").forEach { tab ->
            val tabId = tab.attr("data-name")
            val tabName = tab.text().removeSuffix("-ITA")
            val animeList = widget.select("[data-name=\"$tabId\"] .film-list .item").map {
                it.toSearchResult()
            }
            list.add(HomePageList(tabName, animeList))
        }
        widget.select(".tabs [data-name=\"trending\"]").forEach { tab ->
            val tabId = tab.attr("data-name")
            val tabName = tab.text()
            val animeList = widget.select("[data-name=\"$tabId\"] .film-list .item").map {
                it.toSearchResult(showEpisode = false)
            }.distinctBy { it.url }
            list.add(HomePageList(tabName, animeList))
        }
        return HomePageResponse(list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?keyword=$query").document
        return document.select(".film-list > .item").map {
            it.toSearchResult(showEpisode = false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        fun String.parseDuration(): Int? {
            val arr = this.split(" e ")
            return if (arr.size == 1)
                arr[0].split(' ')[0].toIntOrNull()
            else
                arr[1].split(' ')[0].toIntOrNull()?.let {
                    arr[0].removeSuffix("h").toIntOrNull()?.times(60)!!.plus(it) }
        }
        val document = app.get(url).document

        val widget = document.select("div.widget.info")
        val title = widget.select(".info .title").text().removeSuffix(" (ITA)")
        val otherTitle = widget.select(".info .title").attr("data-jtitle").removeSuffix(" (ITA)")
        val description = widget.select(".desc .long").first()?.text() ?: widget.select(".desc").text()
        val poster = document.select(".thumb img").attr("src")

        val type: TvType = getType(widget.select("dd").first()?.text())
        val genres = widget.select(".meta").select("a[href*=\"/genre/\"]").map { it.text() }
        val rating: Int? = widget.select("#average-vote").text().toFloatOrNull()?.times(1000)?.toInt()

        val trailerUrl = document.select(".trailer[data-url]").attr("data-url")
        val malId = document.select("#mal-button").attr("href")
            .split('/').last().toIntOrNull()
        val anlId = document.select("#anilist-button").attr("href")
            .split('/').last().toIntOrNull()

        var dub = false
        var year: Int? = null
        var status: ShowStatus? = null
        var duration: Int? = null

        for (meta in document.select(".meta dt, .meta dd")) {
            val text = meta.text()
            if (text.contains("Audio"))
                dub = meta.nextElementSibling()?.text() == "Italiano"
            else if (year == null && text.contains("Data"))
                year = meta.nextElementSibling()?.text()?.split(' ')?.last()?.toIntOrNull()
            else if (status == null && text.contains("Stato"))
                status = getStatus(meta.nextElementSibling()?.text())
            else if (status == null && text.contains("Durata"))
                duration = meta.nextElementSibling()?.text()?.parseDuration()
        }

        val servers = document.select(".widget.servers")
        val episodes = servers.select(".server[data-name=\"9\"] .episode").map {
            val id = it.select("a").attr("data-id")
            val number = it.select("a").attr("data-episode-num").toIntOrNull()
            AnimeEpisode(
                fixUrl("$mainUrl/api/episode/info?id=$id"),
                episode = number
            )
        }

        val recommendations = document.select(".film-list.interesting .item").map {
            it.toSearchResult(showEpisode = false)
        }

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            japName = otherTitle
            posterUrl = poster
            this.year = year
            addEpisodes(if (dub) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            tags = genres
            this.malId = malId
            this.anilistId = anlId
            this.rating = rating
            this.duration = duration
            this.trailerUrl = trailerUrl
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = JSONObject(
            app.get(data).text
        ).getString("grabber")
        callback.invoke(
            ExtractorLink(
                name,
                name,
                url,
                referer = mainUrl,
                quality = Qualities.Unknown.value
            )
        )
        return true
    }
}
