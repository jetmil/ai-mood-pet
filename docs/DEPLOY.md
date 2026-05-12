# Deploy на свой VPS

Минимум: VPS с 1GB RAM, Linux (Ubuntu 22.04+ протестировано), публичный
IP, домен под него. Не нужен GPU — вся LLM-логика в облаке.

## Шаг 1. Подготовка VPS

```bash
# DNS A-record: pet.yourdomain.tld → <ваш IP>
# (или субдомен какой угодно)
```

## Шаг 2. Клонировать

```bash
ssh root@<vps>
cd /opt
git clone https://github.com/jetmil/ai-mood-pet
cd ai-mood-pet/server
```

## Шаг 3. Конфиг

```bash
cp .env.example .env
nano .env
# обязательно:
#   AUTH_TOKEN=<python -c "import secrets; print(secrets.token_hex(24))">
#   GOOGLE_API_KEY=<ваш Gemini API key>
# опционально:
#   OPENAI_API_KEY=<если есть Whisper API access>
```

Сохрани AUTH_TOKEN отдельно — он же поедет в APK Settings.

## Шаг 4. Запуск через docker-compose

```bash
docker compose up -d
docker compose logs -f tamagochi-cloud-proxy
# должен показать "Application startup complete" + порт 8350
```

## Шаг 5. Nginx + SSL

`docker-compose.yml` поднимает nginx, но SSL нужно получить отдельно:

```bash
# в docker-compose уже есть certbot service, при первом старте:
docker compose run --rm certbot certonly --webroot -w /var/www/html \
    -d pet.yourdomain.tld --email you@example.com --agree-tos
docker compose restart nginx
```

Дальше certbot auto-renew работает через cron внутри контейнера.

## Шаг 6. Проверить

```bash
curl -s https://pet.yourdomain.tld/health
# должно вернуть {"ok":true,...}
```

Если 200 — поздравляю, дальше собирай APK и положи в settings:
- **WS URL**: `wss://pet.yourdomain.tld/ws/dialog`
- **AUTH_TOKEN**: тот же что в `server/.env`

См. [ANDROID.md](ANDROID.md) для сборки APK.

## Затраты в облако

Реальные числа с замера: ~50 диалогов/день (200 chars прямой ответ + 100
chars vision) =

| Сервис | Расход в день |
|---|---|
| Gemini 2.5 Flash-Lite (chat + STT через audio input) | $0.03-0.07 |
| OpenAI Whisper (если включён) | $0.01-0.02 |
| edge-tts (Microsoft anonymous) | $0 |
| Итого | **~$1-3/мес** |

Установи billing alert на $5 в Google Cloud Console — на случай если
кто-то получит твой AUTH_TOKEN и начнёт бомбить.

## Troubleshooting

| Симптом | Причина | Лечение |
|---|---|---|
| `/health` 502 | docker сервер не поднялся | `docker compose logs` |
| `403 unsupported_country_region` на STT | OpenAI блокирует РФ | VPS в Европе/США, или Gemini-STT |
| WS закрывается через 60с | nginx timeout | в `nginx.conf` `proxy_read_timeout 1800` |
| TTS пустой | edge-tts rate-limit на anonymous | подожди 5 мин или поменяй voice |
| «Видяха занята» вечно | mode=local на сервере без Ollama | переключись в cloud mode |
