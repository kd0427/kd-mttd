package com.mttd.data.items

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TTD 에서 파생한 `assets/item_names_ko.json` 로더.
 *
 * 앱 시작 시 1회 로드 (~113 KB). itemId → (koName, koType) 매핑.
 */
class ItemInfoLookup(context: Context) {

    private val map: Map<String, ItemInfo>

    init {
        map = try {
            val raw = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, ItemInfo>>(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to load $ASSET_PATH", t)
            emptyMap()
        }
        Log.i(TAG, "loaded ${map.size} item names")
    }

    fun lookup(itemId: String): ItemInfo? = map[itemId]

    /** UI 라벨용 안전 표시. 없으면 fallback. */
    fun displayName(itemId: String?): String = when {
        itemId == null -> "Unknown"
        else -> map[itemId]?.name ?: "Unknown (id=$itemId)"
    }

    fun displayType(itemId: String?): String? = itemId?.let { map[it]?.type }

    val size: Int get() = map.size

    @Serializable
    data class ItemInfo(
        val name: String = "",
        val type: String = "",
        val img: String = "",
    )

    companion object {
        private const val TAG = "mTTD.ItemInfo"
        private const val ASSET_PATH = "item_names_ko.json"
    }
}
