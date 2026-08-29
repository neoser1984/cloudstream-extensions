# NeO CloudStream Eklentileri

Kişisel CloudStream eklenti deposu. Şu an 3 eklenti içerir:

| Eklenti | Site | Tür |
|---|---|---|
| **DiziPal 2121** | dizipal2121.com | Dizi + Film |
| **DiziPal 1578** | dizipal1578.com | Dizi + Film |
| **SelcukFlix** | selcukflix.co | Dizi + Film |

Hepsi **reklamsız** oynatma yapar: eklentiler siteye özgü oynatıcı arayüzünü (reklamlı iframe/JS) hiç yüklemeden, kaynak videonun m3u8/stream linkini bulup CloudStream'in kendi oynatıcısına verir.

## 🔑 En önemli özellik: adresler tek bir dosyadan yönetiliyor

Bu sitelerin adresleri zaman zaman değişiyor (`dizipal2121.com` → `dizipal2199.com` gibi). Bu adresleri eklentilerin içine gömmek yerine kök dizindeki **[`domains.json`](./domains.json)** dosyasında tutuyoruz:

```json
{
    "dizipal2121": "https://dizipal2121.com",
    "dizipal1578": "https://dizipal1578.com",
    "selcukflix":  "https://selcukflix.co"
}
```

Her eklenti açıldığında bu dosyayı GitHub üzerinden (`raw.githubusercontent.com`) **çalışma zamanında** indirir ve okur. Yani:

1. Bir sitenin adresi değiştiğinde tek yapman gereken `domains.json` içindeki ilgili satırı güncelleyip GitHub'a **push** etmek.
2. Kullanıcıların eklentiyi güncellemesine, senin eklentiyi yeniden derleyip yayınlamana **gerek yok** — herkesin telefonundaki eklenti bir sonraki açılışta yeni adresi otomatik kullanır.
3. `domains.json`'a internet yokken ya da dosyaya bir sebeple ulaşılamazsa, eklenti kod içindeki sabit (fallback) adresi kullanır, çökmez.

Bu mantık her eklentinin kendi paketindeki `RemoteConfig.kt` dosyasında uygulanır (`DiziPal2121/…/RemoteConfig.kt`, `DiziPal1578/…/RemoteConfig.kt`, `SelcukFlix/…/RemoteConfig.kt`).

## ⚙️ Kendi reponu kurarken yapman gerekenler

Bu depo `neoser1984/cloudstream-extensions` adına göre hazırlandı. Farklı bir kullanıcı adı/repo adı kullanacaksan **aşağıdaki 3 yeri** değiştir (hepsi aynı adresi kullanıyor: `https://raw.githubusercontent.com/neoser1984/cloudstream-extensions/main/domains.json`):

- `DiziPal2121/src/main/kotlin/com/neo/dizipal2121/RemoteConfig.kt` → `DOMAINS_URL`
- `DiziPal1578/src/main/kotlin/com/neo/dizipal1578/RemoteConfig.kt` → `DOMAINS_URL`
- `SelcukFlix/src/main/kotlin/com/neo/selcukflix/RemoteConfig.kt` → `DOMAINS_URL`

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
domains.json                 ← SEN BURAYI GÜNCELLERSİN
repo.json                    ← CloudStream'in okuduğu depo tanımı
build.gradle.kts             ← ortak derleme ayarları
settings.gradle.kts          ← hangi klasörlerin eklenti olduğunu bulur
.github/workflows/Derleyici.yml  ← push'ta otomatik derleme
DiziPal2121/                 ← dizipal2121.com eklentisi
DiziPal1578/                 ← dizipal1578.com eklentisi
SelcukFlix/                  ← selcukflix.co eklentisi
```

## 🧩 SelcukFlix nasıl çalışıyor? (teknik not)

SelcukFlix, sayfa verisini (dizi/film detayı, bölüm listesi, oynatıcı kaynağı, arama sonuçları) tarayıcıda çözülen **AES-256-CBC** ile şifreli gönderiyor. Bu eklenti şifreyi doğrudan çözüp (`SelcukCrypto.kt`) temiz JSON üzerinden çalışıyor — bu yüzden site tasarımını/CSS sınıflarını değiştirse bile kırılma ihtimali normal bir "class scraping" eklentisine göre daha düşük.

Video linkleri, sitenin kullandığı `pichive.online` kaynağından çekiliyor. Bu ara sunucu tarafı zamanla değişebilir; öyle bir durumda sadece `SelcukFlix.kt` içindeki `loadLinks` / `extractFromIframe` fonksiyonlarının küçük bir bakıma ihtiyacı olur, `domains.json` ile bir ilgisi yoktur.

## ⚠️ Not

Bu eklentiler herkese açık web sitelerini kazır (scrape); siteler yapılarını değiştirdikçe küçük bakımlar gerekebilir (tıpkı [Kekik-cloudstream](https://github.com/keyiflerolsun/Kekik-cloudstream) gibi tüm benzer eklenti depolarında olduğu gibi). Kişisel kullanım içindir.
