// app/src/main/java/com/onguard/presentation/ui/dashboard/DashboardComponents.kt

package com.onguard.presentation.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.res.painterResource
import com.onguard.R
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onguard.presentation.theme.*

// ==== 1. 공통 카드 스타일 ====
@Composable
fun DashboardCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = CardBackground,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

// ==== 2. 상단 작은 통계 카드 (글래스모피즘) ====
@Composable
fun SmallStatCard(
    title: String,
    count: Int,
    iconColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(110.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.3f),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = iconColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 28.sp
                )
                Text(
                    text = "회",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ==== 3. 중간 차트 카드 (글래스모피즘) ====
@Composable
fun ChartStatCard(
    title: String,
    count: String,
    iconColor: Color,
    iconRes: Int,
    unit: String,
    chartPlaceholder: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(130.dp) // 110dp -> 130dp로 증가
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.5f),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp) // 패딩 조정
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = iconColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AutoResizedText(
                        text = count,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Box(modifier = Modifier.width(65.dp).height(40.dp)) {
                    chartPlaceholder()
                }
            }
        }
    }
}

// 자동 폰트 크기 조절 텍스트 컴포저블
@Composable
fun AutoResizedText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    color: Color = style.color,
    fontWeight: FontWeight? = null
) {
    var resizedStyle by remember { mutableStateOf(style) }
    var shouldDraw by remember { mutableStateOf(false) }

    val defaultFontSize = style.fontSize

    Text(
        text = text,
        color = color,
        modifier = modifier.drawWithContent {
            if (shouldDraw) {
                drawContent()
            }
        },
        softWrap = false,
        maxLines = 1,
        style = resizedStyle.copy(fontWeight = fontWeight),
        onTextLayout = { result ->
            if (result.didOverflowWidth) {
                if (resizedStyle.fontSize.value > 12) { // 최소 크기 제한
                    resizedStyle = resizedStyle.copy(
                        fontSize = resizedStyle.fontSize * 0.9f
                    )
                } else {
                    shouldDraw = true // 최소 크기임에도 넘치면 그냥 그림
                }
            } else {
                shouldDraw = true
            }
        }
    )
}

