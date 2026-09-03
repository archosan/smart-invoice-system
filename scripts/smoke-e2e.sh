#!/usr/bin/env bash
# Uçtan uca duman testi (B-23, B-25): tam yığın + host'taki Ollama ile yükleme → POSTED; rpa-service faturayı
# mock-portal'a girer ve kayıt numarası kayda yazılır. B-46'dan beri önce tedarikçinin uyumlu sözleşmesi yüklenir
# (uyum kontrolü gerçek: bge-m3 + sohbet modeli).
#
# Kullanım: scripts/smoke-e2e.sh [fatura.pdf]      (varsayılan: B-16 setinden invoice-01.pdf)
# Ortam:    SMOKE_TIMEOUT (sn, varsayılan 300)
#
# Ön koşullar: .env, `ollama serve`, OLLAMA_CHAT_MODEL ve OLLAMA_EMBEDDING_MODEL'in çekilmiş olması.
# Yığın sonunda açık bırakılır;
# kapatmak için: docker compose --profile all down
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PDF="${1:-extraction-service/src/test/resources/invoices/invoice-01.pdf}"
TIMEOUT="${SMOKE_TIMEOUT:-300}"
API="http://localhost:8081/api/v1/documents"
OLLAMA="http://localhost:11434"

step() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nBAŞARISIZ: %s\n' "$*" >&2; exit 1; }

step "Ön koşullar"
for tool in docker curl jq; do
    command -v "$tool" >/dev/null || fail "$tool bulunamadı"
done
[[ -f .env ]] || fail ".env yok (cp .env.example .env)"
[[ -f "$PDF" ]] || fail "PDF bulunamadı: $PDF"
# Model adı compose'da zorunludur (B-26); boşsa servis de açılmaz (B-23). Burada erken ve açık hata için okunur.
MODEL="$(grep -E '^OLLAMA_CHAT_MODEL=' .env | tail -n1 | cut -d= -f2- || true)"
[[ -n "$MODEL" ]] || fail ".env'de OLLAMA_CHAT_MODEL yok ya da boş (örn. qwen2.5:7b-instruct)"
# API kullanıcısı (B-43): uzman şifresi .env'den okunur ve curl'e argüman olarak değil, process substitution'la
# yapılandırma olarak verilir (süreç listesinde görünmez).
API_PASSWORD="$(grep -E '^API_EXPERT_PASSWORD=' .env | tail -n1 | cut -d= -f2- || true)"
API_PASSWORD="${API_PASSWORD%\"}"; API_PASSWORD="${API_PASSWORD#\"}"
[[ -n "$API_PASSWORD" ]] || fail ".env'de API_EXPERT_PASSWORD yok ya da boş (B-43; .env.example'a bakın)"
api() { curl -K <(printf 'user = "expert:%s"\n' "$(sed 's/[\\"]/\\&/g' <<<"$API_PASSWORD")") "$@"; }
source scripts/lib-contracts.sh
EMBEDDING_MODEL="$(grep -E '^OLLAMA_EMBEDDING_MODEL=' .env | tail -n1 | cut -d= -f2- || true)"
[[ -n "$EMBEDDING_MODEL" ]] || fail ".env'de OLLAMA_EMBEDDING_MODEL yok ya da boş (örn. bge-m3)"
curl -sf -m 3 "$OLLAMA/api/tags" | jq -e --arg m "$EMBEDDING_MODEL" \
    '.models[] | select(.name == $m or .name == ($m + ":latest"))' >/dev/null \
    || fail "Embedding modeli çekilmemiş: ollama pull $EMBEDDING_MODEL"
curl -sf -m 3 "$OLLAMA/api/tags" >/dev/null || fail "Ollama'ya ulaşılamıyor ($OLLAMA); 'ollama serve' çalışıyor mu?"
curl -sf -m 3 "$OLLAMA/api/tags" | jq -e --arg m "$MODEL" '.models[] | select(.name == $m)' >/dev/null \
    || fail "Model çekilmemiş: ollama pull $MODEL"
echo "Ollama hazır, model: $MODEL"

step "Yığın kuruluyor (docker compose --profile all up --build --wait)"
docker compose --profile all up -d --build --wait

# Aynı PDF ikinci kez yüklenirse hash'ten mükerrer sayılır (200 duplicate) ve akış çalışmaz. Dosyanın sonuna
# %%EOF'tan sonra benzersiz bir PDF yorumu eklenir: içerik ve metin aynı kalır, SHA-256 değişir; betik
# volume'ları silmeden tekrar çalıştırılabilir. Fatura no aynı kaldığı için ikinci çalıştırmada kayıt içerik bazlı
# mükerrer şüphesine düşer (B-41); betik bunu "mükerrer değil" kararıyla geçer ve portal ön araması ilk girişi bulur.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
UPLOAD="$WORK/$(basename "$PDF")"
cp "$PDF" "$UPLOAD"
printf '\n%%smoke-e2e %s\n' "$(uuidgen)" >> "$UPLOAD"

step "Tedarikçinin sözleşmesi (B-46)"
if upload_compliant_contract "$PDF"; then
    echo "Uyumlu sözleşme indekslendi: uyumlu-$(basename "$PDF")"
else
    echo "UYARI: $(basename "$PDF") için hazır sözleşme yok; uyum kontrolü NO_CONTRACT verir, kayıt PENDING_APPROVAL'da durur"
fi

step "Yükleniyor: $PDF"
RESPONSE="$(api -sS -w '\n%{http_code}' -F "file=@$UPLOAD;type=application/pdf" "$API")"
HTTP="$(tail -n1 <<<"$RESPONSE")"
BODY="$(sed '$d' <<<"$RESPONSE")"
[[ "$HTTP" == 202 ]] || fail "Yükleme 202 dönmedi (HTTP $HTTP): $BODY"
ID="$(jq -r '.documentId' <<<"$BODY")"
echo "documentId: $ID"

step "POSTED bekleniyor (en fazla ${TIMEOUT} sn)"
START=$SECONDS
LAST=""
DUPLICATE_NOTE=""
while :; do
    DETAIL="$(api -sf "$API/$ID")" || fail "GET $API/$ID başarısız"
    STATUS="$(jq -r '.status' <<<"$DETAIL")"
    if [[ "$STATUS" != "$LAST" ]]; then
        printf '%4d sn  %s\n' $((SECONDS - START)) "$STATUS"
        LAST="$STATUS"
    fi
    case "$STATUS" in
        POSTED) break ;;
        DUPLICATE_SUSPECTED)
            # Aynı fatura bu yığında daha önce yüklendi (betiğin önceki çalıştırması). Uzman kararı API'den verilir.
            [[ -z "$DUPLICATE_NOTE" ]] || fail "Mükerrer kararından sonra yine DUPLICATE_SUSPECTED"
            jq -r '"        eşleşen kayıtlar: \(.duplicateOf | join(", "))"' <<<"$DETAIL"
            DECISION="$(api -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
                -d '{"duplicate": false, "reason": "smoke-e2e: aynı fatura bilerek tekrar yüklendi"}' \
                "$API/$ID/duplicate-decision")"
            [[ "$DECISION" == 200 ]] || fail "Mükerrer kararı verilemedi (HTTP $DECISION)"
            DUPLICATE_NOTE=" (mükerrer şüphesi 'mükerrer değil' kararıyla geçildi, portal ön araması ilk girişi buldu)"
            echo "        'mükerrer değil' kararı verildi" ;;
        NEEDS_REVIEW | PENDING_APPROVAL | REJECTED | RPA_FAILED)
            jq . <<<"$DETAIL"
            fail "Beklenmeyen son durum: $STATUS (loglar: docker compose logs extraction-service document-service rpa-service)" ;;
    esac
    (( SECONDS - START < TIMEOUT )) || fail "Zaman aşımı; son durum $STATUS (loglar: docker compose logs)"
    sleep 2
