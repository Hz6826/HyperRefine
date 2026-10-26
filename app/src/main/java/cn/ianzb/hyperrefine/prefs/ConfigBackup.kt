package cn.ianzb.hyperrefine.prefs

import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一配置导出 / 导入（JSON）。
 */
object ConfigBackup {

    private const val SKIP_PREFIX = "runtime_"

    /** 迁移标记不是用户配置项，导入时保留，但不计入“已更新”数量。 */
    private const val MIGRATION_PREFIX = "migration_"

    /** 导入结果；[changedCount] 只统计导入后值发生变化的配置项。 */
    data class ImportResult(
        val success: Boolean,
        val changedCount: Int,
    )

    fun exportJson(): String {
        val json = JSONObject()
        PrefsStore.getAll().forEach { (key, value) ->
            if (key.startsWith(SKIP_PREFIX)) return@forEach
            when (value) {
                is Set<*> -> json.put(key, JSONArray(value))
                else -> json.put(key, value)
            }
        }
        return json.toString(2)
    }

    fun importJson(json: String): ImportResult {
        return try {
            val obj = JSONObject(json)
            // 先完整解析，避免解析中途失败时已写入一部分配置。
            val incoming = linkedMapOf<String, Any?>()
            for (key in obj.keys()) {
                if (key.startsWith(SKIP_PREFIX)) continue
                val raw = obj.get(key)
                incoming[key] = when (raw) {
                    is JSONArray -> (0 until raw.length()).map { raw.getString(it) }.toSet()
                    JSONObject.NULL -> null
                    is Double -> raw.toFloat()
                    else -> raw
                }
            }

            val current = PrefsStore.getAll()
            var changedCount = 0
            for ((key, value) in incoming) {
                if (!key.startsWith(MIGRATION_PREFIX) && !valuesEqual(current[key], value)) {
                    changedCount++
                }
                PrefsStore.put(key, value)
            }
            ConfigState.reload()
            ImportResult(success = true, changedCount = changedCount)
        } catch (_: Throwable) {
            ImportResult(success = false, changedCount = 0)
        }
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is Set<*> && b is Set<*> ->
            a.filterIsInstance<String>().toSet() == b.filterIsInstance<String>().toSet()
        else -> a == b
    }
}
