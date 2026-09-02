#!/usr/bin/env bash
# NFR-07 ölçümü (B-30): tam yığın + host'taki Ollama, yerel makinede.
#   1. Yükleme API'si: 1 ısınma + N yükleme; p50 / p95 / maks. Ölçüt: p95 < 500 ms (ısınma ölçüte girmez).
#   2. Uçtan uca: tek sayfalık temiz faturalar (B-16: 01, 02, 04–06) teker teker, yükleme → POSTED. Ölçüt: hepsi
#      < 120 sn. 03 tutar eşiğinin üstündedir, PENDING_APPROVAL'da durur (FR-D11, B-39); ölçüme girmez.
#      Uyum kontrolü gerçektir (B-46): tedarikçilerin uyumlu sözleşmeleri önce yüklenir; süre uyum kontrolünü (bge-m3 +
#      sohbet modeli) içerir. Betik aynı yığında ikinci kez çalışırsa faturalar içerik bazlı mükerrer şüphesine düşer (B-41): betik
#      "mükerrer değil" kararını API'den verir ve ölçüme devam eder (portal ön araması ilk girişi bulur); süre karar
#      dahil ölçülür, raporda işaretlenir. Temiz ölçüm için boş yığında çalıştırın.
#
# Kullanım: scripts/measure-nfr07.sh          Ortam: NFR07_UPLOADS (varsayılan 20)
# Rapor: target/nfr07/nfr07-<zaman>.md. Ön koşullar duman testiyle aynı (.env, `ollama serve`, model çekili).
#
# Yükleme ölçümünde 10. fatura (yalnız resim, metin yok) kullanılır: yükleme yolu aynıdır (hash, dosya, kayıt,
# outbox), ama arkasından LLM işi doğmaz (NO_TEXT → NEEDS_REVIEW); uçtan uca ölçümün önüne kuyruk yığılmaz. Resimli
# PDF diğerlerinden büyüktür (78 KB / 12 KB), yükleme için temkinli seçimdir.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

UPLOADS="${NFR07_UPLOADS:-20}"
INVOICES="extraction-service/src/test/resources/invoices"
API="http://localhost:8081/api/v1/documents"
OLLAMA="http://localhost:11434"
UPLOAD_LIMIT_MS=500
E2E_LIMIT_S=120

step() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nBAŞARISIZ: %s\n' "$*" >&2; exit 1; }

step "Ön koşullar"
for tool in docker curl jq uuidgen; do
    command -v "$tool" >/dev/null || fail "$tool bulunamadı"
