package com.mttd.data.prices

/**
 * 시세 데이터 출처.
 *
 * 두 소스 모두 **최초의 불꽃 결정(100300) = 1** 기준의 같은 단위를 쓰므로
 * 전환해도 수치 스케일이 달라지지 않는다. 다만 표본과 갱신 주기가 달라
 * 아이템별로 값 차이가 난다 (실측 예: 딥 스페이스 비콘 ETor 37.0 / TTD 35.6).
 */
enum class PriceSource(
    val id: String,
    val label: String,
    val description: String,
) {
    ETOR(
        id = "etor",
        label = "ETor",
        description = "시즌 스냅샷 (protobuf). 거의 모든 아이템에 가격이 있음",
    ),
    TTD(
        id = "ttd",
        label = "TTD (한국)",
        description = "ttdiablo.com 한국 시세. 거래량 있는 아이템만 가격이 붙음",
    ),
    ;

    companion object {
        fun fromId(id: String?): PriceSource = entries.firstOrNull { it.id == id } ?: ETOR
    }
}
