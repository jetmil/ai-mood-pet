#!/usr/bin/env bash
# init_letsencrypt.sh — bootstrap первого Let's Encrypt сертификата.
#
# Проблема: docker-compose поднимает nginx с ssl_certificate-путём на
# /etc/letsencrypt/live/<DOMAIN>/... — без этого файла nginx падает.
# certbot certonly --webroot требует чтобы nginx уже отвечал на :80 для
# ACME challenge — chicken-and-egg.
#
# Решение: положить временный self-signed cert, поднять nginx, запросить
# настоящий через webroot, перезагрузить nginx.
#
# Usage:
#   DOMAIN=pet.example.com EMAIL=you@example.com ./scripts/init_letsencrypt.sh
#
# После успешного запуска можно `docker compose up -d` — серт уже на месте.

set -euo pipefail

DOMAIN="${DOMAIN:-}"
EMAIL="${EMAIL:-}"

if [[ -z "$DOMAIN" ]]; then
  echo "DOMAIN не задан. Пример: DOMAIN=pet.example.com EMAIL=you@example.com $0"
  exit 1
fi
if [[ -z "$EMAIL" ]]; then
  echo "EMAIL не задан (нужен Let's Encrypt для уведомлений)."
  exit 1
fi

cd "$(dirname "$0")/.."

LE_DIR="./nginx/letsencrypt"
WWW_DIR="./nginx/certbot-www"
LIVE_DIR="$LE_DIR/live/$DOMAIN"

echo "→ Создаю директории и подменяю <DOMAIN> в nginx/conf.d/server.conf"
mkdir -p "$LIVE_DIR" "$WWW_DIR"
# заменяем плейсхолдер на реальный домен (только если он ещё не заменён)
if grep -q '<DOMAIN>' nginx/conf.d/server.conf; then
  sed -i.bak "s/<DOMAIN>/$DOMAIN/g" nginx/conf.d/server.conf
  echo "  sed: <DOMAIN> → $DOMAIN (бэкап сохранён как server.conf.bak)"
fi

echo "→ Генерирую self-signed dummy cert (только чтобы nginx стартанул)"
docker run --rm -v "$(pwd)/$LE_DIR:/etc/letsencrypt" \
  alpine/openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
    -keyout "/etc/letsencrypt/live/$DOMAIN/privkey.pem" \
    -out    "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" \
    -subj "/CN=localhost" >/dev/null 2>&1

echo "→ Запускаю nginx с dummy cert"
docker compose up -d nginx

echo "→ Удаляю dummy cert и запрашиваю настоящий через webroot"
docker run --rm -v "$(pwd)/$LE_DIR:/etc/letsencrypt" \
  alpine sh -c "rm -rf /etc/letsencrypt/live/$DOMAIN /etc/letsencrypt/archive/$DOMAIN /etc/letsencrypt/renewal/$DOMAIN.conf"

docker compose run --rm --entrypoint "certbot certonly --webroot -w /var/www/html \
  -d $DOMAIN --email $EMAIL --agree-tos --non-interactive --rsa-key-size 4096" certbot

echo "→ Перезапускаю nginx с настоящим сертом"
docker compose restart nginx

echo
echo "Done. Проверь: curl -sI https://$DOMAIN/health"
echo "Дальше: docker compose up -d  (поднимет server + certbot-renew loop)"
