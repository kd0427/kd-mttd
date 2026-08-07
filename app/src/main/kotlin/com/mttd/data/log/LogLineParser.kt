package com.mttd.data.log

/**
 * mTTD 모바일 로그 (Android build) 헤더/마커 파서.
 *
 * 데스크톱 CN 버전과 로그 카테고리가 완전히 다름:
 * - 데스크톱: `GameLog: Display: [Game] ----Socket <dir> STT----<action>----SynId = N`
 * - 모바일: `TLLua: Display: [Game] ItemChange@ ProtoName=<action> start` (~ `end`)
 *
 * ETor 원본은 데스크톱 포맷 기준. 우리는 실기기 로그(Phase0 M2 검증)에서
 * 뽑은 모바일 포맷을 이 클래스로 이식.
 *
 * 예:
 * ```
 * [2026.08.06-07.12.09:924][332]TLLua: Display: [Game] ItemChange@ ProtoName=PickItems start
 * [2026.08.06-07.12.09:925][332]TLLua: Display: [Game] ItemChange@ ProtoName=PickItems end
 * ```
 *
 * 그리고 EnterArea 는 완전히 별개 형식:
 * ```
 * TLLua: Display: [Game] NetGameMgr:OnEnterAreaBegin()
 * TLLua: Display: [Game] NetGameMgr:OnEnterAreaEnd()
 * ```
 *
 * 이 파서는 두 타입을 모두 인식.
 */
object LogLineParser {

    // ItemChange@ ProtoName=X start/end
    private val itemChangeStartRegex = Regex(
        """^\[([^\]]+)\]\[[^\]]+\]TL(?:Lua|Shipping):\s+Display:\s+\[Game\]\s+ItemChange@\s+ProtoName=(\S+)\s+start\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val itemChangeEndRegex = Regex(
        """TL(?:Lua|Shipping):\s+Display:\s+\[Game\]\s+ItemChange@\s+ProtoName=(\S+)\s+end\s*$""",
        RegexOption.IGNORE_CASE,
    )

    // EnterArea begin/end 마커 — 별도 이벤트 스트림
    private val enterAreaBeginRegex = Regex(
        """^\[([^\]]+)\]\[[^\]]+\]TL(?:Lua|Shipping):\s+Display:\s+\[Game\]\s+NetGameMgr:OnEnterAreaBegin\(\).*""",
    )
    private val enterAreaEndRegex = Regex(
        """TL(?:Lua|Shipping):\s+Display:\s+\[Game\]\s+NetGameMgr:OnEnterAreaEnd\(\).*""",
    )

    // Spv3 이벤트 (특수 맵) — ItemChange@ start/end 로 표현됨 (ProtoName=Spv3Open 등)
    // 이건 itemChangeStartRegex 가 이미 커버.

    /** 메시지 헤더 감지. 반환 값 null 이면 header 아님. */
    fun parseHeader(line: String): MessageHeader? {
        // 1) ItemChange@ ProtoName=X start
        itemChangeStartRegex.matchEntire(line)?.let {
            val (ts, action) = it.destructured
            return MessageHeader(
                timestampRaw = ts,
                timestampEpochMs = timeToEpochMs(ts),
                direction = Direction.Recv,      // 모바일은 방향 구분 안 함
                action = action,
                synId = 0,
                kind = HeaderKind.ItemChange,
            )
        }
        // 2) NetGameMgr:OnEnterAreaBegin()
        enterAreaBeginRegex.matchEntire(line)?.let {
            val ts = it.groupValues[1]
            return MessageHeader(
                timestampRaw = ts,
                timestampEpochMs = timeToEpochMs(ts),
                direction = Direction.Recv,
                action = "EnterArea",
                synId = 0,
                kind = HeaderKind.EnterAreaBegin,
            )
        }
        return null
    }

    /** 메시지 종료 감지. header 의 kind 와 일치하는 end 신호. */
    fun isMessageEnd(line: String, kind: HeaderKind): Boolean = when (kind) {
        HeaderKind.ItemChange -> itemChangeEndRegex.containsMatchIn(line)
        HeaderKind.EnterAreaBegin -> enterAreaEndRegex.containsMatchIn(line)
    }

    /** payload 안에 또 다른 헤더가 나오면 assembler 가 새로 시작해야 하므로 감지용. */
    fun isAnyHeader(line: String): Boolean =
        itemChangeStartRegex.containsMatchIn(line) || enterAreaBeginRegex.containsMatchIn(line)

    /** ETor `timeToTimestamp`: `2025.10.18-08.57.57:933` → epoch ms + 8h (CN 서버 시간 보정). */
    fun timeToEpochMs(raw: String): Long = try {
        val (date, timeStr) = raw.split("-", limit = 2)
        val (yr, mo, day) = date.split(".").map { it.toInt() }
        val (timeNoMs, ms) = timeStr.split(":", limit = 2)
        val (h, mn, s) = timeNoMs.split(".").map { it.toInt() }
        val instant = java.time.LocalDateTime.of(yr, mo, day, h, mn, s, ms.toInt() * 1_000_000)
            .toInstant(java.time.ZoneOffset.UTC)
        instant.toEpochMilli() + 8L * 60 * 60 * 1000
    } catch (t: Throwable) {
        System.currentTimeMillis()
    }
}

enum class Direction { Send, Push, Recv, RecvPush }

enum class HeaderKind { ItemChange, EnterAreaBegin }

data class MessageHeader(
    val timestampRaw: String,
    val timestampEpochMs: Long,
    val direction: Direction,
    val action: String,
    val synId: Int,
    val kind: HeaderKind,
)
