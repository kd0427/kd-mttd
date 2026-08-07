package com.mttd.data.log

/**
 * 모바일 로그 assembler — header 시작 → payload 라인 축적 → 매칭되는 end 만나면 flush.
 *
 * [LogLineParser.HeaderKind] 별로 매칭 규칙이 다름:
 * - ItemChange: `ProtoName=X start` ~ `ProtoName=X end`
 * - EnterAreaBegin: `OnEnterAreaBegin()` ~ `OnEnterAreaEnd()`
 *
 * 페이로드 안에 다른 헤더가 나오면 (드물지만 안전을 위해) 현재 메시지 버리고 새로 시작.
 */
class MessageAssembler {

    data class RawMessage(
        val header: MessageHeader,
        val data: List<String>,
    )

    private var current: MutableRawMessage? = null

    private data class MutableRawMessage(
        val header: MessageHeader,
        val data: MutableList<String>,
    )

    fun feed(line: String): RawMessage? {
        // 1) 새 헤더 감지가 최우선 (진행 중 메시지가 있어도 대체)
        val newHeader = LogLineParser.parseHeader(line)
        if (newHeader != null) {
            current = MutableRawMessage(newHeader, mutableListOf())
            return null
        }

        val c = current ?: return null

        // 2) 종료 신호
        if (LogLineParser.isMessageEnd(line, c.header.kind)) {
            val done = RawMessage(c.header, c.data.toList())
            current = null
            return done
        }

        // 3) 페이로드 라인 축적
        c.data += line
        return null
    }

    fun reset() { current = null }
}
