# Betiklerin ortak yardımcısı (B-46): faturadan önce tedarikçinin uyumlu sözleşmesini compliance-service'e yükler.
# Uyum kontrolü gerçektir; sözleşmesiz tedarikçinin faturası NO_CONTRACT → PENDING_APPROVAL olur. B-16 faturalarının
# uyumlu sözleşmeleri commit edilmiştir (compliance-service/src/test/resources/contracts/compliant/uyumlu-<fatura>.pdf).
# Kullanan betik api() (uzman kimliğiyle curl) ve fail() tanımlamış olmalı. Aynı sözleşme ikinci kez yüklenirse
# compliance mevcut kaydı döner (200), yeniden indekslemez.

CONTRACT_API="http://localhost:8082/api/v1/contracts"
COMPLIANT_CONTRACTS="compliance-service/src/test/resources/contracts/compliant"

# $1 = fatura PDF'inin yolu (B-16 adı: invoice-XX.pdf). Uyumlu sözleşmesi yoksa 1 döner, hiçbir şey yüklemez.
upload_compliant_contract() {
    local base contract meta id status
    base="$(basename "$1")"
    contract="$COMPLIANT_CONTRACTS/uyumlu-$base"
    meta="${contract%.pdf}.expected.json"
    [[ -f "$contract" && -f "$meta" ]] || return 1
    local response
    response="$(api -sf -F "file=@$contract;type=application/pdf" -F "supplierVkn=$(jq -r .supplierVkn "$meta")" \
        -F "validFrom=$(jq -r .validFrom "$meta")" -F "validTo=$(jq -r .validTo "$meta")" "$CONTRACT_API")" \
        || fail "Sözleşme yüklenemedi: $contract"
    id="$(jq -r .contractId <<<"$response")"
    # Aynı tedarikçide aralığı kesişen başka sözleşme varsa faturalar CONTRACT_CONFLICT alır; görünür olsun.
    jq -r '.warnings[]? | "UYARI: " + .' <<<"$response"
    for _ in $(seq 240); do
        status="$(api -sf "$CONTRACT_API/$id" | jq -r .contract.status)"
        case "$status" in
            READY) return 0 ;;
            FAILED) fail "Sözleşme indekslenemedi: $contract (loglar: docker compose logs compliance-service)" ;;
        esac
        sleep 0.5
    done
    fail "Sözleşme 2 dk içinde indekslenmedi: $contract"
}
