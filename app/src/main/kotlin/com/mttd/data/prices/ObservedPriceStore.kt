package com.mttd.data.prices

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 게임 내 경매장에서 **직접 조회한** 시세.
 *
 * 서버 스냅샷([PriceRepository])보다 항상 우선한다. 유저가 방금 눈으로 본 호가라
 * 스냅샷보다 최신이고, 스냅샷에 없는 아이템도 커버되기 때문.
 *
 * 로그의 `XchgSearchPrice` 응답은 **최저가순 호가 목록**(관측상 100 건)을 주고
 * 수량 필드는 없다. 따라서 평균가 = Σ호가 ÷ 호가 건수.
 *
 * 앱 프로세스 메모리에만 둔다. 재시작하면 사라지고 다시 스냅샷 가격을 쓴다.
 */
class ObservedPriceStore {

    private val _prices = MutableStateFlow<Map<String, Observed>>(emptyMap())
    val prices: StateFlow<Map<String, Observed>> = _prices.asStateFlow()

    /**
     * 경매장 조회 결과 1건 기록.
     *
     * @param unitPrices 응답의 `unitPrices` 배열 (최저가순)
     */
    fun record(
        itemId: String,
        unitPrices: List<Double>,
        observedAtMs: Long,
        currencyId: String? = null,
    ) {
        if (itemId.isBlank() || unitPrices.isEmpty()) return
        val avg = unitPrices.sum() / unitPrices.size
        if (avg <= 0.0 || !avg.isFinite()) return

        val entry = Observed(
            itemId = itemId,
            avgPrice = avg.toFloat(),
            samples = unitPrices.size,
            lowest = unitPrices.min().toFloat(),
            highest = unitPrices.max().toFloat(),
            observedAtMs = observedAtMs,
            currencyId = currencyId,
        )
        _prices.value = _prices.value + (itemId to entry)
        Log.i(TAG, "observed $itemId avg=%.4f from ${unitPrices.size} listings".format(avg))
    }

    /** 관측 가격. 없으면 null → 호출측이 스냅샷 가격으로 폴백. */
    fun priceOf(itemId: String?): Float? =
        itemId?.let { _prices.value[it]?.avgPrice?.takeIf { p -> p > 0f } }

    fun clear() {
        _prices.value = emptyMap()
    }

    data class Observed(
        val itemId: String,
        /** 평균 호가 = Σ호가 ÷ 건수. */
        val avgPrice: Float,
        /** 평균을 낸 호가 건수. */
        val samples: Int,
        val lowest: Float,
        val highest: Float,
        val observedAtMs: Long,
        /** 호가 화폐. 결정(100300) 만 기록되고 가루(100200) 거래는 제외된다. */
        val currencyId: String? = null,
    )

    companion object {
        private const val TAG = "mTTD.Observed"
    }
}
