package io.github.jetmil.aimoodpet.eyes

/**
 * Что нарисовать на лбу — emoji + цвет ауры.
 * `null` = пусто (нейтральное состояние).
 *
 * Приоритет (сверху вниз) определяется в EyesViewModel:
 *   thinking → sleeping → petting → strong emotion
 */
enum class ForeheadBadge(val emoji: String, val argb: Long) {
    Thinking ("💭", 0xFFB0B8C8),  // 💭
    Sleeping ("💤", 0xFF6E84A8),  // 💤
    Petting  ("💖", 0xFFFF7AB6),  // 💖
    Joy      ("✨",       0xFFFFD954),  // ✨
    Anger    ("💢", 0xFFFF5470),  // 💢
    Fear     ("⚡",       0xFFC2B5FF),  // ⚡
    Sadness  ("💧", 0xFF7AC8FF),  // 💧
    Surprise ("❗",       0xFFFFB060),  // ❗
    Disgust  ("🍃", 0xFF6FB87C),  // 🍃
    Trust    ("❤",       0xFFFF6B95),  // ❤
    Anticipation("❓",    0xFFFFE08A),  // ❓
    Curious  ("👀", 0xFFFFE08A),  // 👀
}
