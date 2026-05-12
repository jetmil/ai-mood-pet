# ai-mood-pet

Голосовой ИИ-питомец на Android с распознаванием эмоций и жестов. Mood/FSM/lipsync/face/hand-detection
работают на устройстве через MediaPipe, LLM/STT/TTS — в облаке (Gemini Flash-Lite + OpenAI Whisper).
Сервер — лёгкий FastAPI-прокси, который ты поднимаешь сам на своём VPS.

**Это не Tamagotchi для PlayStore.** Это open-source каркас под self-host. Бюджет на работу — ~$1-3/мес
на LLM/STT API.

## Что умеет

- **Многоперсонажность.** 6 готовых характеров (Мудрец, Льстец, Дикий Маугли, Стэтхем, Детский робот,
  Сердитый Бас). У каждого свой голос, словарь, шутки. JSON-spec — добавляй своих.
- **Эмоции через лицо.** MediaPipe Face Landmarker (52 blendshape'а) → питомец зеркалит твою
  мимику с собственной поправкой по характеру.
- **Распознавание жестов.** MediaPipe Hands → реакции на 9 жестов (палец вверх, OK, peace,
  кулак, открытая ладонь, три пальца, point, rock, four_fingers).
- **Vision-инструмент.** «Что у меня в руках?» → кадр с камеры идёт в multimodal-LLM.
- **Streaming TTS.** Ответ режется на предложения, TTS параллельно, первый звук через ~1.5с
  вместо 6с.
- **Wake-word.** Vosk small-model на устройстве (по умолчанию русский).
- **Память диалога.** 5 последних обменов на user_id, локально на сервере.

## Что НЕ умеет

- Полностью офлайн (LLM/STT облачные). Локальный fallback на pre-recorded банк фраз — есть.
- Не заменяет терапию. Это технический эксперимент.

## Quickstart (self-host)

```bash
git clone https://github.com/jetmil/ai-mood-pet
cd ai-mood-pet
cp server/.env.example server/.env
# В server/.env заполнить: AUTH_TOKEN, GOOGLE_API_KEY, опционально OPENAI_API_KEY

# Bootstrap первый раз — Let's Encrypt cert + nginx:
DOMAIN=pet.yourdomain.tld EMAIL=you@example.com ./scripts/init_letsencrypt.sh

# Поднять весь стек:
docker compose up -d
```

Сервер на `:8350`. Полная инструкция (DNS, troubleshooting) — см.
[docs/DEPLOY.md](docs/DEPLOY.md).
Минимальный VPS: 1GB RAM, Linux, публичный IP, домен. От 300₽/мес на Timeweb/Reg.ru,
от €4/мес на Aeza/Hetzner.

APK сборка — [docs/ANDROID.md](docs/ANDROID.md) (или скачивай готовый из GitHub Releases когда
будет первый тег).

## Архитектура

```
┌──────────────┐    WSS     ┌──────────────────────┐
│ Android APK  │ ─────────► │ FastAPI proxy        │
│  - mood/FSM  │            │  - WS /ws/dialog     │
│  - lipsync   │            │  - STT routing       │ ──► Gemini / OpenAI
│  - hand det. │            │  - LLM call          │
│  - face det. │            │  - TTS streaming     │ ──► Microsoft edge-tts
│  - voice bank│            │  - character configs │
└──────────────┘            └──────────────────────┘
   on-device                 your VPS (any size)
```

Mood/FSM/lipsync/wake-word работают офлайн. Сервер нужен только для LLM/STT-облака.

## Лицензия — AGPL-3.0

**Не MIT.** Если ты разворачиваешь этот сервис в публичном виде (на свой сайт, в SaaS,
кому-то другому) — обязан опубликовать исходный код своих модификаций. Это защита от того,
что кто-то берёт твой код, поднимает конкурента и не возвращает доработки.

Личное использование, форки для своих исследований, доработка под себя — без обязательств.
Полный текст: [LICENSE](LICENSE) (GNU Affero General Public License v3).

Для коммерческой / корпоративной лицензии (если AGPL не подходит по политике твоей компании) —
свяжись с владельцем проекта: **`jetmil@proton.me`** или через GitHub Discussions.

## Поддержать разработку

Внутри РФ:
- **Boosty:** [скоро] — подписки + разовые
- **CloudTips (Тинькофф):** [скоро] — «купить кофе»
- **ЮKassa на ligardi.ru:** [скоро] — через ИП АРТ-СВЕЧИ

Снаружи РФ: пока не подключено (зарубежного счёта нет). Если PR'ом
прислал чёткое улучшение — респект в CONTRIBUTORS.md.

## Managed-режим

**Нет.** Self-host first. Поднимай свой VPS, держишь свои API-ключи, плати $1-3/мес сам.
Это даёт privacy: твои разговоры идут только через сервисы Google/OpenAI, никакого
посредника-владельца проекта.

Если очень хочется managed — открой issue с описанием use-case. Запустим только когда
наберётся 3+ платных подписчика покрывающих VPS + маржу.

## Author

Maintainer: [@jetmil](https://github.com/jetmil). Code implementation assisted by Claude (Anthropic)
during 12-hour collaborative sessions, all design and product decisions belong to maintainer.
См. [CONTRIBUTORS.md](CONTRIBUTORS.md).
