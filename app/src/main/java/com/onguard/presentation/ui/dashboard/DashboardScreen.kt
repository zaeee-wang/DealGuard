//app/src/main/java/com/onguard/presentation/ui/dashboard/DashboardScreen.kt

package com.onguard.presentation.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.painterResource
import com.onguard.R
import com.onguard.presentation.theme.*
import java.util.*
import androidx.compose.ui.platform.LocalConfiguration

enum class DashboardState {
    INITIAL, DETAILS, EXPANDED
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState = DashboardUiState()
) {
    // 상태 정의
    var dashboardState by remember { mutableStateOf(DashboardState.INITIAL) }
    var selectedTab by remember { mutableStateOf(DashboardTab.RATIO) }

    // 화면 높이 기반 계산
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val headerHeight = 22.dp
    // 확장 높이는 헤더 공간과 상단 여백을 고려하여 설정
    val expandedHeight = screenHeight - 120.dp 

    // 애니메이션 값 설정
    val transition = updateTransition(targetState = dashboardState, label = "dashboardTransition")

    // 1. 로고 Alpha (INITIAL에서만 보임)
    val logoAlpha by transition.animateFloat(
        label = "logoAlpha",
        transitionSpec = { tween(durationMillis = 300) }
    ) { state ->
        if (state == DashboardState.INITIAL) 1f else 0f
    }
    
    // 1.1 로고 Translation Y (위로 슬라이드)
    val logoTranslationY by transition.animateDp(
        label = "logoTranslationY",
        transitionSpec = { tween(durationMillis = 300) }
    ) { state ->
        if (state == DashboardState.INITIAL) 0.dp else (-30).dp
    }

    // 2. 메인 콘텐츠 Offset (INITIAL -> DETAILS 시 위로 이동)
    val contentOffset by transition.animateDp(
        label = "contentOffset",
        transitionSpec = { tween(durationMillis = 300) } // spring -> tween으로 변경
    ) { state ->
        when (state) {
            DashboardState.INITIAL -> 0.dp
            DashboardState.DETAILS -> (-60).dp // 로고 자리만큼 위로
            DashboardState.EXPANDED -> (-60).dp // 유지
        }
    }
    
    // 3. 상세 콘텐츠(카드/차트) Alpha & Offset (DETAILS 이상에서 보임)
    val detailsAlpha by transition.animateFloat(
        label = "detailsAlpha",
        transitionSpec = { tween(durationMillis = 300) }
    ) { state ->
        if (state == DashboardState.INITIAL) 0f else 1f
    }
    
    val detailsTranslationY by transition.animateDp(
        label = "detailsTranslationY",
        transitionSpec = { spring(stiffness = Spring.StiffnessLow) }
    ) { state ->
        if (state == DashboardState.INITIAL) 100.dp else 0.dp
    }

    // 4. 하단 오버레이 높이
    val sheetHeight by transition.animateDp(
        label = "sheetHeight",
        transitionSpec = { spring(stiffness = Spring.StiffnessLow) }
    ) { state ->
        if (state == DashboardState.EXPANDED) expandedHeight else headerHeight
    }
    
    // 배경 콘텐츠 Alpha (오버레이 확장 시 흐려짐)
    val backgroundContentAlpha by transition.animateFloat(
         label = "backgroundContentAlpha",
         transitionSpec = { tween(durationMillis = 300) }
    ) { state ->
        if (state == DashboardState.EXPANDED) 0f else 1f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandGradient)
            .pointerInput(dashboardState) { // Key로 state를 사용하여 재구성 방지
                var accumulatedDrag = 0f
                detectVerticalDragGestures(
                    onDragEnd = {
                        // 드래그 종료 시 누적 값을 기준으로 상태 전환 (한 번만)
                        val threshold = 100f // 임계값을 크게 설정하여 의도적인 스와이프만 감지
                        if (accumulatedDrag < -threshold) { // 위로 스와이프
                            when (dashboardState) {
                                DashboardState.INITIAL -> dashboardState = DashboardState.DETAILS
                                DashboardState.DETAILS -> dashboardState = DashboardState.EXPANDED
                                else -> {}
                            }
                        } else if (accumulatedDrag > threshold) { // 아래로 스와이프
                            when (dashboardState) {
                                DashboardState.EXPANDED -> dashboardState = DashboardState.INITIAL // 바로 초기화면으로
                                DashboardState.DETAILS -> dashboardState = DashboardState.INITIAL
                                else -> {}
                            }
                        }
                        accumulatedDrag = 0f // 초기화
                    },
                    onDragCancel = {
                        accumulatedDrag = 0f // 취소 시 초기화
                    }
                ) { change, dragAmount ->
                    change.consume()
                    accumulatedDrag += dragAmount // 드래그 양 누적
                }
            }
    ) {
        // 1. 메인 콘텐츠 (뒤쪽 레이어)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = contentOffset)
                .alpha(backgroundContentAlpha) // 오버레이 확장 시 배경 숨김
                .padding(bottom = headerHeight + 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1.1 상단 고정 영역 (날짜)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1.1 상단 고정 영역 (로고 - 배경에 위치)
                Row(
                    modifier = Modifier
                        .height(28.dp) // 로고 크기에 맞춰 축소
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    // 텍스트가 포함된 로고 이미지 사용
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.ic_logo),
                        contentDescription = "App Logo",
                        modifier = Modifier
                            .height(28.dp) // 고정 크기
                            .graphicsLayer {
                                alpha = logoAlpha
                                translationY = logoTranslationY.toPx() // 위로 슬라이드
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // 1.2 중간 콘텐츠 영역 (위험도 통계)
            // AnimatedVisibility 제거 -> 항상 표시
            // 상태별 상단 패딩 애니메이션
            val statusBadgeTopPadding by transition.animateDp(
                label = "statusBadgeTopPadding",
                transitionSpec = { tween(durationMillis = 300) }
            ) { state ->
                when (state) {
                    DashboardState.INITIAL -> 0.dp
                    DashboardState.DETAILS, DashboardState.EXPANDED -> 0.dp
                }
            }
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = statusBadgeTopPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StatusBadge(state.status)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "%,d".format(state.totalDetectionCount),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    lineHeight = 72.sp
                )
                Text(
                    text = "회",
                    fontSize = 20.sp,
                    color = TextWhite.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(32.dp))
                
                // 탐지 문자 위험도 카드 (항상 표시)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.5f))
                        .padding(20.dp)
                ) {
                    Column {
                        // 타이틀 영역
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.05f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "탐지 키워드 위험도 분류",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(20.dp))
                        
                        // 3개 수치 나열 (세로형 리스트)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RiskStatItem("고위험", state.highRiskCount, RiskHigh)
                            RiskStatItem("중위험", state.mediumRiskCount, RiskMedium)
                            RiskStatItem("저위험", state.lowRiskCount, RiskLow)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                
                // 키워드/시간 차트 (DETAILS 이상에서만 표시)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = detailsAlpha
                            translationY = detailsTranslationY.toPx()
                        },
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                // ... charts ...
                ChartStatCard(
                    "탐지 키워드", state.totalKeywords.toString(), SearhKeyword, "개",
                    chartPlaceholder = { MiniBarChart(SearhKeyword) },
                    modifier = Modifier.weight(1f)
                )
                ChartStatCard(
                    "탐지 시간", state.totalDetectionHours.toString(), SearhTime, "시간",
                    chartPlaceholder = { MiniBarChart(SearhTime) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    // 2. 하단 오버레이 (Daily Updates - 앞쪽 레이어)
    // Column으로 묶어서 헤더를 Surface 위에 배치
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
    ) {
        Spacer(modifier = Modifier.height(150.dp)) // 달력과 Daily Updates 간 간격 증가
        // 헤더 (Surface 외부로 이동)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { 
                    // 클릭 시 상태 전환 토글
                    dashboardState = if (dashboardState == DashboardState.EXPANDED) 
                        DashboardState.INITIAL 
                    else 
                        DashboardState.EXPANDED 
                }
                .padding(horizontal = 20.dp, vertical = 10.dp), // 패딩 조정
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
                Text(
                    "Daily Updates",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Icon(
                    imageVector = if (dashboardState == DashboardState.EXPANDED) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = TextWhite,
                    modifier = Modifier.size(28.dp)
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sheetHeight),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = CardBackground,
                shadowElevation = 16.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 드래그 핸들 (시각적 요소 추가)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp), // 터치/드래그 영역 (시각적 요소만 제공)
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.Gray.copy(alpha = 0.3f))
                        )
                    }

                    // 콘텐츠 (탭 및 상세 내용)
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 탭 바
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 0.dp) // 상단 패딩 제거
                        ) {
                            DashboardTabBar(
                                selectedTab = selectedTab,
                                onTabSelected = { selectedTab = it }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp)) // 탭바와 콘텐츠 간격 추가

                        // 내부 스크롤 콘텐츠
                        val density = LocalDensity.current
                        val slideDistance = with(density) { 10.dp.roundToPx() } // 20dp -> 10dp로 감소하여 잘림 방지

                        // 탭 내용 전환 애니메이션
                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(300)) + slideInVertically { slideDistance } with
                                fadeOut(animationSpec = tween(300)) + slideOutVertically { -slideDistance }
                            },
                            label = "TabContent"
                        ) { targetTab ->
                            val scrollState = rememberScrollState()
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(scrollState) {
                                        var totalDragX = 0f
                                        var totalDragY = 0f
                                        var isDraggingHorizontal: Boolean? = null
                                        
                                        detectDragGestures(
                                            onDragStart = { 
                                                totalDragX = 0f
                                                totalDragY = 0f
                                                isDraggingHorizontal = null
                                            },
                                            onDragEnd = {
                                                // 수평 드래그 - 탭 전환
                                                if (isDraggingHorizontal == true) {
                                                    val tabs = DashboardTab.values()
                                                    val currentIndex = tabs.indexOf(selectedTab)
                                                    if (totalDragX < -100f) { // Swipe Left -> Next
                                                        val nextIndex = (currentIndex + 1).coerceAtMost(tabs.lastIndex)
                                                        selectedTab = tabs[nextIndex]
                                                    } else if (totalDragX > 100f) { // Swipe Right -> Prev
                                                        val prevIndex = (currentIndex - 1).coerceAtLeast(0)
                                                        selectedTab = tabs[prevIndex]
                                                    }
                                                } 
                                                // 수직 드래그 - 오버레이 닫기
                                                else if (totalDragY > 150f) { // 아래로 스와이프
                                                    dashboardState = DashboardState.INITIAL
                                                }
                                            }
                                        ) { change, dragAmount ->
                                            // 스크롤이 맨 위이고 아래로 드래그하는 경우, 수직 드래그로 우선 처리
                                            val isAtTop = scrollState.value == 0
                                            val isDraggingDown = dragAmount.y > 0
                                            
                                            if (isDraggingHorizontal == null) {
                                                // 첫 움직임 방향 결정
                                                if (isAtTop && isDraggingDown) {
                                                    // 스크롤 맨 위에서 아래로 드래그하면 수직으로 우선 처리
                                                    isDraggingHorizontal = false
                                                } else {
                                                    // 일반적인 경우 수평/수직 판단
                                                    isDraggingHorizontal = kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y)
                                                }
                                            }
                                            
                                            if (isDraggingHorizontal == true) {
                                                // 수평 드래그 - 탭 전환
                                                change.consume()
                                                totalDragX += dragAmount.x
                                            } else {
                                                // 수직 드래그 - 오버레이 닫기 또는 스크롤
                                                if (isAtTop && isDraggingDown) {
                                                    // 스크롤 맨 위에서 아래로 드래그하면 consume (오버레이 닫기)
                                                    change.consume()
                                                    totalDragY += dragAmount.y
                                                }
                                                // 그 외에는 스크롤에 맡김
                                            }
                                        }
                                    }
                                    .verticalScroll(scrollState)
                                    .padding(horizontal = 20.dp)
                                    .navigationBarsPadding()
                                    .padding(bottom = 20.dp)
                            ) {
                                when (targetTab) {
                                    DashboardTab.RATIO -> {
                                        StaggeredAnimatedItem(delay = 0) {
                                            DailyRiskSummaryCard(state.dailyStats)
                                        }
                                        Spacer(modifier = Modifier.height(40.dp))
                                    }
                                    DashboardTab.HISTORY -> {
                                        StaggeredAnimatedItem(delay = 0) {
                                            DetailedRiskCard("일일 고위험 탐지", state.dailyStats.highRiskDetail, RiskHigh, Icons.Default.Warning)
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        StaggeredAnimatedItem(delay = 100) {
                                            DetailedRiskCard("일일 중위험 탐지", state.dailyStats.mediumRiskDetail, RiskMedium, Icons.Default.ErrorOutline)
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        StaggeredAnimatedItem(delay = 200) {
                                            DetailedRiskCard("일일 저위험 탐지", state.dailyStats.lowRiskDetail, RiskLow, Icons.Default.HelpOutline)
                                        }
                                        Spacer(modifier = Modifier.height(40.dp))
                                    }
                                    DashboardTab.LOG -> {
                                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                            Text("로그 기능은 준비 중입니다.")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 상단 오버레이 (달력 - 독립적으로 동작)
        // EXPANDED 일 때 위에서 내려옴
        AnimatedVisibility(
            visible = dashboardState == DashboardState.EXPANDED,
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) { -it } + fadeIn(tween(300)),
            exit = slideOutVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) { -it } + fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding() // 상태바 패딩 적용
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp) // 로고 위치 쯤
            ) {
                WeekCalendarSection()
            }
        }
    }
}

