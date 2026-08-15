package com.mttd.data.log

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

/**
 * 로그 파일별 read offset 지속 저장소.
 *
 * DataStore(Preferences) 백엔드. 키는 파일 경로를 SHA-1 해시해서 사용
 * (경로가 길거나 특수문자를 포함해도 안전).
 */
class OffsetStore(private val context: Context) {

    /**
     * 방금 저장한 값을 프로세스 안에서 즉시 되읽기 위한 캐시.
     *
     * 폴러를 그 자리에서 다시 시작하면(설정의 "시작" 재탭) 이전 폴러의 종료 저장은
     * 비동기고 새 폴러의 [load] 는 곧바로 실행돼서, 디스크만 보면 최대 3 초(저장 debounce)
     * 뒤처진 값을 읽어 그 구간 라인을 다시 방출할 수 있다. 같은 프로세스 안에서는
     * 이 캐시가 항상 최신이라 그 창이 닫힌다.
     */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun load(logPath: String): Long =
        memory[logPath] ?: context.dataStore.data.map { it[keyFor(logPath)] ?: 0L }.first()

    suspend fun save(logPath: String, offset: Long) {
        memory[logPath] = offset
        context.dataStore.edit { it[keyFor(logPath)] = offset }
    }

    suspend fun clear(logPath: String) {
        memory.remove(logPath)
        context.dataStore.edit { it.remove(keyFor(logPath)) }
    }

    private fun keyFor(logPath: String) = longPreferencesKey("offset_${sha1(logPath)}")

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val Context.dataStore by preferencesDataStore(name = "log_offsets")
    }
}
