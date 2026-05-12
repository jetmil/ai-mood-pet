package io.github.jetmil.aimoodpet.voice

import android.content.Context
import android.util.Log

/**
 * Каталог голосовых фраз для одного стиля (baby_robot / bass_grumpy / ...).
 * Парсит assets/voice/<styleName>/manifest.tsv. Формат: id\ttag\ttext.
 */
class VoiceBank(context: Context, val styleName: String) {

    private val byTag: Map<String, List<VoicePhrase>>
    val all: List<VoicePhrase>
    val basePath = "voice/$styleName"

    init {
        val parsed = mutableListOf<VoicePhrase>()
        try {
            context.assets.open("$basePath/manifest.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val parts = trimmed.split("\t")
                    if (parts.size >= 3) {
                        parsed += VoicePhrase(parts[0], parts[1], parts[2])
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "manifest read failed for style=$styleName", e)
        }
        all = parsed
        byTag = parsed.groupBy { it.tag }
        Log.i(TAG, "style=$styleName loaded ${all.size} phrases, tags=${byTag.keys}")
    }

    fun pick(tag: String, excludeIds: Collection<String>): VoicePhrase? {
        val pool = byTag[tag] ?: return null
        if (pool.isEmpty()) return null
        val available = pool.filter { it.id !in excludeIds }
        return (available.takeIf { it.isNotEmpty() } ?: pool).randomOrNull()
    }

    fun has(tag: String): Boolean = (byTag[tag]?.size ?: 0) > 0

    companion object { private const val TAG = "VoiceBank" }
}