@Composable
fun StaggeredAnimatedItem(
    delay: Int = 0,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delay.toLong())
        visible = true
    }

    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else 20.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "offsetY"
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationY = offsetY.toPx()
                this.alpha = alpha
            }
    ) {
        content()
    }
}

@Composable
fun WeekCalendarSection() {
    val calendar = Calendar.getInstance()
    val days = mutableListOf<Triple<String, Int, Int>>()
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    for (offset in -2..2) {
        val tempCalendar = Calendar.getInstance()
        tempCalendar.add(Calendar.DAY_OF_MONTH, offset)
        val dayOfWeek = tempCalendar.get(Calendar.DAY_OF_WEEK)
        val dayName = dayNames[(dayOfWeek + 5) % 7]
        val dayNumber = tempCalendar.get(Calendar.DAY_OF_MONTH)
        days.add(Triple(dayName, dayNumber, offset))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        days.forEach { (dayName, dayNumber, offset) ->
            val isToday = offset == 0
            val alpha = when (kotlin.math.abs(offset)) {
                0 -> 1.0f
                1 -> 0.85f
                2 -> 0.70f
                else -> 0.70f
            }
            val heightOffset = when (offset) {
                -2, 2 -> 0.dp
                -1, 1 -> 10.dp
                0 -> 20.dp
                else -> 0.dp
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .offset(y = heightOffset)
            ) {
                Text(
                    text = dayName,
                    fontSize = 12.sp,
                    color = TextWhite.copy(alpha = alpha),
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = dayNumber.toString(),
                    fontSize = if (isToday) 18.sp else 16.sp,
                    color = TextWhite.copy(alpha = alpha),
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun StatusBadge(status: SecurityStatus) {
    Surface(
        color = Color.White.copy(alpha = 0.25f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = TextWhite,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (status == SecurityStatus.PROTECTED) "보호중" else "미보호",
                color = TextWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun DailyRiskSummaryCard(dailyStats: DailyRiskStats) {
    DashboardCard(backgroundColor = BackgroundLight) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "일일 위험 탐지",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "고위험 + 중위험 + 저위험",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(20.dp))
                RiskRatioRow("일일 고위험 탐지", dailyStats.highRiskRatio, RiskHigh, Icons.Default.Warning)
                Spacer(modifier = Modifier.height(10.dp))
                RiskRatioRow("일일 중위험 탐지", dailyStats.mediumRiskRatio, RiskMedium, Icons.Default.ErrorOutline)
                Spacer(modifier = Modifier.height(10.dp))
                RiskRatioRow("일일 저위험 탐지", dailyStats.lowRiskRatio, RiskLow, Icons.Default.HelpOutline)
            }
            Spacer(modifier = Modifier.width(20.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(140.dp)
            ) {
                CircularProgressIndicator(
                    progress = 1f,
                    modifier = Modifier.size(140.dp),
                    color = Color(0xFFE0E0E0),
                    strokeWidth = 16.dp
                )
                CircularProgressIndicator(
                    progress = dailyStats.highRiskRatio,
                    modifier = Modifier.size(140.dp),
                    color = RiskHigh,
                    strokeWidth = 16.dp
                )
                CircularProgressIndicator(
                    progress = dailyStats.highRiskRatio + dailyStats.mediumRiskRatio,
                    modifier = Modifier.size(140.dp),
                    color = RiskMedium,
                    strokeWidth = 16.dp
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "누적",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        "%,d".format(dailyStats.cumulativeCount),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun RiskRatioRow(title: String, ratio: Float, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodySmall, color = color)
            Text("${(ratio * 100)}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RiskStatItem(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.025f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
        
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "%,d".format(count),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "회",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
fun DashboardScreenPreview() {
    DashboardScreen(state = DashboardUiState())
}