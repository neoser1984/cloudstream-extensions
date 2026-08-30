// ! Bu araç NeO tarafından yazılmıştır.
// ! Domain listesi: https://github.com/neoser1984/cloudstream-extensions/blob/main/domains.json
// !
// ! Nasıl çalışır?
// ! Bu eklenti her açıldığında repodaki domains.json dosyasını çalışma zamanında (runtime) indirir.
// ! Sen o dosyadaki adresi değiştirip GitHub'a push ettiğinde, eklentiyi YENİDEN DERLEMEDEN
// ! ve kullanıcıların eklentiyi güncellemesine gerek KALMADAN yeni adres otomatik kullanılır.
// ! (İnternet yoksa veya dosyaya ulaşılamazsa aşağıdaki FALLBACK adres kullanılır.)

package com.neo.hdfilmizle

import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking

object RemoteConfig {
    private const val DOMAINS_URL = "https://raw.githubusercontent.com/neoser1984/cloudstream-extensions/main/domains.json"

    private var cache: Map<String, String>? = null

    private fun fetch(): Map<String, String> {
        cache?.let { return it }

        return try {
            val json = runBlocking { app.get(DOMAINS_URL, timeout = 8_000L).text }
            val map  = jacksonObjectMapper().readValue<Map<String, String>>(json)
            if (map.isNotEmpty()) cache = map
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * @param key      domains.json içindeki anahtar (örn: "hdfilmizle")
     * @param fallback domains.json'a ulaşılamazsa kullanılacak varsayılan adres
     */
    fun getDomain(key: String, fallback: String): String {
        val remote = fetch()[key]?.trim()?.trimEnd('/')
        return if (!remote.isNullOrBlank()) remote else fallback
    }
}
