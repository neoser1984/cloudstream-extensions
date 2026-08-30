# NeO CloudStream Eklentileri

Kişisel CloudStream eklenti deposu. Şu an **22 eklenti** içerir:

| Eklenti | Site | Tür |
|---|---|---|
| **AsyaWatch** | asyawatch.com | Dizi + Film |
| **DDizi** | ddizi.im | Dizi |
| **DiziAsya** | diziasya.com | Dizi + Film + Anime |
| **DiziGom** | dizigom.biz | Dizi |
| **DiziKorea** | dizikorea3.com | Dizi + Film |
| **DiziMom** | dizimom.food | Dizi |
| **DiziPal 1578** | dizipal1578.com | Dizi + Film |
| **DiziPal 2121** | dizipal2121.com | Dizi + Film |
| **Diziyo** | diziyo.so | Dizi + Film |
| **DiziYou** | diziyou.one | Dizi |
| **FilmMakinesi** | filmmakinesi.to | Film |
| **Filmzal** | filmzal.me | Dizi + Film |
| **FullHDFilmizlesene** | fullhdfilmizlesene.now | Film |
| **HDFilmCehennemi** | hdfilmcehennemi.nl | Dizi + Film |
| **HDFilmDiziIzle** | hdfilmdiziizle.com | Dizi + Film |
| **HDFilmizle** | hdfilmizle.vip | Dizi + Film |
| **KoreFilmizle** | korefilmizle.com | Dizi + Film |
| **SelcukFlix** | selcukflix.co | Dizi + Film |
| **TrDiziIzle** | trdiziizle.tv | Dizi |
| **TvDiziler** | tvdiziler.tv | Dizi |
| **WebDramaTurkey** | webdramaturkey2.com | Dizi + Film |
| **YabancıDizi** | yabancidizi.news | Dizi + Film |

Çoğu **reklamsız** oynatma yapar: eklentiler siteye özgü oynatıcı arayüzünü (reklamlı iframe/JS) hiç yüklemeden, kaynak videonun m3u8/stream linkini bulup CloudStream'in kendi oynatıcısına verir. İki istisna aşağıdaki "Bilinen kısıtlar" bölümünde açıklanıyor.

## 🔑 En önemli özellik: adresler tek bir dosyadan yönetiliyor

Bu sitelerin adresleri zaman zaman değişiyor (`dizipal2121.com` → `dizipal2199.com` gibi). Bu adresleri eklentilerin içine gömmek yerine kök dizindeki **[`domains.json`](./domains.json)** dosyasında tutuyoruz:

```json
{
    "dizipal2121": "https://dizipal2121.com",
    "dizipal1578": "https://dizipal1578.com",
    "selcukflix": "https://selcukflix.co",
    "filmmakinesi": "https://filmmakinesi.to",
    "hdfilmcehennemi": "https://www.hdfilmcehennemi.nl",
    "dizimom": "https://www.dizimom.food",
    "fullhdfilmizlesene": "https://www.fullhdfilmizlesene.now",
    "diziyou": "https://www.diziyou.one",
    "dizikorea3": "https://dizikorea3.com",
    "ddizi": "https://www.ddizi.im",
    "tvdiziler": "https://tvdiziler.tv",
    "hdfilmizle": "https://www.hdfilmizle.vip",
    "trdiziizle": "https://www.trdiziizle.tv",
    "hdfilmdiziizle": "https://www.hdfilmdiziizle.com",
    "diziyo": "https://www.diziyo.so",
    "yabancidizi": "https://yabancidizi.news",
    "filmzal": "https://filmzal.me",
    "dizigom": "https://www.dizigom.biz",
    "korefilmizle": "https://korefilmizle.com",
    "webdramaturkey": "https://webdramaturkey2.com",
    "diziasya": "https://diziasya.com",
    "asyawatch": "https://asyawatch.com"
}
```

Her eklenti açıldığında bu dosyayı GitHub üzerinden (`raw.githubusercontent.com`) **çalışma zamanında** indirir ve okur. Yani:

1. Bir sitenin adresi değiştiğinde tek yapman gereken `domains.json` içindeki ilgili satırı güncelleyip GitHub'a **push** etmek.
2. Kullanıcıların eklentiyi güncellemesine, senin eklentiyi yeniden derleyip yayınlamana **gerek yok** — herkesin telefonundaki eklenti bir sonraki açılışta yeni adresi otomatik kullanır.
3. `domains.json`'a internet yokken ya da dosyaya bir sebeple ulaşılamazsa, eklenti kod içindeki sabit (fallback) adresi kullanır, çökmez.

Bu mantık her eklentinin kendi paketindeki `RemoteConfig.kt` dosyasında uygulanır — hepsi aynı boilerplate'i kullanır: `<Eklenti>/src/main/kotlin/com/neo/<isim>/RemoteConfig.kt`.

## ⚙️ Kendi reponu kurarken yapman gerekenler

