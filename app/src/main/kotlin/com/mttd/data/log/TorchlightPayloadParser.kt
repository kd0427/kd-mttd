package com.mttd.data.log

/**
 * ETor `tor/main.js` `parseTorchlightData` 함수의 Kotlin 이식.
 *
 * UE4 커스텀 트리 텍스트 포맷을 nested Map 으로 변환.
 *
 * 예 입력:
 * ```
 * +MapID [1001002]
 * +levelId [0]
 * +worldInitArgs+ownerPlayerId [814971382814871552]
 * |             +seed [1532413606]
 * +areaId [110_df24d82e-ae4f-11f0-b2ba-00000000005c]
 * ```
 *
 * 예 출력:
 * ```
 * {
 *   "MapID": "1001002",
 *   "levelId": "0",
 *   "worldInitArgs": {
 *     "ownerPlayerId": "814971382814871552",
 *     "seed": "1532413606"
 *   },
 *   "areaId": "110_df24d82e-ae4f-11f0-b2ba-00000000005c"
 * }
 * ```
 *
 * 규칙:
 * - `+key` 세그먼트로 시작하는 라인이 계층 표현.
 * - `+key [value]` 형태면 leaf.
 * - `+key` (bracket 없음) 이면 하위 컨테이너.
 * - `|` 로 시작하는 라인은 이전 라인의 key path 를 이어받는 continuation.
 * - `+errCode` 로 시작하는 라인은 무시.
 */
object TorchlightPayloadParser {

    // "+... " 세그먼트 뒤에 있는 "[value]" 추출 정규식. `\s+` 는 leaf key 와 [ 사이 공백 허용.
    private val leafRegex = Regex("""(\S+).*\[([^\]]*)\]""")

    fun parse(lines: List<String>): Map<String, Any> {
        val root = mutableMapOf<String, Any>()

        val filtered = lines.mapNotNull { l ->
            val t = l.trim()
            if (t.isEmpty()) null
            else if (t.startsWith("+errCode")) null
            else t
        }

        val lastKeys = mutableListOf<String>()

        for (line in filtered) {
            // '|' → 이전 라인의 key path 를 prefix 로 대체
            var copyLine = line
            for (k in lastKeys) {
                val idx = copyLine.indexOf('|')
                if (idx < 0) break
                copyLine = copyLine.substring(0, idx) + "+" + k + copyLine.substring(idx + 1)
            }

            val keys = copyLine.split('+').map { it.trim() }.filter { it.isNotEmpty() }

            // 다음 라인 continuation 용 lastKeys 갱신: leaf 가 아닌 key 만 저장
            lastKeys.clear()
            lastKeys += keys.filter { !leafRegex.containsMatchIn(it) }

            // 계층 순회하며 넣기
            var ctxMap: MutableMap<String, Any> = root
            for (i in keys.indices) {
                val k = keys[i]
                val m = leafRegex.find(k)
                if (m != null) {
                    val (name, value) = m.destructured
                    ctxMap[name] = value
                    // leaf 는 컨텍스트를 갱신하지 않음
                } else {
                    val child = ctxMap[k]
                    val map: MutableMap<String, Any> = if (child is MutableMap<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        child as MutableMap<String, Any>
                    } else {
                        val fresh = mutableMapOf<String, Any>()
                        ctxMap[k] = fresh
                        fresh
                    }
                    ctxMap = map
                }
            }
        }

        return root
    }
}
