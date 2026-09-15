#!/bin/sh
# Uygulama kullanıcısını .env'den üretip topolojiyle birleştirir, sonra RabbitMQ'yu başlatır.
# definitions.json yalnızca topolojiyi tutar; şifre ve hash'i git'e girmez (NFR-10, ADR-17).
# Definitions yüklenince guest oluşturulmaz: kullanıcı burada üretilmezse servisler bağlanamaz.
# İsteğe bağlı: RABBITMQ_ADMIN_PASSWORD doluysa yönetim arayüzü (15672) için ayrı bir insan kullanıcısı da
# üretilir: "monitoring" etiketi, invoice vhost'unda yalnız okuma izni. Kuyrukları ve mesajları görür, yayınlayamaz,
# topoloji değiştiremez; RabbitMQ'da okuma izni mesaj almayı (Get messages) ve kuyruk boşaltmayı (purge) da kapsar.
set -eu

: "${RABBITMQ_USERNAME:?RABBITMQ_USERNAME gerekli (.env)}"
: "${RABBITMQ_PASSWORD:?RABBITMQ_PASSWORD gerekli (.env)}"
RABBITMQ_ADMIN_USERNAME="${RABBITMQ_ADMIN_USERNAME:-}"
RABBITMQ_ADMIN_PASSWORD="${RABBITMQ_ADMIN_PASSWORD:-}"

# Kullanıcı adı JSON'a kaçışsız yazıldığı için karakter kümesi sınırlı.
check_name() {
  case "$2" in
    ''|*[!A-Za-z0-9._-]*) echo "$1 yalnızca harf, rakam, . _ - içerebilir ve boş olamaz" >&2; exit 1 ;;
  esac
}
check_name RABBITMQ_USERNAME "$RABBITMQ_USERNAME"

TOPOLOGY=/etc/rabbitmq/definitions.json
OUT=/tmp/rabbitmq/definitions.json
VHOST=invoice

# Şifre argüman olarak verilirse rabbitmqctl onu ekrana yazar; stdin'den verilir.
hash_password() {
  h=$(printf '%s\n' "$1" | rabbitmqctl hash_password 2>/dev/null | tail -n 1)
  [ -n "$h" ] || { echo "Şifre hash'lenemedi" >&2; exit 1; }
  printf '%s' "$h"
}

# Hash ayrı atamayla alınır: iç içe komut ikamesinde hash_password'ün hatası set -e'ye ulaşmazdı.
APP_HASH=$(hash_password "$RABBITMQ_PASSWORD")
USERS=$(printf '{"name": "%s", "password_hash": "%s", "hashing_algorithm": "rabbit_password_hashing_sha256", "tags": []}' \
  "$RABBITMQ_USERNAME" "$APP_HASH")
PERMISSIONS=$(printf '{"user": "%s", "vhost": "%s", "configure": "", "write": ".*", "read": ".*"}' \
  "$RABBITMQ_USERNAME" "$VHOST")

if [ -n "$RABBITMQ_ADMIN_PASSWORD" ]; then
  check_name RABBITMQ_ADMIN_USERNAME "$RABBITMQ_ADMIN_USERNAME"
  [ "$RABBITMQ_ADMIN_USERNAME" != "$RABBITMQ_USERNAME" ] \
    || { echo "RABBITMQ_ADMIN_USERNAME uygulama kullanıcısından farklı olmalı" >&2; exit 1; }
  ADMIN_HASH=$(hash_password "$RABBITMQ_ADMIN_PASSWORD")
  USERS="$USERS, $(printf '{"name": "%s", "password_hash": "%s", "hashing_algorithm": "rabbit_password_hashing_sha256", "tags": ["monitoring"]}' \
    "$RABBITMQ_ADMIN_USERNAME" "$ADMIN_HASH")"
  PERMISSIONS="$PERMISSIONS, $(printf '{"user": "%s", "vhost": "%s", "configure": "", "write": "", "read": ".*"}' \
    "$RABBITMQ_ADMIN_USERNAME" "$VHOST")"
fi

mkdir -p "$(dirname "$OUT")"
# Topoloji dosyası '{' ile başlar; kullanıcılar ve izinler o karakterden sonra eklenir.
{
  printf '{\n'
  printf '  "users": [%s],\n' "$USERS"
  printf '  "permissions": [%s],\n' "$PERMISSIONS"
  tail -c +2 "$TOPOLOGY"
} > "$OUT"
chown rabbitmq:rabbitmq "$OUT" 2>/dev/null || true

exec docker-entrypoint.sh "$@"
