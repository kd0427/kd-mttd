package com.mttd.data.prices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * TTD (ttdiablo.com) 한국 시세 API.
 *
 * 단일 엔드포인트: `GET https://ttdiablo.com/ttd_price.json` (약 310 KB)
 *
 * 응답은 `{ "<itemId>": { name, type, price, ... } }` 형태의 flat map.
 * 시즌 개념이 없어 [PriceApi] 와 달리 후보 스윕이 필요 없다.
 *
 * 실측(2026-08-07): 1684 개 아이템 중 554 개에 `price > 0`.
 * 거래량이 없는 아이템은 `price: 0` 으로 내려온다.
 */
class TtdPriceApi {

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** @return itemId → TTD 항목. 가격이 0 인 것도 포함하므로 걸러 쓰는 건 호출측 책임. */
    suspend fun fetchPrices(): Map<String, Item> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(URL).get().build()
        val resp = client.newCall(req).awaitResponse()
        resp.use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $URL")
            val body = r.body?.string() ?: throw IOException("empty body")
            json.decodeFromString<Map<String, Item>>(body)
        }
    }

    @Serializable
    data class Item(
        val name: String = "",
        val type: String = "",
        val price: Float = 0f,
    )

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) { cont.resume(response) {} }
                override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            })
            cont.invokeOnCancellation {
                try { cancel() } catch (_: Throwable) {}
            }
        }

    companion object {
        const val URL = "https://ttdiablo.com/ttd_price.json"
    }
}
