// app/src/main/java/com/onguard/presentation/ui/dashboard/DashboardComponents.kt

package com.onguard.presentation.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                    Icon(
                        Icons.Default.Analytics,
                        null,
                        tint = iconColor,
                        modifier = Modifier.size(16.dp)
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
                Column {
                    Text(
                        text = count,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Box(modifier = Modifier.size(40.dp)) {
                    chartPlaceholder()
                }
            }
        }
    }
}

// 미니 바 차트 플레이스홀더
@Composable
fun MiniBarChart(color: Color) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        val heights = listOf(0.4f, 0.7f, 0.5f, 0.9f, 0.6f, 0.8f)
        heights.forEach { height ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(height)
                    .background(color, shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BackgroundLight)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        DashboardTab.values().forEach { tab ->
            val isSelected = selectedTab == tab
            
            if (isSelected) {
                Surface(
                    modifier = Modifier.weight(1f),
                    color = CardBackground,
                    shape = RoundedCornerShape(10.dp),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .clickable { onTabSelected(tab) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            tab.icon,
                            null,
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            tab.title,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                TabItem(
                    icon = tab.icon,
                    text = tab.title,
                    modifier = Modifier.weight(1f),
                    onClick = { onTabSelected(tab) }
                )
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
    Row(
        modifier = modifier
            .padding(vertical = 10.dp)
            .clickable { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text,
            color = TextSecondary,
            fontSize = 14.sp
        )
    }
}

// 탭 정의
enum class DashboardTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    RATIO("비율", Icons.Default.PieChart),
    HISTORY("내역", Icons.Default.List),
    LOG("로그", Icons.Default.Description)
}

// ==== 5. 하단 상세 위험 카드 ====
@Composable
fun DetailedRiskCard(
    title: String,
    riskDetail: RiskDetail,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
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
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    null,
                    tint = TextWhite,
                    modifier = Modifier.size(22.dp)
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
                    Text(
                        text = riskDetail.count.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 태그 칩
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    riskDetail.tags.take(2).forEach { tag ->
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

                Spacer(modifier = Modifier.height(14.dp))

                // 프로그레스 바 (이미지 디자인과 동일하게)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val totalBars = 15
                    val filledBars = (riskDetail.count.toFloat() / 1000 * totalBars).toInt().coerceIn(0, totalBars)
                    
                    repeat(totalBars) { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (index < filledBars) color else Color(0xFFE0E0E0)
                                )
                        )
                    }
                }
            }
        }
    }
}