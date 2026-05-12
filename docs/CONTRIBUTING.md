# Contributing

Спасибо что хочешь добавить что-то. Несколько простых правил.

## Лицензия — AGPL-3.0

Submitting PR значит ты соглашаешься что твой код лицензируется под
**AGPL-3.0**. Maintainer оставляет право предлагать проект под
коммерческой лицензией третьим сторонам (dual licensing); ты сохраняешь
copyright на свой код, но не блокируешь dual.

Если для тебя AGPL неприемлем — не контрибутируй, просто форкни и держи
свой fork private. Это нормально.

## Кодовый стиль

- **Python (server/)** — `ruff format` + `ruff check` стандартный.
  Type-hints обязательны для публичных функций. Loguru для логирования
  (не `print`), pydantic для конфигов/схем.
- **Kotlin (android/)** — стандартный Android Studio formatter
  (Ctrl+Alt+L). Compose-функции PascalCase, suspending-функции c
  результатом — `async X` префикс не нужен.
- **Markdown (docs/)** — 80 символов в строке. Заголовки `##` для основных
  секций. Без emoji-икон в начале строк.
- **Комментарии** — на русском или английском, без смешивания в одном
  файле. Объясни *почему*, а не *что* делает код.

## Workflow

1. **Открой issue** перед началом крупной работы. Согласуем направление,
   чтобы не делать впустую.
2. **Fork → branch → PR.** Имя ветки: `feature/<short>` или `fix/<short>`.
3. **Один PR — одна тема.** Не смешивай character.json + server refactor
   + UI redesign в одном PR.
4. **Описание PR:** что и зачем. Если есть скриншоты — приложи.
5. **Test plan:** напиши как проверить твою работу. Серверные изменения —
   импорт-тест + smoke `/health`. Android — что собирается через `gradlew
   assembleDebug`.

## Что приветствуется

- **Новые персонажи** — JSON-spec + 90 уникальных осмысленных фраз.
  См. [CHARACTERS.md](CHARACTERS.md).
- **Локализация** — английский для system_prompts и phrase banks (сейчас
  только русский). Структура: `characters/<id>.en.json` параллельно
  русскому файлу + поле `lang` в spec.
- **Новые жесты** в `vision/HandTracker.kt` — добавь label + handle в
  server-side `_comment_gesture`.
- **Performance** — улучшения CPU (HandTracker rate-limit, MediaPipe
  delegate=GPU и т.п.).
- **Документация** — особенно `DEPLOY.md` для разных VPS-провайдеров,
  `SECURITY.md` для конкретных угроз.

## Что НЕ приветствуется

- **Telemetry / analytics** в код без явного opt-in. Не добавляй
  Firebase/AppMetrica/Sentry SDK как dependency.
- **Captive-portal / dark patterns** в UI. Setup-screen должен пропускать
  юзера без обязательного логина.
- **Persistent ID** на устройстве которое уходит в облако. UUID для
  user_id остаётся локально, server видит его только в hello-msg.
- **Hardcoded URL** / API-key / persona owner-name. Всё конфигурируется.
- **Зависимости с GPL-incompatible лицензией.** AGPL-3.0 не позволяет
  linking с проприетарными closed-source библиотеками.

## Reporting bugs / security

- **Обычный баг** — open issue. Шаблон в `.github/ISSUE_TEMPLATE/bug.md`
  (TODO добавить).
- **Security vulnerability** — НЕ публичный issue. Через
  [GitHub Security Advisory](https://github.com/jetmil/ai-mood-pet/security/advisories/new)
  приватно. Или email `jetmil@proton.me`.

## Maintainer

Final say on architecture, persona direction, release timing —
[@jetmil](https://github.com/jetmil). Я уважаю это, не пытайся
"переголосовать" PR через downvotes.

## Code of conduct

Без манифеста — здравый смысл. Не оскорбляй людей в issues/PR/commit
messages, не делай harassment, не присваивай чужие patches себе. Если
кто-то ведёт себя плохо — пиши maintainer'у напрямую.
