// ! Bu araç NeO tarafından yazılmıştır.
// !
// ! Bu eklenti kod içinde site kazımıyor (scrape) — repodaki CanliKanallar.m3u dosyasını
// ! (standart IPTV M3U/M3U8 playlist formatı) çalışma zamanında indirip parse ediyor ve
// ! her kanalı CloudStream'in "Live" (canlı TV) tipinde listeliyor.
// !
// ! Playlist adresi domains.json içindeki "canlikanallar" anahtarından okunur (bkz. RemoteConfig.kt),
// ! yani kanal listesini güncellemek için eklentiyi yeniden derlemeye gerek yok:
// ! CanliKanallar.m3u dosyasını güncelleyip push etmen yeterli.

package com.neo.canlikanallar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String
)

class CanliKanallar : MainAPI() {
    override var mainUrl        = "https://raw.githubusercontent.com/neoser1984/cloudstream-extensions/main"
    override var name           = "Canlı Kanallar"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasDownloadSupport = false
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val fallbackPlaylistUrl =
        "https://raw.githubusercontent.com/neoser1984/cloudstream-extensions/main/CanliKanallar.m3u"

    private var cachedChannels: List<Channel>? = null

    override val mainPage = mainPageOf(
        "all" to "Canlı Kanallar",
    )

    private suspend fun loadChannels(): List<Channel> {
        cachedChannels?.let { return it }

        val playlistUrl = RemoteConfig.getDomain("canlikanallar", fallbackPlaylistUrl)
        val text = try {
            app.get(playlistUrl, timeout = 20_000L).text
        } catch (e: Exception) {
            return cachedChannels ?: emptyList()
        }

        val logoRegex  = Regex("""tvg-logo="([^"]*)"""")
        val groupRegex = Regex("""group-title="([^"]*)"""")

        val channels = mutableListOf<Channel>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                val chName = line.substringAfterLast(",").trim()
                val logo   = logoRegex.find(line)?.groupValues?.get(1)?.ifBlank { null }
                val group  = groupRegex.find(line)?.groupValues?.get(1)?.ifBlank { null } ?: "Diğer"

                var j = i + 1
                while (j < lines.size && lines[j].trim().startsWith("#")) j++

                val streamUrl = if (j < lines.size) lines[j].trim() else null
                if (!streamUrl.isNullOrBlank() && chName.isNotBlank()) {
                    channels.add(Channel(chName, streamUrl, logo, group))
                }
                i = j + 1
            } else {
                i++
            }
        }

        if (channels.isNotEmpty()) cachedChannels = channels
        return channels
    }

    private fun Channel.toSearchResponse(): LiveSearchResponse {
        return newLiveSearchResponse(this.name, this.url, TvType.Live) {
            this.posterUrl = this@toSearchResponse.logo
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = loadChannels()
        val grouped = channels.groupBy { it.group }

        val homePageLists = grouped.map { (group, list) ->
            HomePageList(
                group,
                list.map { it.toSearchResponse() },
                isHorizontalImages = false
            )
        }

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val channels = loadChannels()
        return channels
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val channels = loadChannels()
        val channel  = channels.firstOrNull { it.url == url }

        return newLiveStreamLoadResponse(
            channel?.name ?: this.name,
            url,
            url
        ) {
            this.posterUrl = channel?.logo
            this.plot      = channel?.group?.let { "Kategori: $it" }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = data,
                type   = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
