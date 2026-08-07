package com.mttd.data.maps

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 로그의 원시 맵 코드네임(`MapName = /Game/Art/Maps/.../<code>`)을 사람이 읽을 표시 이름으로 변환.
 *
 * `assets/map.json` — `<code>#<levelId>` (난이도/티어별 정확한 키, 예: `YongShuangBingPo#3000`) 또는
 * `<code>` 단독 키 → 표시 이름. 대부분의 항목(감시자 던전 등)은 `#<levelId>` 없이는 매칭되지 않는다
 * — `#` 뒤 숫자는 [handleEnterArea] 에서 파싱하는 LevelId 와 같은 값으로 추정된다
 * (같은 던전의 순차적 티어마다 LevelId 가 다르게 찍히는 것과 map.json 의 순차적 `#3000, #3001, ...`
 * 패턴이 일치). 실기기 로그로 아직 검증 전이라, 매칭 실패 시 조용히 다음 후보로 넘어가게 짜여 있다.
 *
 * `assets/map_alias.json` 의 `prefix` — 코드네임이 특정 접두사로 시작하면 매칭 (시즌 이벤트 맵 등
 * 접미사가 계속 바뀌는 것들).
 */
class MapNameLookup(context: Context) {

    private val exact: Map<String, String>
    private val prefixes: List<Pair<String, String>>

    init {
        val json = Json { ignoreUnknownKeys = true }
        exact = try {
            val raw = context.assets.open(MAP_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.decodeFromString<Map<String, String>>(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to load $MAP_ASSET_PATH", t)
            emptyMap()
        }
        prefixes = try {
            val raw = context.assets.open(ALIAS_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.decodeFromString<MapAliasFile>(raw).prefix.toList()
        } catch (t: Throwable) {
            Log.e(TAG, "failed to load $ALIAS_ASSET_PATH", t)
            emptyList()
        }
        Log.i(TAG, "loaded ${exact.size} map names, ${prefixes.size} prefix aliases")
    }

    /**
     * @param code    로그에서 뽑은 원시 맵 코드네임 (예: "YongShuangBingPo"). null 이면 매칭 시도 안 함.
     * @param levelId `EnterArea` 의 LevelId (예: "3000"). code 와 조합해 정확한 티어를 구분하는 데 쓰인다.
     * @return 매칭된 표시 이름, 없으면 null (호출측이 기존 fallback 을 쓰면 된다).
     */
    fun resolve(code: String?, levelId: String?): String? {
        if (code.isNullOrEmpty()) return null
        tryMatch(code, levelId)?.let { return it }
        // 실측: 로그의 코드네임 끝에 UE 가 붙인 것으로 보이는 숫자 접미사가 붙어 나올 때가 있다
        // (예: "YJ_XieDuYuZuo200" vs map.json 의 "YJ_XieDuYuZuo#5310"). 떼고 한 번 더 시도.
        val trimmed = code.trimEnd { it.isDigit() }
        if (trimmed.isNotEmpty() && trimmed != code) {
            tryMatch(trimmed, levelId)?.let { return it }
        }
        Log.i(TAG, "no match for code=$code levelId=$levelId")
        return null
    }

    private fun tryMatch(code: String, levelId: String?): String? {
        if (levelId != null) exact["$code#$levelId"]?.let {
            Log.i(TAG, "resolved code=$code levelId=$levelId via exact '#' key -> $it")
            return it
        }
        exact[code]?.let {
            Log.i(TAG, "resolved code=$code levelId=$levelId via exact code -> $it")
            return it
        }
        for ((prefix, name) in prefixes) {
            if (code.startsWith(prefix)) {
                Log.i(TAG, "resolved code=$code levelId=$levelId via prefix '$prefix' -> $name")
                return name
            }
        }
        return null
    }

    @Serializable
    private data class MapAliasFile(
        val prefix: Map<String, String> = emptyMap(),
    )

    companion object {
        private const val TAG = "mTTD.MapNames"
        private const val MAP_ASSET_PATH = "map.json"
        private const val ALIAS_ASSET_PATH = "map_alias.json"
    }
}
