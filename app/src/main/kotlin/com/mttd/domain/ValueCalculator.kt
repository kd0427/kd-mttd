package com.mttd.domain

import com.mttd.data.prices.ObservedPriceStore
import com.mttd.data.prices.PriceRepository

/**
 * 픽업 이벤트 → 가치 계산기.
 *
 * 가격 우선순위:
 * 1. **경매장 직접 조회** ([ObservedPriceStore]) — 유저가 방금 본 호가라 가장 신뢰도가 높다
 * 2. 시세 스냅샷 ([PriceRepository]) — ETor 또는 TTD
 * 3. 없으면 0 (unpriced 로 분류)
 *
 * **자기 자신을 아이템 ID 로 매칭한 통화** (100200/100300 등) 도 그대로 가격 lookup
 * → 서버가 그것들에 대해서도 price 를 리턴하는 것이 확인됨 (self-priced, 대부분 1.0)
 */
class ValueCalculator(
    private val prices: PriceRepository,
    private val observed: ObservedPriceStore? = null,
) {

    /**
     * @return [Result]
     *         - unitPrice: 아이템 단가 (0 이면 미상)
     *         - totalValue: unitPrice * quantity (시장가 기준)
     *         - netValue: 경매장 세금 반영한 실수령 예상치 (아래 [netOf] 참조)
     *         - priced: 가격 확보 성공 여부
     *         - fromObserved: 경매장 조회 가격을 썼는지
     */
    fun compute(itemId: String?, quantity: Int): Result {
        val obs = observed?.priceOf(itemId)
        if (obs != null) {
            val total = obs.toDouble() * quantity
            return Result(obs, total, priced = true, fromObserved = true, netValue = netOf(itemId, total, quantity))
        }
        val unit = prices.priceOf(itemId)
        return if (unit != null) {
            val total = unit.toDouble() * quantity
            Result(unit, total, priced = true, netValue = netOf(itemId, total, quantity))
        } else {
            Result(0f, 0.0, priced = false)
        }
    }

    /**
     * 경매장 판매 시 실수령 예상치.
     *
     * 최초의 불꽃 결정(기본 통화, [BASE_CURRENCY_ID])은 그 자체가 화폐라 경매장에 파는 대상이
     * 아니므로 세금이 없다. 소비(quantity < 0, 나침반·비콘 등)는 판매가 아니라 손실이므로
     * 원가 그대로 차감하고 세금(할인)을 적용하지 않는다. 그 외 획득 아이템만 세율만큼 깎는다.
     */
    private fun netOf(itemId: String?, totalValue: Double, quantity: Int): Double = when {
        itemId == BASE_CURRENCY_ID -> totalValue
        quantity < 0 -> totalValue
        else -> totalValue * AUCTION_TAX_MULTIPLIER
    }

    data class Result(
        val unitPrice: Float,
        val totalValue: Double,
        val priced: Boolean,
        val fromObserved: Boolean = false,
        val netValue: Double = 0.0,
    )

    companion object {
        /** 가치 기준 통화 = 최초의 불꽃 결정. 경매장 판매 대상이 아니라 세금이 없다. */
        const val BASE_CURRENCY_ID = "100300"
        /** 경매장 판매 세금 1/8 고정 — 실수령 = 시장가 × 7/8. */
        const val AUCTION_TAX_MULTIPLIER = 7.0 / 8.0
    }
}
