package com.mttd.domain

import com.mttd.data.items.ItemInfoLookup
import com.mttd.data.log.HeaderKind
import com.mttd.data.log.MessageAssembler
import com.mttd.data.maps.MapNameLookup
import com.mttd.domain.models.MapRun
import com.mttd.domain.models.PickupSummary
import com.mttd.domain.models.SessionState
import com.mttd.domain.models.TimeSample
import com.mttd.domain.models.TimeTrackingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 게임 이벤트 스트림을 [SessionState] 로 집계.
 *
 * 세션 시작/종료 를 명시적으로 제어 (수동 시작/종료 버튼).
 * 세션이 비활성일 때 들어오는 이벤트는 UI 표시용으로 흘리기만 하고 카운터엔 반영 안 함.
 */
class SessionAggregator(
    private val itemInfo: ItemInfoLookup,
    private val valueCalculator: ValueCalculator? = null,
    private val observedPrices: com.mttd.data.prices.ObservedPriceStore? = null,
    private val mapNames: MapNameLookup? = null,
    /** 완료된 회차를 디스크로 내리는 콜백. null 이면 메모리에만 둔다 (테스트용). */
    private val onRunFinished: ((MapRun) -> Unit)? = null,
) {
    // 항상 active. ETor 데스크톱과 동일한 UX (명시적 세션 시작/종료 없음).
    // baselineReady = false 로 시작 → 가방 스냅샷(정렬/귀환/재접속) 관측 후 자동 true.
    private val _state = MutableStateFlow(
        SessionState(
            active = true,
            startedAtMs = System.currentTimeMillis(),
            baselineReady = false,
        )
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * 맵 안/밖 판정이 바뀐 이력. 진단 화면에서만 쓴다.
     *
     * @param logTime 그 판정을 유발한 **게임 로그 줄의 시각** (`HH:mm:ss`). 앱이 그 줄을 읽은
     *   시각([atMs])과는 다르다 — 폴러는 최대 5초에 한 번 읽으므로, 게임에서 1초 만에 끝난
     *   전환이 앱 시각으로는 8~10초 벌어져 보인다. 진단에는 게임 시각이 맞다.
     */
    data class MapPresenceEvent(
        val atMs: Long,
        val inMap: Boolean,
        val reason: String,
        val logTime: String? = null,
    )

    private val _mapPresenceLog = MutableStateFlow<List<MapPresenceEvent>>(emptyList())
    val mapPresenceLog: StateFlow<List<MapPresenceEvent>> = _mapPresenceLog.asStateFlow()

    /**
     * 세션 누적을 전부 0 으로 되돌린다.
     *
     * **플레이어가 지금 어디 있는지는 지우지 않는다.** 리셋은 "여태 센 걸 버린다" 지
     * "맵 밖으로 나갔다" 가 아니다. 예전엔 [SessionState] 를 통째로 새로 만들면서 `inMap` 까지
     * false 로 밀었는데, `inMap` 을 다시 true 로 만들 수 있는 경로가 맵 열기(`Spv3Open`) 하나뿐이라
     * **맵 안에서 리셋하면 그 맵을 도는 내내 MAP_ONLY 시간이 0 에 멈춰 있었다.**
     *
     * `currentAreaId`/`lastEnterAreaMs` 도 같이 들고 간다 — 이걸 비우면 [handleEnterArea] 의
     * 중복 제거가 뚫려서, 같은 맵의 다음 `EnterArea` 가 새 지역으로 오인되며 `inMap` 을 도로
     * false 로 덮는다.
     *
     * 시계 자체는 `baselineReady = false` 라 멈춰 있고, 가방 스냅샷이 오면
     * [markBaselineReady] 가 `inMap` 을 보고 알아서 다시 돌린다. 가방 정렬은 맵 안에서도 된다.
     */
    fun resetSession() {
        val prev = _state.value
        _state.value = SessionState(
            active = true,
            startedAtMs = System.currentTimeMillis(),
            baselineReady = false,   // 다시 가방 정렬 관측할 때까지 계산 대기
            timeTrackingMode = prev.timeTrackingMode,
            // 위치 상태 — 누적이 아니라 "지금 상태" 라 유지한다.
            inMap = prev.inMap,
            currentAreaId = prev.currentAreaId,
            currentMapId = prev.currentMapId,
            currentMapName = prev.currentMapName,
            lastEnterAreaMs = prev.lastEnterAreaMs,
            // 일시정지·거래소도 "지금 상태" 다. 리셋이 이걸 지우면 중지해 둔 세션이 소리 없이
            // 다시 돌고, 거래소 안에서 리셋했을 땐 화면(거래소)과 상태가 어긋난 채로 남는다
            // — 거래소를 닫는 신호는 한 번뿐이라 그 뒤로는 스스로 맞춰지지 않는다.
            paused = prev.paused,
            pausedSinceMs = if (prev.paused) System.currentTimeMillis() else null,
            inExchange = prev.inExchange,
        )
        slotLastCount.clear()
        slotKeyByPosition.clear()
        pendingSlot = null
        latestMapCode = null
        currentRunByItem.clear()
        finishedRuns.clear()
        sessionItemTotals.clear()
        currentRunId = -1
        // 같은 맵에 그대로 서 있으므로, 리셋 직후 암묵적으로 열리는 회차도 이 맵 이름을 단다.
        currentRunMapName = prev.currentMapName
        nextRunId = 1
        awaitingMapArea = false
        // 거래소 상태를 그대로 들고 가므로, "이 pause 는 우리가 건 것" 이라는 표시도 같이
        // 유지해야 거래소를 닫을 때 자동으로 풀린다.
        pausedByExchange = pausedByExchange && prev.inExchange
    }

    fun pauseSession() {
        _state.update {
            if (it.paused) it
            else {
                val now = System.currentTimeMillis()
                val mapChunk = it.mapElapsedSinceMs?.let { since -> (now - since).coerceAtLeast(0) } ?: 0
                val currentMapChunk = it.currentMapElapsedSinceMs?.let { since -> (now - since).coerceAtLeast(0) } ?: 0
                it.copy(
                    paused = true,
                    pausedSinceMs = now,
                    mapElapsedAccumulatedMs = it.mapElapsedAccumulatedMs + mapChunk,
                    mapElapsedSinceMs = null,
                    currentMapElapsedAccumulatedMs = it.currentMapElapsedAccumulatedMs + currentMapChunk,
                    currentMapElapsedSinceMs = null,
                )
            }
        }
    }

    fun resumeSession() {
        _state.update {
            if (!it.paused) return@update it
            val since = it.pausedSinceMs ?: return@update it
            val now = System.currentTimeMillis()
            val chunk = (now - since).coerceAtLeast(0)
            it.copy(
                paused = false,
                pausedSinceMs = null,
                pausedAccumulatedMs = it.pausedAccumulatedMs + chunk,
                mapElapsedSinceMs = if (it.inMap && it.baselineReady) now else null,
                currentMapElapsedSinceMs = if (it.inMap && it.baselineReady) now else null,
            )
        }
    }

    fun togglePause() {
        if (_state.value.paused) resumeSession() else pauseSession()
    }

    /** 설정 화면에서 고른 시간 집계 기준을 즉시 반영한다. */
    fun setTimeTrackingMode(mode: TimeTrackingMode) {
        _state.update { if (it.timeTrackingMode == mode) it else it.copy(timeTrackingMode = mode) }
    }

    /** 거래소 진입 시 우리가 자동으로 걸었던 pause 인지 — 유저가 직접 pause 한 건 우리가 안 푼다. */
    private var pausedByExchange = false

    private fun enterExchange() {
        if (_state.value.inExchange) return
        if (!_state.value.paused) {
            pauseSession()
            pausedByExchange = true
        }
        _state.update { it.copy(inExchange = true) }
        refreshHoldings()
    }

    private fun exitExchange() {
        if (!_state.value.inExchange) return
        _state.update { it.copy(inExchange = false) }
        if (pausedByExchange) {
            resumeSession()
            pausedByExchange = false
        }
    }

    /** 현재 보유 아이템을 가치 내림차순으로 재계산해 state 에 반영. 수동 새로고침에서도 호출. */
    fun refreshHoldings() {
        _state.update { it.copy(holdings = computeHoldings()) }
    }

    /**
     * [slotLastCount] (슬롯별 절대 수량) 를 itemId 기준으로 합산해 가치순으로 정렬.
     * 이름을 모르는 아이템(item_names_ko.json 에 없음)은 노이즈라 제외하고,
     * 표시량도 상위 [MAX_HOLDINGS] 개로 제한한다.
     */
    private fun computeHoldings(): List<PickupSummary> {
        val totals = HashMap<String, Int>()
        for ((slotKey, qty) in slotLastCount) {
            if (qty <= 0) continue
            val itemId = extractItemId(slotKey) ?: continue
            totals[itemId] = (totals[itemId] ?: 0) + qty
        }
        return totals.mapNotNull { (itemId, qty) ->
            val info = itemInfo.lookup(itemId)
            val name = info?.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val v = valueCalculator?.compute(itemId, qty)
            PickupSummary(
                timestampMs = System.currentTimeMillis(),
                action = "Holding",
                itemId = itemId,
                itemName = name,
                itemType = info.type,
                iconUrl = info.img.takeIf { it.isNotBlank() },
                quantity = qty,
                unitPrice = v?.unitPrice ?: 0f,
                value = v?.totalValue ?: 0.0,
                netValue = v?.netValue ?: 0.0,
            )
        }.sortedByDescending { it.value }.take(MAX_HOLDINGS)
    }

    /** 슬롯 키 두 형태 모두 처리: `<baseId>_<uuid>` 또는 fallback `page:slot:itemId`. */
    private fun extractItemId(slotKey: String): String? {
        if (slotKey.contains(':')) return slotKey.substringAfterLast(':')
        val underscoreIdx = slotKey.indexOf('_')
        return if (underscoreIdx > 0) slotKey.substring(0, underscoreIdx) else null
    }

    // 모바일 로그에서 뽑을 수 있는 필드 정규식들.
    private val levelInfoRegex = Regex("""LevelUid,\s*LevelType,\s*LevelId\s*=\s*(\d+)\s+(\d+)\s+(\d+)""")

    /**
     * 슬롯 상태 라인. 두 변종이 있고 포맷은 동일하다:
     * - `ItemChange@ Update Id=<baseId>_<uuid> BagNum=N in PageId=X SlotId=Y` — 기존 슬롯 수량 변경
     * - `ItemChange@ Add    Id=<baseId>_<uuid> BagNum=N in PageId=X SlotId=Y` — **빈 슬롯에 신규 배치**
     *
     * `Add` 를 놓치면 "처음 얻는 아이템 / 다 쓰고 다시 얻은 아이템" 의 획득이 통째로 유실된다
     * (예: 딥 스페이스 비콘을 전부 소모한 뒤 다시 드랍받는 경우).
     */
    private val slotLineRegex = Regex(
        """ItemChange@\s+(?:Update|Add)\s+Id=(\S+)\s+BagNum=(\d+)\s+in\s+PageId=(\d+)\s+SlotId=(\d+)"""
    )
    /**
     * 슬롯 전체 제거 — count 없이 `Delete` 만 찍힌다 (경매 등록, 장비 분해 등).
     * `Update`/`Add` 처럼 다음 줄 Modfy 를 기다릴 필요가 없다: Delete 자체가 이미 확정된
     * "이 슬롯 count=0" 이벤트라 바로 [handleModfy] 로 넘긴다.
     *
     * 실기기 로그 확인(2026-08-08, 경매장 판매 등록):
     *   ItemChange@ ProtoName=XchgForSale start
     *   ItemChange@ Delete Id=400008_<uuid> in PageId=103 SlotId=4
     *   BagMgr@:RemoveBagItem PageId = 103 SlotId = 4
     *   ItemChange@ ProtoName=XchgForSale end
     */
    private val slotDeleteRegex = Regex(
        """ItemChange@\s+Delete\s+Id=(\S+)\s+in\s+PageId=(\d+)\s+SlotId=(\d+)"""
    )
    // 실제 픽업 이벤트: BagMgr@:Modfy BagItem PageId = X SlotId = Y ConfigBaseId = Z Num = N
    private val modfyRegex = Regex("""BagMgr@:Modfy\s+BagItem\s+PageId\s*=\s*(\d+)\s+SlotId\s*=\s*(\d+)\s+ConfigBaseId\s*=\s*(\d+)\s+Num\s*=\s*(\d+)""")
    // 인벤 로드: BagMgr@:InitBagData PageId = X SlotId = Y ConfigBaseId = Z Num = N
    private val initBagFullRegex = Regex("""BagMgr@:InitBagData\s+PageId\s*=\s*(\d+)\s+SlotId\s*=\s*(\d+)\s+ConfigBaseId\s*=\s*(\d+)\s+Num\s*=\s*(\d+)""")

    /**
     * 직전 Update/Add 라인. 바로 다음 줄이 **같은 슬롯의 Modfy** 면 실제 변화 이벤트이고,
     * 그렇지 않으면 가방 스냅샷(로그인·귀환 시 대량 배치) 이므로 baseline 으로 확정한다.
     *
     * 17 MB 실측 로그 검증: Update/Add 484 건 중 163 건이 바로 다음 줄 Modfy 와 짝을 이루고
     * (= 실제 변화), 나머지 321 건은 전부 대량 스냅샷 구간에 있었다.
     */
    private class PendingSlot(
        val key: String,
        val pageId: String,
        val slotId: String,
        val bagNum: Int,
    )
    private var pendingSlot: PendingSlot? = null

    /**
     * `PageId:SlotId` → 슬롯 인스턴스 키(`<baseId>_<uuid>`).
     * InitBagData 라인에는 uuid 가 없어서 이 인덱스로 해석한다.
     * 대량 스냅샷에서 Update 배치가 InitBagData 배치보다 항상 먼저 오므로 커버리지 100% (실측 321/321).
     */
    private val slotKeyByPosition = HashMap<String, String>()

    // ItemChange@ ProtoName=X start / end 마커 감지 (assembler 밖에서도 in-block 상태 추적)
    private val pickItemsStartRegex = Regex("""ItemChange@\s+ProtoName=PickItems?\s+start""")
    private val pickItemsEndRegex = Regex("""ItemChange@\s+ProtoName=PickItems?\s+end""")
    // 소비 이벤트 블록 (맵 열기 = 재료 소비)
    private val consumeStartRegex = Regex("""ItemChange@\s+ProtoName=(Spv3Open|Spv3Enter|InputArea|XchgSyncSoldSale)\s+start""")
    private val consumeEndRegex = Regex("""ItemChange@\s+ProtoName=(Spv3Open|Spv3Enter|InputArea|XchgSyncSoldSale)\s+end""")

    /**
     * 맵 열기 = 이번 런의 시작. 여기서 나침반/비콘/탐침을 소비하므로
     * "이번 진입" 목록은 이 시점에 비워야 소비(마이너스) 항목이 목록에 남는다.
     *
     * 이전에는 OnEnterAreaBegin 에서 비웠는데, 실측 로그상 Spv3Open 소비 라인 ~100 ms 뒤에
     * OnEnterAreaBegin 이 오기 때문에 소비 항목이 화면에 뜨기도 전에 지워졌다.
     */
    private val mapOpenStartRegex = Regex("""ItemChange@\s+ProtoName=Spv3Open\s+start""")
    /** 맵에서 지역 선택 화면으로 나갈 때 찍히는 전환. 이 순간 맵 전용 시계를 멈춘다. */
    private val mapExitStartRegex = Regex(
        """ItemChange@\s+ProtoName=InputArea\s+start""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 거래소(경매장, AuctionHouseV2) 화면 진입/퇴장. `ItemChange@` 블록이 아니라 UI 페이지
     * 전환 로그라 별도 마커. 실기기 로그 캡처로 확인 (2026-08-08, 2회 진입/퇴장 모두 일치):
     *   TipMsgShowMgr@DispatchPageRunChange PageName = AuctionHouseV2 , PageRunState = Run
     *   TipMsgShowMgr@DispatchPageRunChange PageName = AuctionHouseV2 , PageRunState = Destory
     * "Destory" 는 게임 로그 원문의 오타 — 실제로 그렇게 찍힌다.
     */
    private val exchangeEnterRegex = Regex("""PageName\s*=\s*AuctionHouseV2\s*,\s*PageRunState\s*=\s*Run""")
    private val exchangeExitRegex = Regex("""PageName\s*=\s*AuctionHouseV2\s*,\s*PageRunState\s*=\s*Destory""")

    private enum class BlockContext { NONE, PICKUP, CONSUME }
    private var blockContext: BlockContext = BlockContext.NONE

    /**
     * 가방 전체 스냅샷 시작 신호 — 이걸 관측해야 픽업 계산이 시작된다.
     *
     * 세 가지 변종을 모두 받는다. 모바일 클라이언트에서는 `ResetItemsLayout` 이
     * 실측 로그(17 MB) 에 단 한 번도 안 나왔고, 실제로는
     * `ItemChange@ Reset PageId=<n>` → `Update` 배치 → `InitBagData` 배치 순서로 찍힌다.
     * `Reset` 이 배치의 맨 앞이라 가장 빨리 잡힌다.
     */
    private val bagSnapshotStartRegex = Regex(
        """ItemChange@\s+(?:ProtoName=ResetItemsLayout\s+start|Reset\s+PageId=)"""
    )

    // ── 경매장 시세 조회 (XchgSearchPrice) ────────────────────────────────────
    //
    // Send 에 조회 대상 아이템, Recv 에 호가 목록이 오고 **SynId 로 짝**을 맞춰야 한다.
    //
    //   ----Socket SendMessage STT----XchgSearchPrice----SynId = 31883
    //   +typ3 [63]
    //   +filters+1+refer [5040]              ← itemId
    //   ----Socket SendMessage End----
    //
    //   ----Socket RecvMessage STT----XchgSearchPrice----SynId = 31883
    //   +errCode
    //   +prices+1+currency [100300]          ← 화폐 (결정이어야 우리 단위와 일치)
    //   |      | +unitPrices+1 [3.0]         ← 최저가순 호가. 관측상 100 건
    //   |      | |          +2 [3.0]
    //   ----Socket RecvMessage End----
    private val xchgSendHeaderRegex = Regex("""SendMessage\s+STT----XchgSearchPrice----SynId\s*=\s*(\d+)""")
    private val xchgRecvHeaderRegex = Regex("""RecvMessage\s+STT----XchgSearchPrice----SynId\s*=\s*(\d+)""")
    private val socketEndRegex = Regex("""----Socket\s+\w+Message\s+End----""")
    private val xchgReferRegex = Regex("""\+filters\+\d+\+refer\s*\[(\d+)\]""")
    private val xchgCurrencyRegex = Regex("""\+currency\s*\[(\d+)\]""")
    /** `+<n> [<price>]` — `+prices+1+currency [..]` 처럼 숫자 뒤에 바로 `[` 가 안 오는 건 안 걸린다. */
    private val xchgUnitPriceRegex = Regex("""\+\d+\s+\[([0-9]*\.?[0-9]+)\]""")

    private enum class XchgMode { NONE, SEND, RECV }
    private var xchgMode = XchgMode.NONE
    private var xchgSynId: String? = null
    private var xchgLineCount = 0
    private var xchgInUnitPrices = false
    private var xchgCurrency: String? = null
    private val xchgUnitPrices = ArrayList<Double>(128)
    /** SynId → itemId. Send 와 Recv 를 잇는다. 오래된 건 알아서 밀려나게 크기 제한. */
    private val xchgItemBySynId = object : LinkedHashMap<String, String>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    }

    // MapName tracking (기존)
    private val mapNameRegex = Regex("""(?:Load)?MapName\s*=\s*/Game/Art/Maps/[^/]+/([^/\s,]+)""")

    @Volatile
    private var latestMapCode: String? = null

    /**
     * 이번 런(맵 열기 → 다음 맵 열기) 동안 아이템별 누적 합계. key = itemId.
     * 맵을 열 때 초기화. 매 변화마다 갱신되고 state.recentPickups 를 여기서 정렬해서 만듦.
     */
    private val currentRunByItem = mutableMapOf<String, PickupSummary>()

    /**
     * 완료된 회차 **요약**. 아이템 목록은 디스크로 내려가 여기엔 없다.
     * 진행 중인 회차만 [currentRunByItem] 에 아이템을 들고 있다.
     */
    private val finishedRuns = mutableListOf<MapRun>()

    /** 세션 전체 아이템 합계. key = itemId. 증분 갱신 (회차 전체 재훑기 방지). */
    private val sessionItemTotals = LinkedHashMap<String, PickupSummary>()
    private var currentRunId: Long = -1
    private var currentRunStartedAtMs: Long = 0
    private var currentRunMapName: String? = null
    private var nextRunId: Long = 1
    /** Spv3Open 뒤 처음 들어오는 지역은 실제 맵 진입으로 본다. */
    private var awaitingMapArea = false

    /**
     * 인벤토리 슬롯 별 마지막 관측 count. 실제 획득량(delta) 계산에 사용.
     * key: `<baseId>_<UUID>` (한 슬롯 인스턴스에 고정 UUID 가 붙고, 가방 정렬로도 안 바뀜)
     */
    private val slotLastCount = HashMap<String, Int>()

    /**
     * Assembler 에서 완성된 메시지를 소비.
     *
     * 헤더 종류 별로 분기:
     * - ItemChange: PickItem(s) 이면 payload 에서 ConfigBaseId 추출 → 픽업 카운트
     * - EnterAreaBegin: 현재 지역/맵 이름 갱신 (맵핑 횟수는 맵 열기에서 센다)
     */
    fun consume(msg: MessageAssembler.RawMessage) {
        currentLogTime = logClockOf(msg.header.timestampRaw)
        when (msg.header.kind) {
            // ItemChange 는 이제 observeLine 의 Modfy 감지로 처리 → 여기서 중복 처리 안 함
            HeaderKind.ItemChange -> Unit
            HeaderKind.EnterAreaBegin -> handleEnterArea(msg)
        }
    }

    /**
     * assembler 를 통과하지 않는 라인들도 스캔해서 슬롯 변화를 직접 집계한다.
     *
     * 라인 종류:
     * - `ItemChange@ Update|Add Id=… BagNum=N in PageId=X SlotId=Y` — 슬롯 상태 라인.
     *   바로 다음 줄이 같은 슬롯 Modfy 면 **실제 변화**, 아니면 **가방 스냅샷 baseline**.
     * - `BagMgr@:Modfy …` — 확정된 변화. 직전 슬롯 라인의 uuid 와 묶어서 delta 계산.
     * - `BagMgr@:InitBagData …` — 가방 스냅샷 baseline (uuid 는 position 인덱스로 해석).
     * - `MapName = …` — 맵 코드네임.
     */
    fun observeLine(line: String) {
        // ── 빠른 사전 필터 ──────────────────────────────────────────────
        // 실측(157,351 줄): 관심 대상은 1.25% 뿐이고 98.7% 는 렌더러/오디오 로그다.
        // 그 전부에 정규식 10 개를 돌리면 플레이 중 초당 1,000 회 매칭이 헛돈다.
        // 부분 문자열 검사는 정규식보다 훨씬 싸므로 여기서 먼저 걸러낸다.
        //
        // 경매장 블록(RECV) 안에서는 `|  +12 [3.0]` 같은 연속 줄에 키워드가 없으므로
        // 필터를 건너뛰어야 한다.
        if (xchgMode == XchgMode.NONE && !isInteresting(line)) return
        currentLogTime = logClockOf(line)

        // 경매장 조회 블록 안이면 그쪽에서 소비. (블록은 짧고 다른 이벤트가 끼지 않는다)
        if (consumeXchgLine(line)) return

        // 블록 컨텍스트 추적 (Modfy 첫 sighting 시 방향 힌트)
        when {
            pickItemsStartRegex.containsMatchIn(line) -> blockContext = BlockContext.PICKUP
            consumeStartRegex.containsMatchIn(line) -> blockContext = BlockContext.CONSUME
            pickItemsEndRegex.containsMatchIn(line) -> blockContext = BlockContext.NONE
            consumeEndRegex.containsMatchIn(line) -> blockContext = BlockContext.NONE
        }

        // 거래소 진입/퇴장 — 파밍 집계와 무관한 UI 전환이므로 다른 파싱과 독립적으로 처리.
        if (exchangeEnterRegex.containsMatchIn(line)) { enterExchange(); return }
        if (exchangeExitRegex.containsMatchIn(line)) { exitExchange(); return }

        // 맵 열기 = 새 런 시작. 이 직후의 소비(마이너스) 항목부터 "이번 진입" 에 쌓인다.
        if (mapOpenStartRegex.containsMatchIn(line)) startNewRun()
        // 지역 선택으로 복귀하면 시간은 즉시 멈춘다. 여기서 `awaitingMapArea`도 반드시
        // 비워야 한다. 이를 남겨 두면, 귀환 직후에 도착하는 마을 EnterArea를 직전
        // Spv3Open의 맵 진입으로 오인해 타이머가 다시 시작될 수 있다.
        if (mapExitStartRegex.containsMatchIn(line)) {
            awaitingMapArea = false
            setMapPresence(false, "지역 선택 복귀(InputArea)")
        }

        // 가방 스냅샷 시작 → 계산 시작 (게임 응답 배치를 다 기다리지 않고 바로)
        if (bagSnapshotStartRegex.containsMatchIn(line)) {
            flushPendingSlotAsBaseline()
            markBaselineReady()
            return
        }

        // A) Update / Add → 다음 줄이 같은 슬롯 Modfy 인지 보고 판정 (변화 vs baseline)
        val slot = slotLineRegex.find(line)
        if (slot != null) {
            flushPendingSlotAsBaseline()
            val key = slot.groupValues[1]
            val bagNum = slot.groupValues[2].toIntOrNull()
            val pageId = slot.groupValues[3]
            val slotId = slot.groupValues[4]
            updatePositionKey(pageId, slotId, key)
            if (bagNum != null) pendingSlot = PendingSlot(key, pageId, slotId, bagNum)
            return
        }

        // A2) Delete → 슬롯 전체 제거. count=0 으로 확정이라 lookahead 없이 바로 처리.
        val del = slotDeleteRegex.find(line)
        if (del != null) {
            flushPendingSlotAsBaseline()
            val key = del.groupValues[1]
            val pageId = del.groupValues[2]
            val slotId = del.groupValues[3]
            val itemId = extractItemId(key) ?: key
            // 이 슬롯이 이번 세션에서 baseline 합성 키(page:slot:itemId)로만 잡혀 있고 실거래
            // Update/Add 로 uuid 키 재확인이 한 번도 없었다면, updatePositionKey 없이 바로
            // handleModfy 를 부르면 옛 합성 키가 slotLastCount 에 양수로 남아 computeHoldings()
            // 합산에서 유령 보유량으로 잡힌다 — 팔았는데 자산 목록에 계속 남는 버그.
            // Update/Add 경로(위 A1)와 똑같이 포지션 점유 키부터 정리한다.
            updatePositionKey(pageId, slotId, key)
            handleModfy(
                slotUuid = key,
                itemId = itemId,
                pageId = pageId,
                totalCountInSlot = 0,
                timestampMs = System.currentTimeMillis(),
            )
            return
        }

        // B) Modfy → 실제 변화 이벤트 (PickItems 블록 유무와 무관하게)
        val modfy = modfyRegex.find(line)
        if (modfy != null) {
            val pageId = modfy.groupValues[1]
            val slotId = modfy.groupValues[2]
            val itemId = modfy.groupValues[3]
            val currentCount = modfy.groupValues[4].toIntOrNull()

            val p = pendingSlot
            pendingSlot = null
            val matchedPending: Boolean
            val key: String
            if (p != null && p.pageId == pageId && p.slotId == slotId) {
                matchedPending = true
                key = p.key
            } else {
                // 짝이 안 맞으면 그 pending 은 스냅샷이었던 것 → baseline 으로 확정하고
                // 이번 슬롯은 position 인덱스(또는 position+itemId) 로 식별.
                if (p != null) writeBaseline(p)
                matchedPending = false
                key = resolveSlotKey(pageId, slotId, itemId)
            }

            if (currentCount == null) return
            handleModfy(
                slotUuid = key,
                itemId = itemId,
                pageId = pageId,
                totalCountInSlot = currentCount,
                timestampMs = System.currentTimeMillis(),
                confirmedChange = matchedPending,
            )
            return
        }

        // C) InitBagData → 가방 스냅샷. baseline 만 세팅하고 이벤트로는 세지 않는다.
        val init = initBagFullRegex.find(line)
        if (init != null) {
            flushPendingSlotAsBaseline()
            val pageId = init.groupValues[1]
            val slotId = init.groupValues[2]
            val itemId = init.groupValues[3]
            val currentCount = init.groupValues[4].toIntOrNull() ?: return
            slotLastCount[resolveSlotKey(pageId, slotId, itemId)] = currentCount
            markBaselineReady()
            return
        }

        // D) 그 외 라인 → 대기 중이던 Update/Add 는 Modfy 가 안 붙었으므로 스냅샷 확정
        flushPendingSlotAsBaseline()

        // E) MapName 추적. LoginScene(로그인 화면) 과 마을은 맵 밖 복귀 신호라 맵 전용
        // 타이머를 여기서 멈춘다.
        //
        // 마을 판정을 여기서 하는 이유: 맵 안에서 다른 맵 보스로 이동하는 이벤트가 있어서
        // `EnterArea` 로는 이탈을 판정할 수 없다 ([handleEnterArea] 참조). "어느 맵에 있는지" 를
        // 직접 보는 MapName 이 이탈 판정에 더 믿을 만하다.
        val m = mapNameRegex.find(line)
        if (m != null) {
            val code = m.groupValues[1]
            if (code.startsWith("LoginScene") || mapNames?.isTown(code) == true) {
                latestMapCode = null
                awaitingMapArea = false
                // 로그인 화면에 있다 = 거래소 안일 수 없다. 거래소를 닫는 신호(Destory)는
                // 거래소 화면에서 게임이 죽으면 로그에 안 남는데, 그러면 inExchange 가 참으로
                // 굳어 재로그인 후 파밍 집계가 통째로 막힌다.
                // (마을은 여기서 안 푼다 — 경매장은 마을 위에 뜨는 화면이라 거래 중에도
                //  마을 MapName 이 올 수 있고, 그때 풀면 거래가 수익으로 잡힌다.)
                if (code.startsWith("LoginScene")) exitExchange()
                setMapPresence(false, "MapName=$code")
            } else if (code != latestMapCode && code.isNotEmpty()) {
                latestMapCode = code
                // 맵 밖인데 맵(마을·로그인이 아닌) 이름이 관측됐다 = 맵으로 들어가는 중이다.
                //
                // 게임은 **이미 열어둔 맵에 다시 들어갈 때 `Spv3Open` 을 보내지 않는다**
                // (실기기 확인 2026-08-16: 마을→맵→마을→같은 맵 순서에서 재입장 때는
                // MapName 과 EnterArea 만 온다). 맵 열기가 세우던 "지역 진입 대기" 플래그를
                // 여기서도 세워서, 뒤따라오는 EnterArea 가 시계를 켜게 한다.
                //
                // 이름만으로 켜지는 않는다 — areaId 를 실은 EnterArea 가 와야 한다. 마을·로그인
                // 이름은 위 분기에서 이미 걸러졌고, 그 외 미지의 씬 이름이 섞여 들어와도
                // 뒤따르는 지역 진입이 없으면 아무 일도 일어나지 않는다.
                if (!_state.value.inMap) {
                    awaitingMapArea = true
                    recordPresence(false, "맵 이름 관측 — 지역 진입 대기(MapName=$code)")
                }
            }
        }
    }

    /**
     * 정규식을 돌릴 가치가 있는 라인인가. 이 앱이 보는 모든 이벤트는 아래 토큰 중 하나를 포함한다.
     *
     * - `ItemChange@`  — 슬롯 Update/Add/Reset, ProtoName 블록 마커
     * - `BagMgr@:`     — Modfy / InitBagData
     * - `----Socket`   — 경매장 조회 블록 시작·끝
     * - `MapName`      — 맵 코드네임
     * - `OnEnterArea`  — 맵 진입
     * - `AuctionHouseV2` — 거래소 화면 진입/퇴장
     */
    private fun isInteresting(line: String): Boolean =
        line.contains("ItemChange@") ||
        line.contains("BagMgr@:") ||
        line.contains("----Socket") ||
        line.contains("MapName") ||
        line.contains("OnEnterArea") ||
        line.contains("AuctionHouseV2")


    /**
     * 경매장 시세 조회(`XchgSearchPrice`) 블록 처리.
     *
     * @return 이 라인을 소비했으면 true (호출측은 더 볼 필요 없음)
     */
    private fun consumeXchgLine(line: String): Boolean {
        // 1) 블록 시작
        xchgSendHeaderRegex.find(line)?.let {
            flushPendingSlotAsBaseline()
            xchgMode = XchgMode.SEND
            xchgSynId = it.groupValues[1]
            xchgLineCount = 0
            return true
        }
        xchgRecvHeaderRegex.find(line)?.let {
            flushPendingSlotAsBaseline()
            xchgMode = XchgMode.RECV
            xchgSynId = it.groupValues[1]
            xchgLineCount = 0
            xchgInUnitPrices = false
            xchgCurrency = null
            xchgUnitPrices.clear()
            return true
        }

        if (xchgMode == XchgMode.NONE) return false

        // 2) 블록 종료
        if (socketEndRegex.containsMatchIn(line)) {
            if (xchgMode == XchgMode.RECV) finishXchgRecv()
            xchgMode = XchgMode.NONE
            xchgSynId = null
            return true
        }

        // End 라인을 못 만나는 이상 상황에서 무한히 삼키지 않도록 가드.
        if (++xchgLineCount > MAX_XCHG_BLOCK_LINES) {
            xchgMode = XchgMode.NONE
            xchgSynId = null
            return false
        }

        when (xchgMode) {
            XchgMode.SEND -> {
                val refer = xchgReferRegex.find(line)?.groupValues?.get(1)
                val syn = xchgSynId
                if (refer != null && syn != null) xchgItemBySynId[syn] = refer
            }
            XchgMode.RECV -> {
                xchgCurrencyRegex.find(line)?.let { xchgCurrency = it.groupValues[1] }
                // unitPrices 배열은 첫 줄에 `+unitPrices+1 [..]`, 이후 `|` 로 이어지는 연속 줄.
                when {
                    line.contains("+unitPrices") -> xchgInUnitPrices = true
                    !line.trimStart().startsWith("|") -> xchgInUnitPrices = false
                }
                if (xchgInUnitPrices) {
                    for (m in xchgUnitPriceRegex.findAll(line)) {
                        m.groupValues[1].toDoubleOrNull()?.let { xchgUnitPrices += it }
                    }
                }
            }
            XchgMode.NONE -> Unit
        }
        return true
    }

    private fun finishXchgRecv() {
        val itemId = xchgSynId?.let { xchgItemBySynId.remove(it) } ?: return
        if (xchgUnitPrices.isEmpty()) return
        // 결정(100300) 으로 매겨진 호가만 쓴다. 가루(100200) 거래는 계산에서 제외.
        if (xchgCurrency != null && xchgCurrency != BASE_CURRENCY_ID) {
            xchgUnitPrices.clear()
            return
        }
        observedPrices?.record(
            itemId = itemId,
            unitPrices = xchgUnitPrices.toList(),
            observedAtMs = System.currentTimeMillis(),
            currencyId = xchgCurrency,
        )
        xchgUnitPrices.clear()
    }

    /**
     * `PageId:SlotId` 로 슬롯 인스턴스 키를 찾는다.
     * 인덱스가 가리키는 uuid 의 baseId 접두사가 지금 아이템과 다르면 (슬롯 재사용) 인덱스를 믿지 않고
     * `page:slot:itemId` 합성 키를 쓴다.
     */
    private fun resolveSlotKey(pageId: String, slotId: String, itemId: String): String {
        val indexed = slotKeyByPosition["$pageId:$slotId"]
        val key = if (indexed != null && indexed.startsWith("${itemId}_")) indexed
                  else "$pageId:$slotId:$itemId"
        updatePositionKey(pageId, slotId, key)
        return key
    }

    /**
     * `PageId:SlotId` 포지션의 현재 점유 키를 갱신한다.
     *
     * 한 포지션은 항상 하나의 키만 점유해야 하는데, uuid 직접 파싱 경로(케이스 A)와
     * [resolveSlotKey] 의 합성 키 fallback 경로가 **같은 물리 슬롯을 서로 다른 키 문자열로**
     * 기록할 수 있다 (예: baseline(InitBagData) 때는 fallback 합성 키로 잡혔다가, 이후 실거래
     * 이벤트는 항상 uuid 키로 옴).
     *
     * 새 키가 **같은 아이템**이면 (합성 키 ↔ uuid 키 전환일 뿐, 물리적으로 같은 슬롯) 예전 키의
     * 수량을 새 키로 그대로 이어받는다 — 그냥 0으로 지우면 baseline 때 잡힌 수량 정보가
     * 사라져서, 그 다음 실거래 Modfy 가 "이 키는 처음 본다" 고 오판하고 **기존 보유량까지
     * 통째로 신규 획득으로** 잡아버린다 (실측: baseline 581개 상태에서 실제로는 +3개만
     * 늘었는데 확정 픽업 이벤트가 새 키로 들어오는 바람에 584개 전부를 "이번에 획득" 으로
     * 잡아 회차 합계가 592로 부풀려진 버그).
     *
     * 새 키가 **다른 아이템**이면 (슬롯이 재사용된 것) 예전 키는 이 포지션과 무관해졌으니 0으로
     * 무효화한다 — 그 아이템이 실제로 다른 슬롯으로 옮겨간 경우엔 그 슬롯의 Update 라인이
     * (같은 배치 안에서) 다시 정확한 수량으로 채워준다. 이렇게 안 하면 두 키가 각각
     * [slotLastCount] 에 남아 [computeHoldings] 의 itemId 합산에서 같은 물리 아이템이
     * 이중으로 잡힌다 (실측: 8개 보유 중 1개 구매 → 9개가 아니라 17개=8+9 로 표시되던 버그).
     */
    private fun updatePositionKey(pageId: String, slotId: String, newKey: String) {
        val posKey = "$pageId:$slotId"
        val oldKey = slotKeyByPosition[posKey]
        if (oldKey != null && oldKey != newKey) {
            val oldCount = slotLastCount.remove(oldKey)
            val sameItem = extractItemId(oldKey) != null && extractItemId(oldKey) == extractItemId(newKey)
            if (sameItem && oldCount != null && !slotLastCount.containsKey(newKey)) {
                slotLastCount[newKey] = oldCount
            }
        }
        slotKeyByPosition[posKey] = newKey
    }

    /** 대기 중인 슬롯 라인을 baseline 으로 확정 (변화 이벤트가 아니었다는 뜻). */
    private fun flushPendingSlotAsBaseline() {
        val p = pendingSlot ?: return
        pendingSlot = null
        writeBaseline(p)
    }

    private fun writeBaseline(p: PendingSlot) {
        slotLastCount[p.key] = p.bagNum
    }

    /** 가방 스냅샷 관측 → 계산 시작. 경과 시간도 이 시점부터 센다. */
    private fun markBaselineReady() {
        if (_state.value.baselineReady) return
        _state.update {
            if (it.baselineReady) it
            else {
                val now = System.currentTimeMillis()
                it.copy(
                    baselineReady = true,
                    startedAtMs = now,
                    mapElapsedSinceMs = if (it.inMap && !it.paused) now else null,
                    currentMapElapsedSinceMs = if (it.inMap && !it.paused) now else null,
                )
            }
        }
    }

    /** 새 맵 시작 — 진행 중이던 회차를 닫아 기록으로 넘기고, "이번 맵" 을 비운다. */
    private fun startNewRun() {
        // 맵을 열었다 = 거래소 밖이다. 거래소 종료 라인을 못 받은 채로 굳은 상태를 여기서도 푼다.
        exitExchange()
        awaitingMapArea = true
        // 맵 열기는 전환이 없어도 반드시 남긴다. setMapPresence 는 inMap 이 실제로 바뀔 때만
        // 기록하는데, 맵 밖에서 맵을 열면 이미 false 라 아무 흔적이 안 남는다 — 그러면 진단
        // 화면만 보고 "Spv3Open 이 안 왔다" 고 잘못 읽게 된다.
        val wasInMap = _state.value.inMap
        setMapPresence(false, "맵 열기(Spv3Open)")
        if (!wasInMap) recordPresence(false, "맵 열기(Spv3Open)")
        _state.update {
            it.copy(
                // 맵핑 횟수는 여기서 센다. 예전엔 EnterArea 에서 셌는데 맵 안에서 지역이
                // 바뀔 때마다 같이 올라갔다.
                //
                // 맵 안에서 연 경우(wasInMap)는 세지 않는다 — 게임은 다른 맵 보스로 이동할
                // 때도 새 맵 열기(Spv3Open)로 처리하지만, 그건 돌던 판의 연장이라 한 판으로 본다.
                mapsEntered = if (wasInMap) it.mapsEntered else it.mapsEntered + 1,
                currentMapElapsedAccumulatedMs = 0,
                currentMapElapsedSinceMs = null,
            )
        }
        closeCurrentRun()
        currentRunId = nextRunId++
        currentRunStartedAtMs = System.currentTimeMillis()
        currentRunMapName = null
        currentRunByItem.clear()
        publishRuns()
    }

    /** 맵 안/밖 전환 시 맵 전용 시계를 확정하거나 재개한다. */
    /**
     * 맵 안/밖 전환. 누적 시간에 더하는 조각은 전부 `coerceAtLeast(0)` 을 거친다 —
     * NTP 보정 등으로 기기 시계가 뒤로 가면 조각이 음수가 되어 이미 쌓아둔 시간이 깎인다.
     */
    private fun setMapPresence(inMap: Boolean, reason: String) {
        val before = _state.value.inMap
        _state.update { state ->
            if (state.inMap == inMap) return@update state
            val now = System.currentTimeMillis()
            val chunk = state.mapElapsedSinceMs?.let { (now - it).coerceAtLeast(0) } ?: 0
            val currentMapChunk = state.currentMapElapsedSinceMs?.let { (now - it).coerceAtLeast(0) } ?: 0
            state.copy(
                inMap = inMap,
                // 맵을 나가면 지역 기억도 비운다. 안 비우면 같은 맵에 다시 들어올 때 오는
                // EnterArea 가 "같은 areaId" 중복 방지에 걸려 통째로 삼켜진다 (실기기에서
                // 재입장 시 areaId 가 그대로였다). 중복 방지는 한 번의 입장에서 게임이 여러 번
                // emit 하는 것을 거르려는 것이지, 나갔다 들어온 것까지 거르려는 게 아니다.
                currentAreaId = if (inMap) state.currentAreaId else null,
                mapElapsedAccumulatedMs = state.mapElapsedAccumulatedMs + chunk,
                mapElapsedSinceMs = if (inMap && state.baselineReady && !state.paused) now else null,
                currentMapElapsedAccumulatedMs = state.currentMapElapsedAccumulatedMs + currentMapChunk,
                currentMapElapsedSinceMs = if (inMap && state.baselineReady && !state.paused) now else null,
            )
        }
        if (_state.value.inMap != before) recordPresence(inMap, reason)
    }

    /**
     * 맵 안/밖 판정이 바뀐 순간을 원인과 함께 남긴다.
     *
     * 진단용이다. 이 판정은 서로 다른 로그 신호 네 개가 건드리는데, 실기기에서 무엇이
     * 언제 왔는지는 원본 로그 마지막 10줄로는 알 수 없다 — 게임을 내리고 앱을 열기까지
     * 수천 줄이 더 쌓여서 정작 필요한 줄이 밀려난다. 그래서 전환 순간만 따로 붙잡아 둔다.
     */
    private fun recordPresence(inMap: Boolean, reason: String) {
        _mapPresenceLog.value =
            (_mapPresenceLog.value +
                MapPresenceEvent(System.currentTimeMillis(), inMap, reason, currentLogTime))
                .takeLast(MAX_PRESENCE_LOG)
    }

    /**
     * 진행 중인 회차를 완료 처리한다. 빈 회차는 버린다.
     *
     * 아이템 목록은 [onRunFinished] 로 디스크에 내리고 **메모리에는 요약만** 남긴다.
     * 회차가 늘어도 프로세스 메모리가 선형으로 늘지 않게 하는 핵심 지점.
     */
    private fun closeCurrentRun() {
        if (currentRunId < 0) return
        if (currentRunByItem.isNotEmpty()) {
            val run = buildRun(endedAtMs = System.currentTimeMillis())
            onRunFinished?.invoke(run)
            finishedRuns += run.toSummary()
            // 메모리 상한. 디스크 쪽 상한은 RunRepository.MAX_STORED_RUNS.
            while (finishedRuns.size > MAX_RUNS) finishedRuns.removeAt(0)
        }
        currentRunId = -1
    }

    /** 아직 회차가 시작되지 않았으면(맵 열기 전 획득) 암묵적으로 하나 연다. */
    private fun ensureCurrentRun(atMs: Long) {
        if (currentRunId >= 0) return
        currentRunId = nextRunId++
        currentRunStartedAtMs = atMs
        // 맵을 열어서 시작한 회차가 아니라(그건 startNewRun 이 null 로 비운다) 맵 안에서
        // 암묵적으로 열리는 회차다. 지금 서 있는 맵 이름을 그대로 단다 — 예전엔 null 로 비워
        // 다음 EnterArea 가 올 때까지 이름 없는 회차로 보였다(진행 중 회차를 지운 직후 등).
        currentRunMapName = _state.value.currentMapName
    }

    private fun buildRun(endedAtMs: Long?): MapRun = MapRun(
        id = currentRunId,
        index = 0,   // publishRuns 에서 다시 매긴다
        startedAtMs = currentRunStartedAtMs,
        endedAtMs = endedAtMs,
        mapName = currentRunMapName,
        items = currentRunByItem.values.sortedByDescending { kotlin.math.abs(it.value) },
    )

    /** [finishedRuns] + 진행 중 회차를 state 로 반영. 회차 번호를 1 부터 다시 매긴다. */
    private fun publishRuns() {
        val all = buildList {
            addAll(finishedRuns)
            if (currentRunId >= 0) add(buildRun(endedAtMs = null))
        }.mapIndexed { i, r -> r.copy(index = i + 1) }

        val items = sessionItemTotals.values.sortedByDescending { kotlin.math.abs(it.value) }

        _state.update { s ->
            val current = all.lastOrNull()?.takeIf { it.inProgress }
            s.copy(
                runs = all,
                sessionItems = items,
                recentPickups = current?.items?.take(MAX_RECENT_PICKUPS) ?: emptyList(),
                currentMapValue = current?.totalValue ?: 0.0,
                netCurrentMapValue = current?.netTotalValue ?: 0.0,
                totalValue = all.sumOf { it.totalValue },
                netTotalValue = all.sumOf { it.netTotalValue },
                pickupCount = all.sumOf { it.pickupCount },
            )
        }
    }

    /** 세션 전체 아이템 합계를 증분으로 갱신. */
    private fun addToSessionTotals(itemId: String, delta: PickupSummary) {
        val prev = sessionItemTotals[itemId]
        sessionItemTotals[itemId] = if (prev == null) delta
        else prev.copy(
            timestampMs = delta.timestampMs,
            quantity = prev.quantity + delta.quantity,
            value = prev.value + delta.value,
            netValue = prev.netValue + delta.netValue,
            unitPrice = delta.unitPrice.takeIf { it > 0f } ?: prev.unitPrice,
        )
    }

    /** 회차 삭제 시 그 회차 몫을 세션 합계에서 뺀다. */
    private fun subtractFromSessionTotals(items: List<PickupSummary>) {
        for (p in items) {
            val id = p.itemId ?: continue
            val prev = sessionItemTotals[id] ?: continue
            val q = prev.quantity - p.quantity
            val v = prev.value - p.value
            val nv = prev.netValue - p.netValue
            if (q == 0 && kotlin.math.abs(v) < 1e-9) sessionItemTotals.remove(id)
            else sessionItemTotals[id] = prev.copy(quantity = q, value = v, netValue = nv)
        }
    }

    /**
     * 회차 삭제 — 잘못 집계된 회차를 통째로 제거한다.
     * 세션 총 수익/픽업 수/아이템 합계도 남은 회차 기준으로 다시 계산된다.
     *
     * @param items 그 회차의 아이템 목록. 완료된 회차는 메모리에 없으므로
     *              호출측(서비스)이 디스크에서 읽어 넘겨준다.
     */
    fun deleteRun(runId: Long, items: List<PickupSummary> = emptyList()) {
        if (currentRunId == runId) {
            subtractFromSessionTotals(currentRunByItem.values.toList())
            currentRunByItem.clear()
            currentRunId = -1
        } else if (finishedRuns.any { it.id == runId }) {
            subtractFromSessionTotals(items)
            finishedRuns.removeAll { it.id == runId }
        }
        publishRuns()
    }

    /**
     * BagMgr@:Modfy 라인 = 실제 픽업 (또는 소비/변화).
     * PickItems 블록 유무와 상관없이 모든 Modfy 를 처리.
     */
    private fun handleModfy(
        slotUuid: String,
        itemId: String,
        pageId: String,
        totalCountInSlot: Int,
        timestampMs: Long,
        /** 바로 앞 Update/Add 라인이 같은 슬롯에 대해 이 Modfy 와 직접 짝지어졌는지 (실제 변화 확정). */
        confirmedChange: Boolean = false,
    ) {
        // 가방 정렬 관측 전 = 슬롯 기준 수량 미확보 → 계산 안 하고 baseline 만 쌓는다.
        if (!_state.value.baselineReady) {
            slotLastCount[slotUuid] = totalCountInSlot
            return
        }
        // 일시정지 중이면 slot 상태만 갱신하고 픽업 카운트 안 함.
        // slotLastCount 는 계속 최신값 유지해야 resume 후 다음 Modfy delta 가 정확.
        //
        // 거래소 안에서는 paused 와 **무관하게** 카운트하지 않는다. 거래소 진입이 자동으로
        // 거는 pause 하나에만 기대면, 유저가 "중지중" 표시를 보고 재생 버튼을 누르는 순간
        // 구매한 아이템이 수익으로, 판매 등록(Delete)이 소비로 잡힌다 — 거래소 거래는
        // 파밍 수익이 아니므로 어느 쪽이든 집계에 들어가면 안 된다.
        if (_state.value.paused || _state.value.inExchange) {
            slotLastCount[slotUuid] = totalCountInSlot
            // 거래소 안이면(구매/판매 등록/취소로 슬롯이 바뀔 때마다) 수동 새로고침 없이도
            // 보유 아이템 가치가 바로 최신으로 보이게 즉시 재계산한다.
            if (_state.value.inExchange) refreshHoldings()
            return
        }

        val prev = slotLastCount[slotUuid]
        slotLastCount[slotUuid] = totalCountInSlot
        val quantity: Int = if (prev == null) {
            if (confirmedChange && blockContext != BlockContext.CONSUME) {
                // 바로 앞줄 Update/Add 가 이 Modfy 와 직접 짝지어져 실제 변화라고 확정됐는데
                // 이 슬롯을 처음 본다 = baseline 스냅샷엔 없던 완전히 새 슬롯이 방금 생긴 것.
                // 0 → count 전체를 델타로 인정해야 스택으로 여러 개 한 번에 드랍되는 경우
                // (예: 신규 아이템 3개 동시 획득) 를 1개로 과소 집계하지 않는다.
                totalCountInSlot
            } else {
                // 확정 못 한 경로(합성 키 fallback 등)거나 소비 컨텍스트 — 과거 상태를 모르니
                // 안전하게 ±1 로만 카운트해서 절대 오카운트 안 되게 한다.
                //   PICKUP: +1 (신규 드롭 최소값)
                //   CONSUME: -1 (소비 최소값 — totalCountInSlot 은 소비 "이후" 남은 값이라
                //             델타로 못 씀)
                //   NONE (블록 밖): +1 로 간주
                when (blockContext) {
                    BlockContext.PICKUP -> 1
                    BlockContext.CONSUME -> -1
                    BlockContext.NONE -> 1
                }
            }
        } else {
            val delta = totalCountInSlot - prev
            if (delta == 0) return
            delta
        }

        // 맵을 열기 전에 얻은 것도 버리지 않고 암묵적 회차로 담는다.
        //
        // 여기까지 와서 연다 — 실제로 기록할 변화가 있는 게 확정된 지점이다. 예전엔 이 함수
        // 맨 앞에서 열어서, 거래소 안(또는 일시정지 중)처럼 카운트를 건너뛰고 빠져나가는
        // 경우에도 회차가 만들어졌다. 열려 있는 회차가 없던 상태(리셋 직후·진행 회차 삭제
        // 직후·맵을 한 번도 안 연 상태)에서 거래소에 들어가면 아이템 0 개짜리 빈 회차가
        // 목록에 남았다.
        ensureCurrentRun(timestampMs)

        val info = itemInfo.lookup(itemId)
        val valueResult = valueCalculator?.compute(itemId, quantity)
        val unitPrice = valueResult?.unitPrice ?: 0f
        val totalValue = valueResult?.totalValue ?: 0.0
        val netValue = valueResult?.netValue ?: 0.0
        val priced = valueResult?.priced ?: false

        val summary = PickupSummary(
            timestampMs = timestampMs,
            action = "PickItems",
            itemId = itemId,
            itemName = info?.name,
            itemType = info?.type ?: pageIdLabel(pageId),
            iconUrl = info?.img?.takeIf { it.isNotBlank() },
            quantity = quantity,
            unitPrice = unitPrice,
            value = totalValue,
            netValue = netValue,
        )

        // 이번 맵 안 아이템별 누적. 같은 itemId 재관측이면 quantity/value 합산.
        val prevAgg = currentRunByItem[itemId]
        val merged = if (prevAgg == null) summary
        else prevAgg.copy(
            timestampMs = timestampMs,             // 마지막 관측 시각으로 갱신
            quantity = prevAgg.quantity + summary.quantity,
            value = prevAgg.value + summary.value,
            netValue = prevAgg.netValue + summary.netValue,
            unitPrice = summary.unitPrice.takeIf { it > 0f } ?: prevAgg.unitPrice,
        )
        currentRunByItem[itemId] = merged
        addToSessionTotals(itemId, summary)

        if (!_state.value.active) return

        // 미가격 카운트와 시계열은 회차 삭제와 무관하게 누적만 한다.
        _state.update { s ->
            val newTotal = s.runs.sumOf { it.totalValue } + totalValue
            s.copy(
                unpricedPickups = if (priced || itemId.isBlank()) s.unpricedPickups
                                  else s.unpricedPickups + 1,
                valueSeries = updateValueSeries(s.valueSeries, timestampMs, newTotal),
            )
        }
        // 총 수익/픽업 수/이번 맵 목록은 전부 회차 목록에서 파생시킨다.
        publishRuns()
    }

    /**
     * 1분 단위 슬롯에 누적 가치 샘플링. 같은 슬롯이면 마지막 값 갱신, 아니면 새 슬롯 추가.
     * 최대 60개까지 슬라이딩 (최근 1시간).
     */
    private fun updateValueSeries(
        current: List<TimeSample>,
        eventTs: Long,
        cumulative: Double,
    ): List<TimeSample> {
        val slot = (eventTs / 60_000L) * 60_000L
        val list = current.toMutableList()
        val last = list.lastOrNull()
        if (last != null && last.timeSlotMs == slot) {
            list[list.size - 1] = TimeSample(slot, cumulative)
        } else {
            list += TimeSample(slot, cumulative)
            while (list.size > MAX_TIME_SAMPLES) list.removeAt(0)
        }
        return list
    }

    private fun handleEnterArea(msg: MessageAssembler.RawMessage) {
        // payload 안에서 LevelUid/LevelType/LevelId 추출
        var levelUid: String? = null
        var levelType: String? = null
        var levelId: String? = null
        for (line in msg.data) {
            levelInfoRegex.find(line)?.let {
                levelUid = it.groupValues[1]
                levelType = it.groupValues[2]
                levelId = it.groupValues[3]
            }
            if (levelId != null) break
        }

        val newArea = levelUid ?: levelId
        // 마을 복귀 EnterArea에는 버전·로딩 순서에 따라 LevelUid/LevelId가 없을 수 있다.
        // 맵을 열고 기다리는 중인 경우만 예외로 두고, 그 외에는 이 이벤트 자체를 이탈 신호로
        // 처리한다. 기존에는 여기서 즉시 return 해 `inMap=true`가 남아 MAP_ONLY 시간도 계속
        // 누적될 수 있었다.
        if (newArea == null && !awaitingMapArea) {
            setMapPresence(false, "지역 정보 없는 EnterArea")
            return
        }
        // 게임이 한 번의 맵 진입에도 여러 OnEnterAreaBegin 을 emit 하므로 areaId 로 dedupe.
        // (예: 로딩 단계 → 로딩 완료 → 실제 게임플레이 각각 fire)
        val nowMs = msg.header.timestampEpochMs

        // update 블록은 CAS 재시도로 여러 번 돌 수 있어 안에서 바로 기록하면 중복될 수 있다.
        // 전환이 있었는지만 표시해 두고 블록 밖에서 한 번 남긴다.
        enterAreaPresenceReason = null
        _state.update { s ->
            // 일시정지 중에도 맵 밖으로 나갈 수 있다. 이탈 이벤트를 버리면 재생 시
            // 맵 밖 시간이 다시 누적되므로, 지역 전환 자체는 계속 반영한다.
            if (!s.active) return@update s
            val prevArea = s.currentAreaId
            val lastEnterMs = s.lastEnterAreaMs

            // areaId 를 모르면 count 하지 않음 (파싱 실패 케이스)
            if (newArea == null) return@update s

            // 같은 areaId 로 재-트리거되면 무시
            if (prevArea == newArea) {
                enterAreaPresenceReason = "지역 진입 신호 무시 — 같은 지역(areaId=$newArea)"
                return@update s
            }

            // 같은 areaId 없이 매우 짧은 간격(3초 이하) 안에 두 번 오면 게임의 중복 emit 로 간주.
            // 단, 맵으로 들어가는 중(맵 열기 또는 맵 이름 관측)이면 그건 진짜 진입이다 — 맵을
            // 나갈 때 currentAreaId 를 비우므로 여기서 prevArea 가 null 인 것은 정상이다.
            if (prevArea == null && !awaitingMapArea && lastEnterMs > 0 && nowMs - lastEnterMs < 3000) {
                enterAreaPresenceReason = "지역 진입 신호 무시 — 3초 내 중복(areaId=$newArea)"
                return@update s
            }

            val completed = if (prevArea != null) s.mapsCompleted + 1 else s.mapsCompleted
            // 이름 우선순위: map.json/map_alias.json 큐레이션 매핑 → 관측된 MapName code 원본
            // → itemInfo 매핑 → levelId 자체
            val displayName = mapNames?.resolve(latestMapCode, levelId)
                ?: latestMapCode
                ?: levelId?.let { itemInfo.lookup(it)?.name }
                ?: levelId
            // 진행 중인 회차에 맵 이름을 달아준다 (회차 목록/그래프 라벨용).
            if (currentRunMapName == null) currentRunMapName = displayName
            // "이번 진입" 목록은 여기서 비우지 않는다. 맵 열기(Spv3Open) 직후 ~100 ms 만에
            // 이 이벤트가 오기 때문에, 여기서 비우면 방금 기록한 소비(마이너스) 가 사라진다.
            // 목록 초기화는 startNewRun() 이 Spv3Open 시점에 수행.
            val enteringOpenedMap = awaitingMapArea
            awaitingMapArea = false
            val now = System.currentTimeMillis()
            val mapChunk = s.mapElapsedSinceMs?.let { since -> (now - since).coerceAtLeast(0) } ?: 0
            val currentMapChunk = s.currentMapElapsedSinceMs?.let { since -> (now - since).coerceAtLeast(0) } ?: 0
            // EnterArea 는 맵 **진입**만 알린다. 이탈은 InputArea(지역 선택 복귀) ·
            // MapName 의 마을/LoginScene · areaId 없는 EnterArea 가 판정한다.
            //
            // 예전엔 여기서 `inMap = enteringOpenedMap` 으로 덮어써서, 맵 안에서 다른 맵
            // 보스로 이동하는 이벤트처럼 Spv3Open 없이 지역만 바뀌면 맵을 나간 것으로 오인해
            // 시계가 그 맵 내내 멈췄다 (`awaitingMapArea` 는 첫 진입에서 이미 소진되므로
            // 두 번째 지역부터는 항상 false 다).
            val stillInMap = enteringOpenedMap || s.inMap
            enterAreaPresenceReason = when {
                stillInMap != s.inMap -> "지역 진입(areaId=$newArea)"
                // 판정이 안 바뀐 경우도 남긴다 — "재입장했는데 시계가 안 돈다" 를 조사하려면
                // EnterArea 가 오긴 왔는지, 왔다면 어떤 areaId 였는지가 유일한 단서다.
                // (맵 밖 유지 = 맵 열기(Spv3Open) 없이 지역만 바뀐 경우)
                !stillInMap -> "지역 진입 신호 — 맵 밖 유지(areaId=$newArea)"
                else -> "지역 이동(areaId=$newArea)"
            }
            val clockRuns = stillInMap && s.baselineReady && !s.paused
            s.copy(
                // mapsEntered 는 여기서 세지 않는다 — 맵 열기(startNewRun)가 센다.
                mapsCompleted = completed,
                currentAreaId = newArea,
                currentMapId = levelId,
                currentMapName = displayName,
                lastEnterAreaMs = nowMs,
                inMap = stillInMap,
                // 이미 돌고 있던 구간은 mapChunk 로 누적에 합쳐진 뒤 다시 시작하므로 손실이 없다.
                mapElapsedAccumulatedMs = s.mapElapsedAccumulatedMs + mapChunk,
                mapElapsedSinceMs = if (clockRuns) now else null,
                currentMapElapsedAccumulatedMs = s.currentMapElapsedAccumulatedMs + currentMapChunk,
                currentMapElapsedSinceMs = if (clockRuns) now else null,
            )
        }
        enterAreaPresenceReason?.let {
            recordPresence(_state.value.inMap, it)
            enterAreaPresenceReason = null
        }
    }

    /** [handleEnterArea] 의 update 블록에서 블록 밖으로 전환 사유를 넘기는 임시 자리. */
    private var enterAreaPresenceReason: String? = null

    /**
     * 지금 처리 중인 로그 줄의 게임 시각. 진단 기록이 앱 처리 시각 대신 이걸 쓴다.
     *
     * 파싱은 [isInteresting] 필터를 통과한 줄에만 한다 — 전체의 1.25% 뿐이라, 모든 줄에
     * 정규식을 돌려 그 필터의 목적을 무너뜨리지 않는다.
     */
    private var currentLogTime: String? = null

    /** `[2026.08.14-09.35.01:367]…` 과 `2026.08.14-09.35.01:367` 양쪽에서 시:분:초를 뽑는다. */
    private val logClockRegex = Regex("""(\d{2})\.(\d{2})\.(\d{2}):\d+""")

    private fun logClockOf(raw: String): String? =
        logClockRegex.find(raw)?.let { "${it.groupValues[1]}:${it.groupValues[2]}:${it.groupValues[3]}" }

    private fun pageIdLabel(pageId: String?): String? = when (pageId) {
        "100" -> "장비 백팩"
        "101" -> "스킬 백팩"
        "102" -> "통화 백팩"
        "103" -> "기타 백팩"
        else -> null
    }

    private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
        while (true) {
            val prev = value
            val next = block(prev)
            if (compareAndSet(prev, next)) return
        }
    }

    companion object {
        const val MAX_RECENT_PICKUPS = 10
        /** 가치 기준 통화 = 최초의 불꽃 결정. */
        private const val BASE_CURRENCY_ID = "100300"
        /** 경매장 블록이 비정상적으로 길면 파싱을 포기 (End 라인 유실 대비). */
        private const val MAX_XCHG_BLOCK_LINES = 400
        /** 보관할 최대 회차 수. 넘으면 오래된 것부터 버린다. */
        private const val MAX_RUNS = 200
        /** "자산" 탭/HUD 에 보여줄 최대 보유 아이템 종류 수 (가치 내림차순으로 자름). */
        private const val MAX_HOLDINGS = 50
        const val MAX_TIME_SAMPLES = 60   // 1분당 1점 × 60 = 최근 1시간
        /** 진단용 맵 전환 이력 보관 개수. 맵 한 번에 2~3건 남으니 최근 몇 판은 덮인다. */
        private const val MAX_PRESENCE_LOG = 40
    }
}
