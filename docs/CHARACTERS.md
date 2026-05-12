# Characters

Персонаж = JSON-spec в `server/characters/<name>.json`. Каждый файл —
полное определение характера: голос, system-prompt, фразы-банк, жанр шуток,
запретные темы.

## Структура

```json
{
  "id": "sage",
  "label": "Мудрец",
  "bio": "Тёплый размеренный голос. Цитирует классиков по поводу.",
  "voice": {
    "base": "ru-RU-DmitryNeural",
    "rate": "-15%",
    "pitch": "-12Hz",
    "ffmpeg": "equalizer=f=120:width_type=h:width=80:g=3,aecho=0.7:0.5:60:0.25,acompressor=threshold=-20dB:ratio=3:attack=10:release=200"
  },
  "system_prompt": "Ты — Мудрец. Тёплый размеренный голос, без сленга, без мата. Отвечай 1-3 предложения. Цитата только когда отвечает на вопрос. Никогда не комментируй мимику собеседника.",
  "banks": {
    "greeting": ["Здравствуй, друг.", "Рад снова тебя слышать.", ...],
    "joy": [...],
    "anger": [...],
    "sadness": [...],
    "surprise": [...],
    "fear": [...],
    "curious": [...],
    "bye": [...],
    "sleepy": [...]
  },
  "humor_genre": "philosophy",
  "forbidden_topics": ["mat", "modern_slang"],
  "mirror_amplifier": 1.00,
  "default_safe": true
}
```

### Поля

| Поле | Что |
|---|---|
| `id` | machine-readable, нижний регистр, slug |
| `label` | как отображается в Settings |
| `bio` | 1-2 предложения для UI/CONTRIBUTING |
| `voice.base` | edge-tts voice (см. `edge-tts --list-voices`) |
| `voice.rate` | `-30%..+30%` |
| `voice.pitch` | `-50Hz..+80Hz` |
| `voice.ffmpeg` | post-process цепочка ffmpeg `-af`. Тестируй на 200-симв. фразе |
| `system_prompt` | для LLM. Чёткий, < 500 слов. **Никаких реальных имён** — для owner-name используй `{owner}` placeholder. |
| `banks` | 9 категорий по 10-20 фраз. Каждая в характере персонажа, не «хи-хи». |
| `humor_genre` | свободная подпись для документации (`afor`, `ru_mat`, `philosophy`, `wild`, `cyber`...) |
| `forbidden_topics` | список тем для system_prompt block |
| `mirror_amplifier` | 0.5-2.0. Насколько персонаж зеркалит мимику. Stoic-герои ниже 1.0, эмоциональные выше. |
| `default_safe` | `true` если безопасно для незнакомцев / детей. `false` — edgy, opt-in. |

## Как добавить свой персонаж

1. Создай `server/characters/<name>.json` по образцу.
2. Запусти `python server/voice_banks/generate.py <name>` — батч сгенерит
   все pre-recorded `.ogg` из текстов `banks.*`.
3. Перезапусти сервер. Персонаж появится в Settings APK.
4. Тестируй: переключись, поприветствуй, дай злому/радостному triggers.

## Готовые персонажи

| ID | Голос | Жанр | Default-safe |
|---|---|---|---|
| `sage` | низкий тёплый | philosophy | ✅ |
| `baby_robot` | высокий детский | childlike-wonder | ✅ |
| `flatterer` | приподнятый | XIX-век-комплименты | ✅ |
| `bass_grumpy` | глубокий бас | dark-humor | ⚠️ edgy |
| `mowgli` | дикий | jungle-metaphors | ⚠️ edgy |
| `statham` | сухой бас | aphorism-action | ⚠️ edgy |

Edgy-персонажи в default-list, но на оригинальной (cenzured) Gemini —
без открытого мата, только грубовато по смыслу.
