# Security & Privacy

## Что хранится у тебя на сервере

- **Bearer AUTH_TOKEN** в `server/.env` — единственный «ключ» доступа к WS.
  Не комитить, не вставлять в скриншоты.
- **API keys** (`GOOGLE_API_KEY`, опционально `OPENAI_API_KEY`) — в `server/.env`.
  Билинг на твоём аккаунте — лимиты ставь сразу.
- **История диалогов** — если включён `HISTORY_DB_PATH`, локально в SQLite на
  сервере. **Не уходит** в LLM-провайдер дальше необходимого для context.
- **Аудио**: транзитом в STT-провайдер (Google/OpenAI), не сохраняется
  на сервере по умолчанию. Записи `utterances/` для дебага — отключаемы
  через env.

## Что хранится на телефоне

- **`AUTH_TOKEN`** — в `EncryptedSharedPreferences` (Android Jetpack Security).
  Не в plaintext.
- **Face fingerprints** — локально в SQLite, **никогда не покидают устройство**.
  Это 128-float-vector на каждое запомненное лицо, не сам кадр.
- **История разговоров** — копия серверной для UI, прозрачно очищается с
  reset-кнопки в Settings.
- **Аудио из кадра** — транзитом, не сохраняется.

## Что НЕ собирается

- Биометрия в облако. Face landmarker работает локально, никаких кадров
  лица за пределы телефона.
- Реальное имя / телефон / email пользователя. Owner-name в Settings —
  только для system-prompt («хозяин Олег» → «Олег, как дела?»).
- Геолокация.
- Контакты, SMS, файлы.

## Чек-лист перед публичным запуском

- [ ] `AUTH_TOKEN` сгенерирован вручную, не дефолтный, не в git.
- [ ] `GOOGLE_API_KEY` имеет **billing alert** на $5/мес — Gemini Flash-Lite
      дешёвая, но safety-cap на случай атаки.
- [ ] `RATE_LIMIT_PER_MIN` > 0, разумное значение (60 на одного юзера).
- [ ] HTTPS (Let's Encrypt) обязательно, никогда не WS-only без TLS.
- [ ] Сервер только в локальном UFW/iptables allow 80,443 — не пускать 8350
      наружу прямо.
- [ ] Логи в `loguru` обрезают `token=***`, проверь.

## Reporting a vulnerability

Если нашёл security issue — **не пиши в публичных issues**. Два пути:

1. GitHub Security Advisory приватно:
   <https://github.com/jetmil/ai-mood-pet/security/advisories/new>
2. Email maintainer'а: **`jetmil@proton.me`** (PGP-key опционально по
   запросу — пока без).

Reasonable disclosure: даю 30-90 дней на патч, затем публикую CVE
если уместно. Не публикуй детали PoC до выпуска фикса.

## Угрозы которые мы НЕ закрываем

- Физический доступ к телефону (рутование, ADB). Если у атакующего root —
  он достанет AUTH_TOKEN. Это вне scope.
- Compromise твоего GOOGLE_API_KEY на стороне Google. Используй project
  ограничения / IP allowlist.
- Phishing: если другой человек убедит тебя поставить APK с подменой URL —
  это твоя проблема. Сборку делай сам из source.