Bu depo `neoser1984/cloudstream-extensions` adına göre hazırlandı. Farklı bir kullanıcı adı/repo adı kullanacaksan, **her eklentinin** `RemoteConfig.kt` dosyasındaki `DOMAINS_URL` sabitini güncellemen gerekir (hepsi aynı adresi kullanıyor: `https://raw.githubusercontent.com/neoser1984/cloudstream-extensions/main/domains.json`).

İstersen `repo.json` ve kök `build.gradle.kts` içindeki `neoser1984/cloudstream-extensions` referanslarını da güncelle (zorunlu değil, sadece kozmetik/CI ayarları için).

### GitHub reposunu ilk kez kurarken

1. Bu klasörü GitHub'da yeni bir repoya push et (public olmalı, CloudStream repo linkinin herkese açık olması gerekiyor).
2. Repo içinde **boş bir `builds` branch'i** oluştur — derlenen `.cs3` dosyaları ve `plugins.json` oraya yazılır:
```bash
git checkout --orphan builds
git rm -rf .
git commit --allow-empty -m "ilk kurulum"
git push origin builds
git checkout main
```
3. Repo Settings → Actions → General → "Workflow permissions" kısmından **Read and write permissions** seç (Actions'ın `builds` branch'ine push yapabilmesi için).
4. `main` branch'ine her push'ta `.github/workflows/Derleyici.yml` otomatik çalışıp eklentileri derler ve `builds` branch'ine yükler (sadece `domains.json` veya `.md` dosyaları değiştiğinde **çalışmaz** — zaten onlar için derlemeye gerek yok).

## 📲 CloudStream'e ekleme

CloudStream → Ayarlar → Eklentiler → Depo Ekle:

```
https://raw.githubusercontent.com/neoser1984/cloudstream-extensions/main/repo.json
```

(Kendi kullanıcı adınla değiştirmeyi unutma.)

## 🗂️ Proje yapısı

```
domains.json                    ← SEN BURAYI GÜNCELLERSİN
repo.json                       ← CloudStream'in okuduğu depo tanımı
build.gradle.kts                ← ortak derleme ayarları
settings.gradle.kts             ← hangi klasörlerin eklenti olduğunu bulur
.github/workflows/Derleyici.yml ← push'ta otomatik derleme
<Eklenti>/                      ← her site için ayrı bir klasör (yukarıdaki tablo)
    build.gradle.kts            ← eklenti meta verisi (isim, açıklama, tür)
    src/main/AndroidManifest.xml
    src/main/kotlin/com/neo/<isim>/
        RemoteConfig.kt         ← domains.json'ı okuyan ortak kod
        <Eklenti>.kt            ← asıl scraping mantığı (MainAPI)
        <Eklenti>Plugin.kt      ← CloudStream plugin giriş noktası
```

## 🧩 Bazı eklentiler nasıl çalışıyor? (teknik notlar)

**SelcukFlix** — sayfa verisini (dizi/film detayı, bölüm listesi, oynatıcı kaynağı, arama sonuçları) tarayıcıda çözülen **AES-256-CBC** ile şifreli gönderiyor. Bu eklenti şifreyi doğrudan çözüp (`SelcukCrypto.kt`) temiz JSON üzerinden çalışıyor — bu yüzden site tasarımını/CSS sınıflarını değiştirse bile kırılma ihtimali normal bir "class scraping" eklentisine göre daha düşük.

**DiziAsya** — Next.js (App Router, sunucu tarafında render) üzerine kurulu; sayfa HTML'inde gömülü JSON içinde film/dizi sayfalarındaki `ok.ru` ve `vidmoly.org` gibi yaygın video adreslerini doğrudan bulup CloudStream'in yerleşik extractor'larına veriyor.

**AsyaWatch** — her sayfanın `__NEXT_DATA__` script etiketinde base64 ile kodlanmış tam bir JSON veri yapısı bulunuyor (meta veri, sezon/bölüm listesi, video kaynakları dahil); eklenti ayrı bir kazıma yapmadan bunu çözüp okuyor. Ayrıca ana sayfa/liste sayfaları için sitenin kendi dahili JSON API'sini (`/api/bg/findSeries`, `/api/bg/findMovies`, `/api/bg/searchContent`) kullanıyor.

## ⚠️ Bilinen kısıtlar

- **WebDramaTurkey**: video oynatıcı adresi sitede JavaScript ile istemci tarafında üretiliyor (statik HTML'de bulunmuyor), bu yüzden video linki genelde bulunamıyor. Dizi/film gezinme, arama ve bölüm listesi normal çalışıyor.
- **AsyaWatch**: video kaynağı sitenin kendi barındırdığı, Cloudflare korumalı bir adrese işaret ediyor; ağ koşullarına göre video her zaman açılmayabilir.

## ⚠️ Not

Bu eklentiler herkese açık web sitelerini kazır (scrape); siteler yapılarını değiştirdikçe küçük bakımlar gerekebilir. Kişisel kullanım içindir.
