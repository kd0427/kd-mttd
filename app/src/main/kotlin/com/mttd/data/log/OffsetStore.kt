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

    suspend fun load(logPath: String): Long =
        context.dataStore.data.map { it[keyFor(logPath)] ?: 0L }.first()

    suspend fun save(logPath: String, offset: Long) {
        context.dataStore.edit { it[keyFor(logPath)] = offset }
    }

    suspend fun clear(logPath: String) {
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