done
[[ -f .env ]] || fail ".env yok (cp .env.example .env)"
MODEL="$(grep -E '^OLLAMA_CHAT_MODEL=' .env | tail -n1 | cut -d= -f2- || true)"
[[ -n "$MODEL" ]] || fail ".env'de OLLAMA_CHAT_MODEL yok ya da boş"
# API kullanıcısı (B-43): uzman şifresi .env'den okunur ve curl'e argüman olarak değil, process substitution'la
# yapılandırma olarak verilir (süreç listesinde görünmez).
API_PASSWORD="$(grep -E '^API_EXPERT_PASSWORD=' .env | tail -n1 | cut -d= -f2- || true)"
API_PASSWORD="${API_PASSWORD%\"}"; API_PASSWORD="${API_PASSWORD#\"}"
[[ -n "$API_PASSWORD" ]] || fail ".env'de API_EXPERT_PASSWORD yok ya da boş (B-43; .env.example'a bakın)"
api() { curl -K <(printf 'user = "expert:%s"\n' "$(sed 's/[\\"]/\\&/g' <<<"$API_PASSWORD")") "$@"; }
source scripts/lib-contracts.sh
curl -sf -m 3 "$OLLAMA/api/tags" | jq -e --arg m "$MODEL" '.models[] | select(.name == $m)' >/dev/null \
    || fail "Ollama'ya ulaşılamıyor ya da model çekilmemiş ($MODEL)"

step "Yığın kuruluyor"
docker compose --profile all up -d --build --wait

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# PDF'in benzersiz kopyası: %%EOF'tan sonra PDF yorumu, hash değişir (B-23).
unique() {
    local copy="$WORK/$(uuidgen).pdf"
    cp "$1" "$copy"
    printf '\n%%nfr07 %s\n' "$(uuidgen)" >> "$copy"
    echo "$copy"
}

# Yükler; "<http> <ms> <documentId>" yazar.
upload() {
    local out
    out="$(api -sS -o "$WORK/body.json" -w '%{http_code} %{time_total}' -F "file=@$1;type=application/pdf" "$API")"
    local http="${out% *}" seconds="${out#* }"
    [[ "$http" == 202 ]] || fail "Yükleme 202 dönmedi (HTTP $http): $(cat "$WORK/body.json")"
    printf '%s %d %s\n' "$http" "$(awk -v s="$seconds" 'BEGIN { printf "%d", s * 1000 + 0.5 }')" \
        "$(jq -r '.documentId' "$WORK/body.json")"
}

now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

percentile() { # $1 = yüzdelik, stdin = sayılar (nearest-rank)
    sort -n | awk -v p="$1" '{ v[NR] = $1 } END { i = int((p / 100) * NR + 0.999999); if (i < 1) i = 1; print v[i] }'
}

STAMP="$(date +%Y%m%d-%H%M%S)"
REPORT="target/nfr07/nfr07-$STAMP.md"
mkdir -p target/nfr07
VERDICT=0

step "1. Yükleme API'si (1 ısınma + $UPLOADS)"
read -r _ WARMUP _ <<<"$(upload "$(unique "$INVOICES/invoice-10.pdf")")"
echo "ısınma: ${WARMUP} ms"
: > "$WORK/upload.txt"
for i in $(seq "$UPLOADS"); do
    read -r _ MS _ <<<"$(upload "$(unique "$INVOICES/invoice-10.pdf")")"
    echo "$MS" >> "$WORK/upload.txt"
    printf '%3d: %d ms\n' "$i" "$MS"
done
P50="$(percentile 50 < "$WORK/upload.txt")"
P95="$(percentile 95 < "$WORK/upload.txt")"
MAX="$(sort -n "$WORK/upload.txt" | tail -n1)"
UPLOAD_OK=$(( P95 < UPLOAD_LIMIT_MS ))
(( UPLOAD_OK )) || VERDICT=1
echo "p50 ${P50} ms · p95 ${P95} ms · maks ${MAX} ms → $( (( UPLOAD_OK )) && echo GEÇTİ || echo KALDI )"

step "2. Uçtan uca (tek sayfalık temiz faturalar, teker teker)"
# Uyum kontrolü gerçektir (B-46): tedarikçilerin uyumlu sözleşmeleri ölçümden önce yüklenir, süreye girmez.
for n in 01 02 04 05 06; do
    upload_compliant_contract "$INVOICES/invoice-$n.pdf" || fail "invoice-$n için uyumlu sözleşme yok"
done
echo "5 sözleşme indekslendi"
: > "$WORK/e2e.txt"
for n in 01 02 04 05 06; do
    START="$(now)"
    read -r _ _ ID <<<"$(upload "$(unique "$INVOICES/invoice-$n.pdf")")"
    STATUS=""
    MARK=""
    while :; do
        STATUS="$(api -sf "$API/$ID" | jq -r '.status')"
        ELAPSED="$(awk -v a="$START" -v b="$(now)" 'BEGIN { printf "%.1f", b - a }')"
        if [[ "$STATUS" == DUPLICATE_SUSPECTED && -z "$MARK" ]]; then
            api -sf -o /dev/null -H 'Content-Type: application/json' \
                -d '{"duplicate": false, "reason": "measure-nfr07: aynı fatura bilerek tekrar yüklendi"}' \
                "$API/$ID/duplicate-decision" || { STATUS="KARAR_VERİLEMEDİ"; break; }
            MARK="*"
            continue
        fi
        case "$STATUS" in
            POSTED | NEEDS_REVIEW | PENDING_APPROVAL | REJECTED | DUPLICATE_SUSPECTED | RPA_FAILED) break ;;
        esac
        awk -v e="$ELAPSED" -v l="$E2E_LIMIT_S" 'BEGIN { exit !(e > l * 3) }' && { STATUS="ZAMAN_AŞIMI"; break; }
        sleep 0.5
    done
    OK=$(awk -v e="$ELAPSED" -v l="$E2E_LIMIT_S" -v s="$STATUS" 'BEGIN { print (s == "POSTED" && e < l) ? 1 : 0 }')
    (( OK )) || VERDICT=1
    echo "invoice-$n$MARK $STATUS $ELAPSED" >> "$WORK/e2e.txt"
    printf 'invoice-%s%s: %s, %s sn → %s\n' "$n" "$MARK" "$STATUS" "$ELAPSED" "$( (( OK )) && echo GEÇTİ || echo KALDI )"
done
E2E_MAX="$(awk '{ print $3 }' "$WORK/e2e.txt" | sort -n | tail -n1)"

{
    echo "# NFR-07 ölçümü ($STAMP)"
    echo
    echo "Model \`$MODEL\`, Ollama $(curl -sf "$OLLAMA/api/version" | jq -r '.version'), $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)."
    echo
    echo "## Yükleme API'si (ölçüt: p95 < ${UPLOAD_LIMIT_MS} ms)"
    echo
    echo "| Isınma | n | p50 | p95 | maks | Sonuç |"
    echo "| --- | --- | --- | --- | --- | --- |"
    echo "| ${WARMUP} ms | $UPLOADS | ${P50} ms | ${P95} ms | ${MAX} ms | $( (( UPLOAD_OK )) && echo GEÇTİ || echo KALDI ) |"
    echo
    echo "## Uçtan uca: yükleme → POSTED (ölçüt: < ${E2E_LIMIT_S} sn, teker teker)"
    echo
    echo "| Fatura | Durum | Süre (sn) |"
    echo "| --- | --- | --- |"
    awk '{ printf "| %s | %s | %s |\n", $1, $2, $3 }' "$WORK/e2e.txt"
    echo
    echo "En uzun: ${E2E_MAX} sn."
    if grep -q '\*' "$WORK/e2e.txt"; then
        echo
        echo "\\* Mükerrer şüphesi (B-41): fatura bu yığında daha önce yüklenmişti; süre 'mükerrer değil' kararı ve"
        echo "portal ön aramasının ilk girişi bulması dahil. Temiz ölçüm için boş yığında çalıştırın."
    fi
} > "$REPORT"

printf '\nRapor: %s\n' "$REPORT"
(( VERDICT == 0 )) && echo "SONUÇ: NFR-07 GEÇTİ" || { echo "SONUÇ: NFR-07 KALDI"; exit 1; }
