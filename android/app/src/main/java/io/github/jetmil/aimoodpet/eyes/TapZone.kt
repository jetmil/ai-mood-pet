package io.github.jetmil.aimoodpet.eyes

/**
 * Зоны тапа по «лицу» тамагочи. MoodEngine.onTap() классифицирует
 * по нормализованным координатам и шлёт зональный mood-delta.
 */
enum class TapZone {
    Forehead,    // ласка по голове
    Eye,         // тыкнул в глаз — реальный fear+anger
    Mouth,       // щекотка
    Nose,        // играюсь
    EdgeMiss,    // промах в угол — недовольство
}
