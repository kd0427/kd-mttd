package com.mttd.data.log

/**
 * ETor 데스크톱 앱 `tor/main.js` line 34 의 excludePattern 이식.
 *
 * 이 정규식에 매치되는 라인은 assembler 로 넘기지 않음 (완전 skip).
 * 로그 노이즈를 줄여서 CPU 부담을 낮춘다.
 */
object LogLineFilter {
    private val exclude = Regex(
        "SoundTriggerTimestamps|GameplayLog|error:|Warning|PushMgr@RecvLogicMsg",
        RegexOption.IGNORE_CASE,
    )

    fun shouldExclude(line: String): Boolean = exclude.containsMatchIn(line)
}
