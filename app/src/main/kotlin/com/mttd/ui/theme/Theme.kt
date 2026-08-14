package com.mttd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 리디자인 시안(White + Blue)의 팔레트. 색값은 시안에서 그대로 가져온 것이라 임의로 바꾸지 않는다.
 *
 * 시안에 없는 색은 여기에 넣지 않고 [MttdColors] 로 뺀다 — M3 토큰 이름과 실제 쓰임이 어긋나면
 * 나중에 어느 쪽이 진짜인지 알 수 없게 된다.
 */
private val MttdLightColors = lightColorScheme(
    // 브랜드 블루. 총 수익 숫자·선택된 탭·주요 버튼.
    primary = Color(0xFF1687F8),
    onPrimary = Color.White,
    // 블루 톤 카드/칩 배경 (시세 바, 선택된 미니패널 항목, 보조 버튼).
    primaryContainer = Color(0xFFEAF4FF),
    onPrimaryContainer = Color(0xFF0F6BC9),
    // 경고 오렌지. Shizuku 안내와 가방 정렬 안내에만 제한적으로 쓴다.
    secondary = Color(0xFFE07B12),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF4E8),
    onSecondaryContainer = Color(0xFFE07B12),
    // 시안에는 초록이 없다. 기존 코드가 참조 중이라 정의만 남겨두고, 해당 화면을 손볼 때 정리한다.
    tertiary = Color(0xFF1E9C62),
    onTertiary = Color.White,
    // 화면 바탕은 연블루그레이, 카드는 순백. 이 대비가 시안 레이아웃의 뼈대다.
    background = Color(0xFFF7FAFC),
    onBackground = Color(0xFF1A2433),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A2433),
    // 탭 트랙 등 인셋 배경.
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF748094),
    // 카드가 전부 흰색으로 떠 보이게 유지하고, 회색은 화면 바탕과 인셋에만 쓴다.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF1F5F9),
    outline = Color(0xFFE6ECF2),
    error = Color(0xFFE5484D),
    onError = Color.White,
)

/**
 * 시안에는 있지만 M3 컬러 토큰으로는 표현할 자리가 없는 보조 색.
 *
 * 토큰에 억지로 밀어 넣으면(예: 흐린 텍스트를 `scrim` 에) 이름과 쓰임이 어긋나므로 여기 모아둔다.
 * 팔레트를 다시 손볼 때 이 파일 하나만 고치면 된다.
 *
 * 웹 시안의 `style-hover` 색은 옮기지 않았다 — 안드로이드에는 hover 가 없고 눌림 표현은
 * ripple 이 담당한다.
 */
object MttdColors {
    /** 가장 흐린 보조 텍스트 — 빈 상태 문구, 캡션. */
    val TextMuted = Color(0xFFA9B6C6)
    /** 본문과 보조 텍스트 사이 중간 톤 — 라디오 설명, 미선택 칩 라벨. */
    val TextMid = Color(0xFF5A6779)
    /** 카드 안쪽 구분선. [MttdLightColors] 의 outline 보다 연하다. */
    val DividerSoft = Color(0xFFEEF3F8)
    /** 빈 상태 블록의 점선 테두리. */
    val DashBorder = Color(0xFFDEE7EF)

    /** 블루 톤 카드/칩의 테두리. */
    val BlueBorder = Color(0xFFD7EBFF)
    /** 켜진 토글 카드처럼 좀 더 또렷해야 하는 블루 테두리. */
    val BlueStrong = Color(0xFFBFDDFB)
    /** 블루 카드 안의 보조 텍스트 (시세 바 본문). */
    val BlueText = Color(0xFF5B93C7)
    /** 블루 카드 안의 가장 흐린 요소 (단위 표기, 안내 아이콘). */
    val BlueFaint = Color(0xFF8FBFF3)

    /** 경고 카드 테두리 (오렌지 계열). */
    val WarnLine = Color(0xFFFFE2C2)
    /** 위험 버튼/카드의 테두리 (리셋, 앱 종료). */
    val DangerLine = Color(0xFFF3D2D4)
    /** 위험 상태 카드 배경 — 게임 위 패널이 꺼져 있을 때. */
    val DangerBg = Color(0xFFFFF6F6)
    /** 꺼진 토글의 트랙 색. */
    val ToggleOff = Color(0xFFE5A0A3)
    /** Shizuku 체크리스트에서 미완료 항목의 아이콘 배경. */
    val FailBg = Color(0xFFFDECEC)

    /** 선택되지 않은 칩/라디오 카드의 배경 — 흰색보다 아주 살짝 푸르다. */
    val ChipOffBg = Color(0xFFFBFDFF)
    /** 선택되지 않은 체크박스/라디오의 테두리. */
    val CheckboxOff = Color(0xFFC7D2DE)
}

// medium 은 Card 의 기본 shape 다 — 시안의 카드가 전부 라운드 16 이라 여기서 맞춘다.
// 그보다 작은 요소(인셋 12, 칩 10 등)는 시안 값이 제각각이라 호출부에서 직접 지정한다.
private val PremiumShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

// 작은 보조 문구도 휴대폰에서 편하게 읽히도록 기본 Material 크기보다 한 단계 올린다.
private val PremiumTypography = Typography(
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

/**
 * 팔레트는 시스템 다크모드와 무관하게 [MttdLightColors] 하나로 고정한다 (values-night 없음).
 * 따라서 라이트/다크를 바꾸려면 이 파일만으로는 부족하고, 아래 두 곳을 **함께** 맞춰야 한다:
 *   - `res/values/themes.xml` — 창 바탕색과 시스템 바 배경
 *   - `MainActivity.onCreate` — 시스템 바 아이콘 명암
 */
@Composable
fun mTTDTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false,
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MttdLightColors,
        shapes = PremiumShapes,
        typography = PremiumTypography,
        content = content,
    )
}
