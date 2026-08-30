package com.neo.inatbox

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import org.json.JSONArray
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import android.util.Base64
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.util.Locale

class InatBox : MainAPI() {
    private val aesKey = "ywevqtjrurkwtqgz"
    private val contentUrl = resolveContentUrl()

    override var name = "InatBox"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override var sequentialMainPage = false
    override val getMainPageTimeoutMs = 25_000L

    private val urlToSearchResponse = mutableMapOf<String, SearchResponse>()

    companion object {
        private const val DOMAIN_DOCUMENT_URL =
            "https://raw.githubusercontent.com/mtlshash/cert/main/hash"
        private const val FALLBACK_CONTENT_URL = "https://static.staticsave.com/fast/ct.js"
    }

    override val mainPage = mainPageOf(contentUrl to "Ana İstek")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = loadCategoryLists(request.data)
        return if (lists.isEmpty()) {
            newHomePageResponse(request.name, emptyList())
        } else {
            newHomePageResponse(lists)
        }
    }

    private suspend fun loadCategoryLists(indexUrl: String): List<HomePageList> {
        val encryptedIndex = runCatching { app.get(indexUrl).body.string() }.getOrNull()
            ?: return emptyList()
        val decryptedIndex = getJsonFromEncryptedInatResponse(encryptedIndex)
            ?: return emptyList()

        val categories = runCatching { JSONArray(decryptedIndex) }.getOrNull()
            ?: return emptyList()

        val orderedCategories = (0 until categories.length())
            .mapNotNull { categories.optJSONObject(it) }
            .filter(::categoryAllowed)
            .sortedBy { categoryPriority(it.optString("catName")) }

        return coroutineScope {
            orderedCategories.map { category ->
                async(Dispatchers.IO) {
                    val categoryUrl = category.optString("catUrl")
                    if (categoryUrl.isBlank()) return@async null

                    val response = makeInatRequest(categoryUrl) ?: return@async null
                    val results = getSearchResponseList(response)
                    synchronized(urlToSearchResponse) {
                        results.forEach { urlToSearchResponse.putIfAbsent(it.url, it) }
                    }

                    val categoryName = category.optString("catName", "İsimsiz")
                    val categoryType = category.optString("catType").lowercase()
                    val isHorizontal = categoryType.contains("live") ||
                        categoryType.contains("iptv") ||
                        categoryType.contains("tv") ||
                        categoryName.lowercase().contains("canlı") ||
                        categoryName.lowercase().contains("spor")

                    HomePageList(categoryName, results, isHorizontal)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun categoryPriority(name: String): Int {
        val normalizedName = name.lowercase(Locale.forLanguageTag("tr"))
        return when {
            normalizedName.contains("spor") -> 0
            normalizedName.contains("ulusal") -> 1
            normalizedName.contains("sinema") -> 2
            normalizedName.contains("liste 1") && normalizedName.contains("tr") -> 3
            else -> 4
        }
    }

    private fun categoryAllowed(category: JSONObject): Boolean {
        val name = category.optString("catName")
        val type = category.optString("catType")
        val url = category.optString("catUrl")
        return type != "link" && type != "link_mode" && type != "destek" &&
            type != "destek_mode" && name != "Hata Bildir" && name != "Derbiler" &&
            !url.contains("4k-film-exo.php") && !url.contains("destek_mode") &&
            !url.contains("inattv") && !url.contains("x.com/")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (urlToSearchResponse.isEmpty()) {
            loadCategoryLists(contentUrl)
        }

        val matchingResults = mutableListOf<SearchResponse>()

        val regex = try {
            Regex(query, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        }

        for ((_, searchResponse) in urlToSearchResponse) {
            if (regex.containsMatchIn(searchResponse.name)) {
                matchingResults.add(searchResponse)
            }
        }

        return matchingResults.distinctBy { it.name }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val item = JSONObject(url)

        if (!inatContentAllowed(item)) {
            return null
        }

        if (item.has("diziType")) {
            item.getString("diziName")
            val type = item.getString("diziType")

            return when (type) {
                "dizi", "dizi_mode" -> parseTvSeriesResponse(item)
                "film", "film_mode" -> parseMovieResponse(item)
                else -> null
            }

        } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
            item.getString("chName")
            val chType = item.getString("chType")

            val loadResponse = when {
                chType.contains("SsprDrm", ignoreCase = true) -> parseSSportResponse(item)
                chType.contains("live") || chType.contains("cable") ->
                    parseLiveStreamLoadResponse(item)
                chType.contains("tekli") -> parseLiveSportsStreamLoadResponse(item)
                else -> parseMovieResponse(item)
            }
            return loadResponse
        } else {
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("InatBox", "data: $data")
        return try {
            if (data.startsWith("[")) {
                val chContentJsonArray = JSONArray(data)
                for (i in 0 until chContentJsonArray.length()) {
                    val chContentJsonObject = chContentJsonArray.getJSONObject(i)
                    val chContent = parseToChContent(chContentJsonObject)
                    loadChContentLinks(chContent, subtitleCallback, callback)
                }
            } else {
                val chContentJsonArray = JSONObject(data)
                val chContent = parseToChContent(chContentJsonArray)
                loadChContentLinks(chContent, subtitleCallback, callback)
            }
            true
        } catch (e: Exception) {
            Log.e("InatBox", "Error on loadLinks:${e::class.simpleName} - ${e.message}")
            false
        }
    }

    private suspend fun parseTvSeriesResponse(
        item: JSONObject,
        tvType: TvType = TvType.TvSeries
    ): LoadResponse? {
        val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()
        val seasonDataList = mutableListOf<SeasonData>()

        val name = item.getString("diziName")
        val url = item.getString("diziUrl")
        val plot = item.getString("diziDetay")

        val jsonResponse = makeInatRequest(url) ?: return null
        val jsonArray = JSONArray(jsonResponse)

        try {
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)
                val seasonName = seasonItem.getString("diziName")
                val seasonData = SeasonData(season = (i + 1), name = seasonName)
                seasonDataList.add(seasonData)

                val seasonUrl = seasonItem.getString("diziUrl")

                // Fetch the episode data for this season
                val episodeResponse = makeInatRequest(seasonUrl) ?: continue
                val episodeArray = try {
                    JSONArray(episodeResponse)
                } catch (e: Exception) {
                    Log.e("InatBox", "Failed to parse episode JSON for season: $seasonName", e)
                    continue
                }

                for (j in 0 until episodeArray.length()) {
                    try {
                        val episodeItem = episodeArray.getJSONObject(j)
                        val episodeName = episodeItem.getString("chName")
                        val episodePoster = episodeItem.getString("chImg")
                        episodes.getOrPut(DubStatus.None) { mutableListOf() }.add(
                            newEpisode(episodeItem.toString()) {
                                this.name = episodeName
                                this.posterUrl = episodePoster
                                this.season = i + 1
                                this.episode = j + 1
                            }
                        )
                    } catch (_: JSONException) {
                        continue
                    }
                }
            }

            // Get the poster URL from the first season
            val firstSeason = jsonArray.getJSONObject(0)
            val posterUrl = firstSeason.getString("diziImg")

            return newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = tvType,
                comingSoonIfNone = false
            ) {
                this.episodes = episodes.mapValues { it.value.toList() }.toMutableMap()
                this.posterUrl = posterUrl
                this.plot = plot
                this.seasonNames = seasonDataList
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Failed to parse TV series response: ${e.message}\nStacktrace:${
                    e.stackTrace.joinToString("\n")
                }"
            )
            return null
        }
    }

        private suspend fun parseSSportResponse(item: JSONObject): LoadResponse? {
        try {
            val name = item.optString("chName", "S Sport Plus")
            val posterUrl = item.optString("chImg", "")
            
            val rawResponse = app.get("https://sprspr.help/CDN/SSP/bir-p-no-cron.php", headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Safari/605.1.15",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "https://google.com/"
            )).body.string()
            
            val jsonResponse = JSONObject(rawResponse)
            val categories = jsonResponse.optJSONArray("Categories") ?: return null
            val firstCategory = categories.optJSONObject(0) ?: return null
            val contents = firstCategory.optJSONArray("Contents") ?: return null
            
            val episodes = mutableListOf<Episode>()
            for (i in 0 until contents.length()) {
                val content = contents.optJSONObject(i) ?: continue
                val epName = content.optString("Title", "")
                val description = content.optString("Description", "")
                
                val medias = content.optJSONArray("Medias")
                val mediaUrl = if (medias != null && medias.length() > 0) {
                    medias.optJSONObject(0)?.optString("URL", "") ?: ""
                } else ""
                
                if (mediaUrl.isNotEmpty()) {
                    // Create an Episode, setting data to mediaUrl (e.g. NONE/01)
                    val episode = newEpisode(mediaUrl) {
                        this.name = epName
                        this.description = description
                        this.episode = i + 1
                        this.posterUrl = posterUrl
                    }
                    episodes.add(episode)
                }
            }
            
            return newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = TvType.TvSeries
            ) {
                this.episodes = mutableMapOf(DubStatus.None to episodes)
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse SSport response: ${e.message}")
            return null
        }
    }


    private suspend fun parseMovieResponse(item: JSONObject): LoadResponse? {
        try {
            if (item.has("diziType")) {
                val name = item.getString("diziName")
                val url = item.getString("diziUrl")
                val posterUrl = item.getString("diziImg")
                val plot = item.getString("diziDetay")

                val jsonResponse = makeInatRequest(url) ?: return null
                val jsonArray = JSONArray(jsonResponse)

                return newMovieLoadResponse(
                    name = name,
                    url = item.toString(),
                    type = TvType.Movie,
                    dataUrl = jsonArray.toString()
                ) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                }
            } else {
                val name = item.getString("chName")
                item.getString("chUrl")
                val posterUrl = item.getString("chImg")
                return newMovieLoadResponse(name, item.toString(), TvType.Movie, item.toString()) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }

    private suspend fun parseLiveSportsStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        try {
            val chContent = parseToChContent(item)
            val chName = chContent.chName
            val posterUrl = chContent.chImg

            return newLiveStreamLoadResponse(chName, item.toString(), item.toString()) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse sports live stream response: ${e.message}")
            return null
        }
    }

    private suspend fun parseLiveStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        try {
            val chContent = parseToChContent(item)
            val name = chContent.chName
            val posterUrl = chContent.chImg

            return newLiveStreamLoadResponse(name, item.toString(), item.toString()) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }

    private fun inatContentAllowed(item: JSONObject): Boolean {
        val type: String = if (item.has("diziType")) {
            item.getString("diziType")
        } else {
            item.getString("chType")
        }

        return when (type) {
            "link", "web", "link_mode", "web_mode" -> false
            else -> true
        }
    }

    private fun String.vkSourceFix(): String {
        if (this.startsWith("act")) {
            return "https://vk.com/al_video.php?${this}"
        }
        return this
    }

    private fun parseToChContent(item: JSONObject): ChContent {
        return ChContent(
            chName = item.optString("chName"),
            chUrl = item.optString("chUrl").vkSourceFix(),
            chImg = item.optString("chImg"),
            chHeaders = item.opt("chHeaders")?.toString() ?: "null",
            chReg = item.opt("chReg")?.toString() ?: "null",
            chType = item.optString("chType")
        )
    }

    private suspend fun loadChContentLinks(
        chContent: ChContent,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val resolvedChContent = if (chContent.chUrl.startsWith("NONE/")) {
            val id = chContent.chUrl.substringAfter("NONE/")
            chContent.copy(chUrl = "https://sspplus.redzones.icu/CDN/SSP/txt/$id.m3u8")
        } else {
            chContent
        }

        val chType = resolvedChContent.chType
        val contentToProcess: ChContent

        if (chType.contains("tekli_regex_lb_sh_3") && !isDirectStream(resolvedChContent.chUrl)) {
            val name = resolvedChContent.chName
            val url = resolvedChContent.chUrl
            val posterUrl = resolvedChContent.chImg
            val headers = resolvedChContent.chHeaders
            val reg = resolvedChContent.chReg
            val type = resolvedChContent.chType

            val customKey = runCatching {
                if (reg == "null") aesKey else JSONArray(reg).getJSONObject(0)
                    .optString("Regex1", aesKey)
            }.getOrDefault(aesKey)

            val jsonResponse = makeInatRequestWithKey(url, customKey) ?: return

            val firstItem = JSONObject(jsonResponse)
            firstItem.put("chHeaders", headers)
            firstItem.put("chReg", reg)
            firstItem.put("chName", name)
            firstItem.put("chImg", posterUrl)
            firstItem.put("chType", type)
            contentToProcess = parseToChContent(firstItem)
        } else {
            contentToProcess = resolvedChContent
        }

        var sourceUrl = contentToProcess.chUrl

        // Headerları hazırlama kısmı
        val headers: MutableMap<String, String> = mutableMapOf()
        try {
            val chHeaders = contentToProcess.chHeaders
            val chReg = contentToProcess.chReg
            if (chHeaders != "null" && chHeaders.isNotBlank()) {
                val jsonHeaders = JSONArray(chHeaders).getJSONObject(0)
                for (entry in jsonHeaders.keys()) {
                    val keyName = when (entry) {
                        "UserAgent" -> "User-Agent"
                        "XRequestedWith" -> "X-Requested-With"
                        else -> entry
                    }
                    headers[keyName] = jsonHeaders[entry].toString()
                }
            }
            if (chReg != "null" && chReg.isNotBlank()) {
                val jsonReg = JSONArray(chReg).getJSONObject(0)
                if (jsonReg.has("playSH2")) {
                    val cookie = jsonReg.getString("playSH2")
                    headers["Cookie"] = cookie
                }
            }
        } catch (_: Exception) {
        }

        if (!headers.containsKey("Referer")) {
            headers["Referer"] = "https://google.com/"
        }
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0"
        }

        if (sourceUrl.contains("filmizleeeee")) {
            sourceUrl = resolveFilmizleStream(sourceUrl, headers) ?: sourceUrl
        }

        if (isDirectStream(sourceUrl)) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = contentToProcess.chName,
                    url = sourceUrl,
                    type = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else if (sourceUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
                ) {
                    this.referer = headers["Referer"].orEmpty()
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            loadExtractor(
                sourceUrl,
                headers["Referer"].orEmpty(),
                subtitleCallback,
                callback
            )
        }
    }

    private fun isDirectStream(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) ||
            url.contains(".mpd", ignoreCase = true) ||
            url.contains(".mp4", ignoreCase = true) ||
            url.contains(".webm", ignoreCase = true)

    private suspend fun resolveFilmizleStream(
        url: String,
        headers: Map<String, String>
    ): String? {
        var response = runCatching {
            app.get(url, headers = headers, referer = headers["Referer"]).body.string()
        }.getOrNull() ?: return null

        repeat(3) {
            val separator = response.lastIndexOf(':')
            if (separator <= 0) return@repeat

            val encrypted = response.substring(0, separator).trim()
            val encodedKey = response.substring(separator + 1).trim()
            val key = runCatching { String(Base64.decode(encodedKey, Base64.DEFAULT)) }
                .getOrNull() ?: return@repeat
            response = decryptAES(encrypted, key, decodeKeyAsBase64 = false) ?: return@repeat

            val json = runCatching { JSONObject(response.trim()) }.getOrNull()
            if (json != null && json.has("chUrl")) return json.optString("chUrl")
        }
        return null
    }

    private suspend fun makeInatRequest(url: String): String? {
        return makeInatRequestWithKey(url, aesKey)
    }

    private suspend fun makeInatRequestWithKey(
        url: String,
        customKey: String,
        retryCount: Int = 2
    ): String? {
        val hostName = try {
            URI(url).host ?: throw IllegalArgumentException("Invalid URL: $url")
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to extract hostname from URL: $url", e)
            return null
        }

        val headers = mapOf(
            "Cache-Control" to "no-cache",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Host" to hostName,
            "Referer" to "https://speedrestapi.com/",
            "X-Requested-With" to "com.bp.box"
        )

        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val newRequest = request.newBuilder().header("User-Agent", "speedrestapi").build()
            chain.proceed(newRequest)
        }

        repeat(retryCount) { attempt ->
            try {
                val response = if (url.contains("sprspr.help") || url.contains("/SPR/")) {
                    app.get(url = url, headers = headers, interceptor = interceptor)
                } else {
                    val requestBody = "1=$customKey&0=$customKey"
                    app.post(
                        url = url,
                        headers = headers,
                        requestBody = requestBody.toRequestBody(
                            contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                        ),
                        interceptor = interceptor
                    )
                }

                if (response.isSuccessful) {
                    val encryptedResponse = response.body.string()
                    getJsonFromEncryptedInatResponse(encryptedResponse, customKey)?.let { return it }
                }
            } catch (e: Exception) {
                if (attempt == retryCount - 1) {
                    Log.e("InatBox", "Request failed for $url: ${e.message}")
                }
            }
        }
        return null
    }

    private fun getJsonFromEncryptedInatResponse(
        response: String,
        customKey: String? = null
    ): String? {
        val defaultKey = customKey ?: aesKey
        return runCatching {
            val separator = response.indexOf(':')
            val encrypted = if (separator >= 0) response.substring(0, separator) else response
            val key = if (separator >= 0) response.substring(separator + 1).trim() else defaultKey
            val firstLayer = decryptAES(encrypted.trim(), key) ?: return null

            val innerSeparator = firstLayer.indexOf(':')
            if (innerSeparator >= 0) {
                decryptAES(
                    firstLayer.substring(0, innerSeparator).trim(),
                    firstLayer.substring(innerSeparator + 1).trim()
                )
            } else {
                firstLayer
            }
        }.getOrElse {
            Log.e("InatBox", "Decryption failed: ${it.message}")
            null
        }
    }

    private fun decryptAES(
        encryptedText: String,
        keyText: String,
        decodeKeyAsBase64: Boolean = true
    ): String? {
        val keyBytes = if (decodeKeyAsBase64) {
            runCatching { Base64.decode(keyText, Base64.DEFAULT) }
                .getOrNull()
                ?.takeIf { it.size == 16 || it.size == 24 || it.size == 32 }
                ?: keyText.toByteArray()
        } else {
            keyText.toByteArray()
        }
        if (keyBytes.size != 16 && keyBytes.size != 24 && keyBytes.size != 32) return null

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(keyBytes.copyOf(16)))
        return String(cipher.doFinal(Base64.decode(encryptedText, Base64.DEFAULT)))
    }

    private fun resolveContentUrl(): String = runBlocking {
        runCatching {
            val certificate = app.get(DOMAIN_DOCUMENT_URL).body.string()
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .trim()
            val outerSeparator = certificate.indexOf(':')
            require(outerSeparator > 0)

            val firstLayer = decryptAES(
                certificate.substring(0, outerSeparator).trim(),
                certificate.substring(outerSeparator + 1).trim()
            ) ?: error("Domain document first layer could not be decrypted")
            val innerSeparator = firstLayer.indexOf(':')
            require(innerSeparator > 0)

            val domainJson = decryptAES(
                firstLayer.substring(0, innerSeparator).trim(),
                firstLayer.substring(innerSeparator + 1).trim()
            ) ?: error("Domain document second layer could not be decrypted")
            JSONObject(domainJson).optString("DC10", FALLBACK_CONTENT_URL)
        }.getOrElse {
            Log.w("InatBox", "Dynamic domain lookup failed, using fallback: ${it.message}")
            FALLBACK_CONTENT_URL
        }
    }

    private fun getSearchResponseList(jsonResponse: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        try {
            val jsonArray = JSONArray(jsonResponse)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)

                if (!inatContentAllowed(item)) {
                    continue
                }

                //Let's pass item directly to the next step
                if (item.has("diziType")) {
                    val name = item.getString("diziName")
                    val type = item.getString("diziType")
                    val posterUrl = item.getString("diziImg")

                    val searchResponse = when (type) {
                        "dizi", "dizi_mode" -> newTvSeriesSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }

                        "film", "film_mode" -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }

                        else -> null // Ignore unsupported types
                    }
                    searchResponse?.let { searchResults.add(it) }
                } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
                    // Handle the case where diziType is missing but chName, chUrl, and chImg are present
                    val name = item.getString("chName")
                    val posterUrl = item.getString("chImg")
                    val chType = item.getString("chType")

                    val searchResponse = when (chType) {
                        "live_url", "live_url_mode", "tekli_regex_lb_sh_3", "tekli_regex_lb_sh_3_mode" -> newLiveSearchResponse(
                            name,
                            item.toString(),
                            TvType.Live
                        ) {
                            this.posterUrl = posterUrl
                        }

                        else -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
                    }
                    searchResults.add(searchResponse)
                }
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse JSON response: ${e.message}")
        }

        return searchResults
    }
}