// 주간 빈도 차트 (7일)
@Composable
fun WeeklyFrequencyChart(
    data: List<Int>, // 0:오늘 ~ 6:6일전
    color: Color
) {
    // 순서: 왼쪽부터 [3일전, 2일전, 1일전, 오늘, 6일전(가장오래된), 5일전, 4일전]
    // Indices: 3, 2, 1, 0, 6, 5, 4
    val displayIndices = listOf(3, 2, 1, 0, 6, 5, 4)
    
    // 데이터 안전 처리 (7개 미만인 경우 0으로 채움)
    val safeData = if (data.size >= 7) data else data + List(7 - data.size) { 0 }
    val maxCount = safeData.maxOrNull() ?: 1
    
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceBetween, // 간격을 최대로 벌림 (양쪽 끝 포함)
        verticalAlignment = Alignment.Bottom
    ) {
        displayIndices.forEach { daysAgo ->
            val count = safeData.getOrElse(daysAgo) { 0 }
            
            // 높이 비율 계산 (최대값 기준)
            // count가 0이어도 최소 높이(0.15f)는 부여하여 "작게 바를 만들어줘" 요구사항 충족
            val heightRatio = if (maxCount > 0) {
                (count.toFloat() / maxCount.toFloat()).coerceIn(0.15f, 1f)
            } else 0.15f
            
            // 불투명도 계산 (수정됨)
            // 1. 값이 0이면 "가장 오래된 날보다 약하게" -> 0.1f
            // 2. 그 외: "오늘이 가장 높고(1.0), 오래될수록 약해짐(->0.3)"
            //    daysAgo: 0(오늘) ~ 6(6일전)
            val alpha = if (count == 0) {
                0.1f 
            } else {
                1.0f - (daysAgo / 6f) * 0.7f // 0일때 1.0, 6일때 0.3
            }
            
            Box(
                modifier = Modifier
                    .width(6.dp) 
                    .fillMaxHeight(heightRatio)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

// ==== 4. 탭 바 컴포넌트 ====
@Composable
fun DashboardTabBar(
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit
) {
    // 탭바 전체를 감싸는 알약 모양의 글래스모피즘 박스
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(64.dp)
    ) {
        // 1. 메인 글래스 패널 (알약 모양) - Dark Glass Theme
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.05f), // 검정색 반투명 배경
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.3f), // 상단: 은은한 하이라이트
                        Color.White.copy(alpha = 0.05f) // 하단: 거의 보이지 않음
                    ),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            ),
            shadowElevation = 0.dp
        ) {
            // 내부 안개 효과 (어두운 깊이감 + 노이즈 텍스처)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.3f),
                                Color.Black.copy(alpha = 0.1f)
                            )
                        )
                    )
                    .padding(6.dp)
            ) {
                // *** 3. 슬라이딩 인디케이터 및 탭 아이템 구현 ***
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val tabWidth = maxWidth / DashboardTab.values().size
                    
                    // 인디케이터 위치 애니메이션
                    val indicatorOffset by animateDpAsState(
                        targetValue = tabWidth * selectedTab.ordinal,
                        animationSpec = spring(
                            stiffness = Spring.StiffnessMediumLow, // 반응성 향상 (Low -> MediumLow)
                            dampingRatio = Spring.DampingRatioNoBouncy
                        ),
                        label = "indicatorOffset"
                    )

                    // 3-1. 움직이는 인디케이터 (선택된 탭 배경)
                    Surface(
                        modifier = Modifier
                            .width(tabWidth)
                            .fillMaxHeight()
                            .offset(x = indicatorOffset),
                        color = Color.White.copy(alpha = 0.85f), // 불투명에 가까운 흰색
                        shape = CircleShape, // 둥근 알약 모양
                        shadowElevation = 4.dp // 그림자 추가로 입체감
                    ) {}

                    // 3-2. 탭 아이템들 (Row로 배치)
                    Row(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        DashboardTab.values().forEach { tab ->
                            val isSelected = selectedTab == tab
                            
                            // 텍스트/아이콘 색상 애니메이션
                            val contentColor by animateColorAsState(
                                targetValue = if (isSelected) Color(0xFF1A1A1A) else TextSecondary.copy(alpha = 0.9f),
                                animationSpec = tween(durationMillis = 200), // 300ms -> 200ms 단축
                                label = "contentColor"
                            )

                            Box(
                                modifier = Modifier
                                    .width(tabWidth)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .clickable { onTabSelected(tab) },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.foundation.Image(
                                        painter = painterResource(id = tab.iconRes),
                                        contentDescription = null,
                                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(contentColor),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    // 선택 안 된 상태에서도 텍스트 표시 (공간 충분함)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        tab.tabName,
                                        color = contentColor,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TabItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    // Deprecated: DashboardTabBar 내부로 로직 통합됨
}

// 탭 정의
enum class DashboardTab(
    val tabName: String,        // 탭바에 표시될 짧은 이름
    val headerTitle: String,    // 상단 헤더에 표시될 제목
    val subtitle: String,       // 소제목
    val iconRes: Int
) {
    RATIO("비율", "위험도간 차지 비율", "일일 고위험 · 중위험 · 저위험 탐지 비율", R.drawable.ic_chart),
    HISTORY("분류", "위험도별 상세 분석", "일일 고위험 · 중위험 · 저위험 탐지 시간 및 키워드", R.drawable.ic_classification),
    LOG("로그", "최근 탐지 기록", "실시간 알림 목록", R.drawable.ic_log)
}

// ==== 5. 하단 상세 위험 카드 ====
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailedRiskCard(
    title: String,
    riskDetail: RiskDetail,
    color: Color,
    iconRes: Int,
    isExpanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {}
) {
    DashboardCard(backgroundColor = BackgroundLight) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // 왼쪽 아이콘 원형 배경
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp) // 22dp -> 24dp (조금 키움)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        fontSize = 15.sp
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = riskDetail.count.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "개",
                            fontSize = 14.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(0.dp))
                
                // 분류 기준 해시태그 + 아코디언
                
                val criteria = when {
                    title.contains("고위험") -> listOf("직접적인 피해", "개인정보 요구")
                    title.contains("중위험") -> listOf("불법 금융 거래", "개인정보 요구")
                    title.contains("저위험") -> listOf("클릭 유도", "미끼성 낚시")
                    else -> emptyList()
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 해시태그
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        criteria.forEach { criterion ->
                            Surface(
                                color = color.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = criterion,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = color,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    
                    // 아코디언 아이콘
                    IconButton(
                        onClick = { onExpandedChange(!isExpanded) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "확장/축소",
                            tint = color,
                            modifier = Modifier.rotate(if (isExpanded) 180f else 0f)
                        )
                    }
                }

                // 확장 가능한 탐지 문구 섹션
                AnimatedVisibility(visible = isExpanded) {
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "오늘 탐지된 문구",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // FlowRow로 자동 줄바꿈되는 해시태그
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            riskDetail.tags.forEach { tag ->
                                Surface(
                                    color = color.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = tag,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 시간별 탐지 분포 (0~2시, ... 22~24시) - 총 12개 구간
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val timeSlots = 12
                    // 데이터가 없는 경우 기본값 0으로 채움
                    val distribution = if (riskDetail.timeDistribution.size == 12) {
                        riskDetail.timeDistribution
                    } else {
                        List(12) { 0 }
                    }
                    
                    repeat(timeSlots) { index ->
                        val count = distribution[index]
                        // 300개 이상이면 100% (alpha 1.0), 1개 이상이면 최소 20% 투명도부터 시작
                        val alpha = if (count > 0) {
                            (count / 300f).coerceIn(0.2f, 1f)
                        } else 0f
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (count == 0) Color(0xFFE0E0E0) 
                                    else color.copy(alpha = alpha)
                                )
                        )
                    }
                }
            }
        }
    }
}


// ==== 보호 상태 배지 (글래스모피즘) ====
@Composable
fun ProtectionStatusBadge(
    isProtected: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isProtected) {
        Color(0xFF143D95) // 보호중 - 파란색
    } else {
        Color(0xFF5E0B0B) // 미보호 - 빨간색
    }
    
    val statusText = if (isProtected) "보호중" else "미보호"
    
    // 더 세련된 글래스모피즘 박스
    Box(
        modifier = modifier
            // .height(40.dp) 제거하여 높이 자동 조정
            // .width(100.dp) 제거하여 내용에 따라 너비 자동 조정
    ) {
        // 배경 그림자 레이어 (깊이감 추가)
        Box(
            modifier = Modifier
                .matchParentSize() // 부모 크기에 맞춤 (내용물에 따라 부모 크기가 결정됨)
                .padding(2.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        radius = 120f
                    )
                )
        )
        
        // 메인 글래스 카드
        Surface(
            modifier = Modifier
                // .fillMaxSize() 제거 -> 내용물 크기에 맞춤
                .padding(1.dp),
            shape = RoundedCornerShape(26.dp),
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.2f),
                        Color.White.copy(alpha = 0.05f)
                    )
                )
            )
        ) {
            Box(
                modifier = Modifier
                    // .fillMaxSize() 제거 -> 내용물 크기에 맞춤
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                backgroundColor.copy(alpha = 0.12f),
                                backgroundColor.copy(alpha = 0.06f)
                            )
                        )
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // 아이콘 (XML 리소스 교체)
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = if (isProtected) R.drawable.ic_shield_blue else R.drawable.ic_shield_red),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = backgroundColor,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
fun RecentAlertSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon Skeleton
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.2f))
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Title Skeleton
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Gray.copy(alpha = 0.2f))
            )
            // Message Skeleton
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Gray.copy(alpha = 0.2f))
            )
        }
    }
}
