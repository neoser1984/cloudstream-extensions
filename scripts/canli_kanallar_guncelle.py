#!/usr/bin/env python3
"""
CanliKanallar.m3u otomatik güncelleme betiği.

https://onureroz.com/indirmeler/turk/index.m3u adresindeki güncel M3U listesini indirir,
depodaki CanliKanallar.m3u ile kanal adına göre birleştirir (aynı kanal her iki listede de
varsa onureroz.com'daki en güncel link/kayıt kazanır, sadece bizim listemizde olan kanallar
olduğu gibi korunur) ve tekilleştirilmiş sonucu tekrar CanliKanallar.m3u'ya yazar.

.github/workflows/CanliKanallarGuncelle.yml içindeki zamanlanmış (cron) iş tarafından
periyodik olarak çalıştırılır. Kaynağa ulaşılamazsa ya da kaynaktan hiç kanal
çıkarılamazsa dosyaya DOKUNMADAN sessizce çıkar (mevcut liste korunur).
"""
import re
import sys
import urllib.error
import urllib.request

SOURCE_URL = "https://onureroz.com/indirmeler/turk/index.m3u"
TARGET_FILE = "CanliKanallar.m3u"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
)


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", errors="ignore")


def parse_m3u(text: str):
    entries = []
    lines = text.splitlines()
    i, n = 0, len(lines)
    while i < n:
        line = lines[i]
        if line.startswith("#EXTINF"):
            extinf = line
            i += 1
            extra = []
            while i < n and lines[i].startswith("#") and not lines[i].startswith("#EXTINF"):
                extra.append(lines[i])
                i += 1
            while i < n and lines[i].strip() == "":
                i += 1
            if i < n and not lines[i].startswith("#"):
                url = lines[i].strip()
                entries.append((extinf, extra, url))
                i += 1
            else:
                continue
        else:
            i += 1
    return entries


def get_name(extinf: str) -> str:
    return extinf.rsplit(",", 1)[-1].strip()


def norm_key(name: str) -> str:
    key = name.upper()
    key = re.sub(r"\(.*?\)", "", key)
    key = re.sub(r"\[.*?\]", "", key)
    key = re.sub(r"[^A-Z0-9]", "", key)
    return key


def main() -> int:
    try:
        with open(TARGET_FILE, encoding="utf-8") as f:
            current_text = f.read()
    except FileNotFoundError:
        current_text = "#EXTM3U\n"

    current_entries = parse_m3u(current_text)

    try:
        remote_text = fetch(SOURCE_URL)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"UYARI: kaynak indirilemedi, mevcut dosya korunuyor: {e}", file=sys.stderr)
        return 0

    remote_entries = parse_m3u(remote_text)
    if not remote_entries:
        print("UYARI: kaynaktan hiç kanal parse edilemedi, mevcut dosya korunuyor.", file=sys.stderr)
        return 0

    merged = {}
    order = []

    # 1) Önce mevcut CanliKanallar.m3u'daki kanallar (elle eklenmiş olanlar dahil) tabana konur.
    for extinf, extra, url in current_entries:
        name = get_name(extinf)
        key = norm_key(name)
        if not key:
            continue
        if key not in merged:
            order.append(key)
        merged[key] = (extinf, extra, url, name)

    # 2) onureroz.com'daki güncel kayıtlar ÜZERİNE yazılır (aynı kanal adı varsa link güncellenir).
    for extinf, extra, url in remote_entries:
        name = get_name(extinf)
        key = norm_key(name)
        if not key:
            continue
        if key not in merged:
            order.append(key)
        merged[key] = (extinf, extra, url, name)

    out_lines = ["#EXTM3U"]
    for key in order:
        extinf, extra, url, name = merged[key]
        out_lines.append(extinf)
        out_lines.extend(extra)
        out_lines.append(url)

    new_text = "\n".join(out_lines) + "\n"

    if new_text.strip() == current_text.strip():
        print("Değişiklik yok.")
        return 0

    with open(TARGET_FILE, "w", encoding="utf-8") as f:
        f.write(new_text)

    print(f"Güncellendi: {len(order)} kanal ({len(remote_entries)} tanesi onureroz.com kaynağından okundu).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