done
ELAPSED=$((SECONDS - START))

step "Kayıt"
jq '{status, supplierVkn, invoiceNo, invoiceDate, grandTotal, currency, confidenceScore,
     failedRules: [.ruleResults[] | select(.passed | not) | .rule], compliance, portalRefNo}' <<<"$DETAIL"
[[ "$(jq -r '.compliance.result' <<<"$DETAIL")" == COMPLIANT ]] || fail "Uyum sonucu COMPLIANT değil"
REF_NO="$(jq -r '.portalRefNo // empty' <<<"$DETAIL")"
[[ -n "$REF_NO" ]] || fail "POSTED kaydında portal kayıt numarası yok"

step "Durum geçişleri (status_transitions)"
docker compose exec -T postgres psql -U postgres -d document_db -c \
    "SELECT coalesce(from_status, '—') AS from_status, to_status, trigger_event, actor
     FROM status_transitions WHERE document_id = '$ID' ORDER BY id"

step "Portal kaydı (rpa_db.portal_submissions)"
docker compose exec -T postgres psql -U postgres -d rpa_db -c \
    "SELECT status, portal_ref_no, attempt_count FROM portal_submissions WHERE document_id = '$ID'"

step "Loglarda secret taraması (NFR-10, B-26)"
# .env'deki her *_PASSWORD değeri bütün konteyner loglarında aranır. Değer ekrana ve süreç argümanlarına
# yazılmaz (grep deseni process substitution ile verilir); bulunursa yalnızca anahtarın adı söylenir.
LOGS="$WORK/compose.log"
docker compose --profile all logs --no-color > "$LOGS" 2>&1
LEAKED=()
while IFS='=' read -r key value; do
    # compose gibi çevreleyen tırnakları at
    value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
    [[ -n "$value" ]] || continue
    if grep -qF -f <(printf '%s\n' "$value") "$LOGS"; then
        LEAKED+=("$key")
    fi
done < <(grep -E '^[A-Z_]*PASSWORD=' .env)
(( ${#LEAKED[@]} == 0 )) || fail "Loglarda secret bulundu: ${LEAKED[*]}"
echo "$(wc -l < "$LOGS" | tr -d ' ') log satırı tarandı, secret yok"

printf '\nBAŞARILI: yükleme → POSTED, %d sn, portal kayıt no %s%s. Portal: http://localhost:8090 · Yığın açık; kapatmak için: docker compose --profile all down\n' \
    "$ELAPSED" "$REF_NO" "$DUPLICATE_NOTE"
