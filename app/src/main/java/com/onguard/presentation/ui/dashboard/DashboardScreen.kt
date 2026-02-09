//app/src/main/java/com/onguard/presentation/ui/dashboard/DashboardScreen.kt

package com.onguard.presentation.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.platform.LocalContext

enum class DashboardState {
    INITIAL, DETAILS, EXPANDED
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState = DashboardUiState()
) {
    // Context for navigation
    val context = LocalContext.current
    
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

    // 배경 그라데이션 (보호 상태 및 테마에 따라 변경)
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundBrush = if (state.status == SecurityStatus.PROTECTED) {
        if (isDarkTheme) BrandGradientDarkBlue else BrandGradientBlue
    } else {
        if (isDarkTheme) BrandGradientDarkRed else BrandGradientRed
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .pointerInput(dashboardState) { // Key로 state를 사용하여 재구성 방지
                var accumulatedDrag = 0f
                var totalDragX = 0f
                var totalDragY = 0f
                var isHorizontalDrag = false
                
                detectDragGestures(
                    onDragStart = { 
                        accumulatedDrag = 0f 
                        totalDragX = 0f
                        totalDragY = 0f
                        isHorizontalDrag = false
                    },
                    onDragEnd = {
                        if (isHorizontalDrag) {
                            // 수평 스와이프 (왼쪽으로, 설정 화면 이동)
                            if (totalDragX < -150f) {
                                val intent = android.content.Intent(context, com.onguard.presentation.ui.settings.SettingsActivity::class.java)
                                context.startActivity(intent)
                                if (context is android.app.Activity) {
                                    context.overridePendingTransition(
                                        com.onguard.R.anim.slide_in_right,
                                        com.onguard.R.anim.slide_out_left
                                    )
                                }
                            }
                        } else {
                            // 수직 스와이프 (기존 로직)
                            val threshold = 100f
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
                            accumulatedDrag = 0f
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    totalDragX += dragAmount.x
                    totalDragY += dragAmount.y
                    
                    // 방향 결정 (초기 움직임으로 판단)
                    if (!isHorizontalDrag && kotlin.math.abs(totalDragX) > kotlin.math.abs(totalDragY)) {
                       if (kotlin.math.abs(totalDragX) > 20f) { // 약간의 threshold
                           isHorizontalDrag = true
                       }
                    }
                    
                    if (!isHorizontalDrag) {
                        accumulatedDrag += dragAmount.y
                    }
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
                    horizontalArrangement = Arrangement.SpaceBetween // 양쪽 정렬
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
                    
                    // 설정 아이콘 버튼
                    IconButton(
                        onClick = { 
                            val intent = android.content.Intent(context, com.onguard.presentation.ui.settings.SettingsActivity::class.java)
                            context.startActivity(intent)
                            if (context is android.app.Activity) {
                                context.overridePendingTransition(
                                    com.onguard.R.anim.slide_in_right,
                                    com.onguard.R.anim.slide_out_left
                                )
                            }
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer {
                                alpha = logoAlpha // 로고와 같이 사라짐
                                translationY = logoTranslationY.toPx()
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "설정",
                            tint = TextWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }
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
                ProtectionStatusBadge(
                    isProtected = state.isProtected
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "%,d".format(state.totalDetectionCount),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    lineHeight = 72.sp
                )
                Text(
                    text = "건",
                    fontSize = 20.sp,
                    color = TextWhite.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                // === 메인 페이지 컨텐츠 (총 누적 데이터 표시) ===
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
                                androidx.compose.foundation.Image(
                                    painter = painterResource(id = R.drawable.ic_magnifyingglass_text),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "위험도별 탐지 분류",
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
                            RiskStatItem("고위험 탐지", state.highRiskCount, RiskHigh, R.drawable.ic_magnifyingglass_cross)
                            RiskStatItem("중위험 탐지", state.mediumRiskCount, RiskMedium, R.drawable.ic_magnifyingglass_exclamation)
                            RiskStatItem("저위험 탐지", state.lowRiskCount, RiskLow, R.drawable.ic_magnifyingglass_question)
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
                    "탐지 키워드", state.totalKeywords.toString(), SearhKeyword, R.drawable.ic_keyword, "개",
                    chartPlaceholder = { WeeklyFrequencyChart(state.weeklyKeywordStats, SearhKeyword) },
                    modifier = Modifier.weight(1f)
                )
                ChartStatCard(
                    "탐지 시간", state.totalDetectionValue.toString(), SearhTime, R.drawable.ic_magnifyingglass_time, state.totalDetectionUnit,
                    chartPlaceholder = { WeeklyFrequencyChart(state.weeklyTimeStats, SearhTime) },
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
            // .padding(bottom = 80.dp) // 탭 바 공간 확보 제거 -> 오버레이가 탭 바 뒤로 확장됨
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
                shadowElevation = 0.dp // 그림자 제거
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
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = {
                                        // 드래그 종료 시 로직은 유지하되, Velocity 등을 고려하지 않고 단순 거리로 판단
                                    }
                                ) { change, dragAmount ->
                                    // ...
                                }
                            }
                            // 위 pointerInput을 아래와 같이 구현하여 적용
                            .pointerInput(Unit) {
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
                                            // 민감도 유지 (50f)
                                            if (totalDragX < -50f) { 
                                                val nextIndex = (currentIndex + 1).coerceAtMost(tabs.lastIndex)
                                                selectedTab = tabs[nextIndex]
                                            } else if (totalDragX > 50f) { 
                                                val prevIndex = (currentIndex - 1).coerceAtLeast(0)
                                                selectedTab = tabs[prevIndex]
                                            }
                                        } 
                                        // 수직 드래그 - 오버레이 닫기 (하향 스와이프)
                                        else if (totalDragY > 100f) { 
                                            dashboardState = DashboardState.INITIAL
                                        }
                                    }
                                ) { change, dragAmount ->
                                    if (isDraggingHorizontal == null) {
                                        // 초기 움직임으로 방향 판별 (탄력적)
                                        if (kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y)) {
                                            isDraggingHorizontal = true
                                        } else if (dragAmount.y > 0) {
                                            // 아래로 당길 때만 수직 제스처로 인정 (닫기 위함)
                                            isDraggingHorizontal = false
                                        }
                                    }
                                    
                                    if (isDraggingHorizontal == true) {
                                        totalDragX += dragAmount.x
                                        // 수평 드래그 중에는 상위/하위 스크롤 간섭 방지
                                        if (kotlin.math.abs(totalDragX) > 10f) {
                                             change.consume()
                                        }
                                    } else if (isDraggingHorizontal == false) {
                                        totalDragY += dragAmount.y
                                        // 닫기 제스처 감지 시 이벤트 소비
                                        if (totalDragY > 10f) {
                                            change.consume()
                                        }
                                    }
                                }
                            }
                    ) {
                        // Box로 전체 감싸기 (AnimatedVisibility의 align() 스코프 복구)
                        Box(modifier = Modifier.weight(1f)) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // 탭 바 제거됨 (외부로 이동)
                                
                                Spacer(modifier = Modifier.height(4.dp))

                                // 2. 데일리 업데이트 (탭 컨텐츠)
                                // 탭 제목 + 소제목 (AnimatedContent 밖으로 이동 - 슬라이드 효과 적용 안됨)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp)
                                ) {
                                    Text(
                                        text = selectedTab.headerTitle,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                                    )
                                    Text(
                                        text = selectedTab.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Black.copy(alpha = 0.6f),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                                    )
                                }

                                // 탭 내용 전환 애니메이션
                                AnimatedContent(
                            targetState = selectedTab,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 20.dp) // 콘텐츠 폭에 맞춰 여백 확보
                                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)), // 확보된 영역 안에서 상단 라운드 처리
                            transitionSpec = {
                                val direction = if (targetState.ordinal > initialState.ordinal) {
                                    AnimatedContentTransitionScope.SlideDirection.Left
                                } else {
                                    AnimatedContentTransitionScope.SlideDirection.Right
                                }

                                // Spring 애니메이션으로 부드러움 극대화
                                slideIntoContainer(
                                    towards = direction,
                                    animationSpec = spring(
                                        stiffness = Spring.StiffnessLow, 
                                        dampingRatio = Spring.DampingRatioNoBouncy
                                    )
                                ) + fadeIn(
                                    animationSpec = tween(300)
                                ) togetherWith
                                slideOutOfContainer(
                                    towards = direction,
                                    animationSpec = spring(
                                        stiffness = Spring.StiffnessLow,
                                        dampingRatio = Spring.DampingRatioNoBouncy
                                    )
                                ) + fadeOut(
                                    animationSpec = tween(300)
                                )
                            },
                            label = "TabContent"
                        ) { targetTab ->
                            // HISTORY 탭의 아코디언 확장 상태 추적
                            val isHighRiskExpanded = remember { mutableStateOf(false) }
                            val isMediumRiskExpanded = remember { mutableStateOf(false) }
                            val isLowRiskExpanded = remember { mutableStateOf(false) }
                            val isAnyExpanded = isHighRiskExpanded.value || isMediumRiskExpanded.value || isLowRiskExpanded.value
                            
                            // 내부 스크롤 컬럼 (LazyColumn으로 변경하여 성능 최적화)
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize(), // navigationBarsPadding 제거하여 하단 꽉 채움
                                contentPadding = PaddingValues(bottom = 130.dp), // 탭 바 + 네비게이션 바 공간 확보
                                userScrollEnabled = when (targetTab) {
                                    DashboardTab.RATIO -> false // 비율 탭은 스크롤 비활성화
                                    DashboardTab.HISTORY -> isAnyExpanded // HISTORY 탭은 아코디언 열렸을 때만 스크롤 허용
                                    else -> true // 나머지 탭은 스크롤 허용
                                }
                            ) {
                                    // === 데일리 업데이트 탭 컨텐츠 (당일 데이터만 표시) ===
                                when (targetTab) {
                                    DashboardTab.RATIO -> {
                                        // 누적 탐지 비교 바 차트 (상단)
                                        item {
                                            StaggeredAnimatedItem(delay = 0) {
                                                CumulativeVsDailyComparisonCard(
                                                    totalCumulative = state.totalDetectionCount,
                                                    todayCumulative = state.dailyStats.highRiskDetail.count + 
                                                                     state.dailyStats.mediumRiskDetail.count + 
                                                                     state.dailyStats.lowRiskDetail.count
                                                )
                                            }
                                        }
                                        item { Spacer(modifier = Modifier.height(12.dp)) }
                                        // 일일 탐지 비교 파이 차트 (하단)
                                        item {
                                            StaggeredAnimatedItem(delay = 100) {
                                                DailyRiskSummaryCard(state.dailyStats)
                                            }
                                        }
                                        item { Spacer(modifier = Modifier.height(40.dp)) }
                                    }
                                    DashboardTab.HISTORY -> {
                                        item {
                                            StaggeredAnimatedItem(delay = 0) {
                                                DetailedRiskCard(
                                                    title = "고위험 키워드",
                                                    riskDetail = state.dailyStats.highRiskDetail,
                                                    color = RiskHigh,
                                                    iconRes = R.drawable.ic_massagebox_cross,
                                                    isExpanded = isHighRiskExpanded.value,
                                                    onExpandedChange = { isHighRiskExpanded.value = it }
                                                )
                                            }
                                        }
                                        item { Spacer(modifier = Modifier.height(12.dp)) }
                                        item {
                                            StaggeredAnimatedItem(delay = 100) {
                                                DetailedRiskCard(
                                                    title = "중위험 키워드",
                                                    riskDetail = state.dailyStats.mediumRiskDetail,
                                                    color = RiskMedium,
                                                    iconRes = R.drawable.ic_massagebox_exclamation,
                                                    isExpanded = isMediumRiskExpanded.value,
                                                    onExpandedChange = { isMediumRiskExpanded.value = it }
                                                )
                                            }
                                        }
                                        item { Spacer(modifier = Modifier.height(12.dp)) }
                                        item {
                                            StaggeredAnimatedItem(delay = 200) {
                                                DetailedRiskCard(
                                                    title = "저위험 키워드",
                                                    riskDetail = state.dailyStats.lowRiskDetail,
                                                    color = RiskLow,
                                                    iconRes = R.drawable.ic_massagebox_question,
                                                    isExpanded = isLowRiskExpanded.value,
                                                    onExpandedChange = { isLowRiskExpanded.value = it }
                                                )
                                            }
                                        }
                                        item { Spacer(modifier = Modifier.height(40.dp)) }
                                    }
                                    DashboardTab.LOG -> {
                                        if (state.recentAlerts.isEmpty()) {
                                            item {
                                                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                                    Text("최근 알림이 없습니다.", color = TextSecondary)
                                                }
                                            }
                                        } else {
                                            // 전체 삭제 버튼
                                            item {
                                                Button(
                                                    onClick = { /* TODO: Clear all alerts */ },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFFFF5252)
                                                    )
                                                ) {
                                                    Text("알림 전체 삭제", style = MaterialTheme.typography.labelLarge)
                                                }
                                                Spacer(modifier = Modifier.height(12.dp))
                                            }
                                            
                                            itemsIndexed(state.recentAlerts) { index, alert ->
                                                // 순차적 애니메이션 적용 (화면에 보이는 초기 아이템은 즉시 표시 - 프리로딩)
                                                StaggeredAnimatedItem(
                                                    delay = if (index < 8) 0 else (index % 6) * 50, 
                                                    skeleton = { RecentAlertSkeleton() }
                                                ) {
                                                    RecentAlertItem(alert = alert)
                                                }
                                                // Column의 spacedBy 대신 각 아이템 아래에 Spacer 추가
                                                Spacer(modifier = Modifier.height(8.dp))
                                            }
                                            item { Spacer(modifier = Modifier.height(40.dp)) }
                                        }
                                    }
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

        // 3. 하단 탭 바 (화면 최하단 고정) - EXPANDED 상태일 때만 등장
        AnimatedVisibility(
            visible = dashboardState == DashboardState.EXPANDED,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(1f) // 오버레이보다 위에 강제 배치
                .padding(horizontal = 20.dp, vertical = 10.dp) // 여백 추가
        ) {
            DashboardTabBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    }
}

@Composable
fun StaggeredAnimatedItem(
    delay: Int = 0,
    skeleton: @Composable (() -> Unit)? = null,
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

    Box {
        // Skeleton (Loading State)
        if (!visible && skeleton != null) {
            skeleton()
        }

        // Real Content (Animated)
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
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
        ) {
            // 타이틀
            Text(
                "일일 탐지 비교",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            
            Spacer(modifier = Modifier.height(20.dp))

            // 2. 하단 콘텐츠 (차트 + 태그)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween // 양쪽 배분
            ) {
                // 차트 (왼쪽)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(140.dp)
                ) {
                    // 배경 (회색, 전체 100%)
                    CircularProgressIndicator(
                        progress = 1f,
                        modifier = Modifier.size(140.dp),
                        color = Color(0xFFE0E0E0),
                        strokeWidth = 16.dp
                    )
                    // 저위험 (노랑, high + medium + low) - 조건부 렌더링
                    if (dailyStats.lowRiskRatio > 0f || dailyStats.mediumRiskRatio > 0f || dailyStats.highRiskRatio > 0f) {
                        CircularProgressIndicator(
                            progress = dailyStats.highRiskRatio + dailyStats.mediumRiskRatio + dailyStats.lowRiskRatio,
                            modifier = Modifier.size(140.dp),
                            color = RiskLow,
                            strokeWidth = 16.dp
                        )
                    }
                    // 중위험 (주황, high + medium) - 조건부 렌더링
                    if (dailyStats.mediumRiskRatio > 0f || dailyStats.highRiskRatio > 0f) {
                        CircularProgressIndicator(
                            progress = dailyStats.highRiskRatio + dailyStats.mediumRiskRatio,
                            modifier = Modifier.size(140.dp),
                            color = RiskMedium,
                            strokeWidth = 16.dp
                        )
                    }
                    // 고위험 (빨강, high만) - 조건부 렌더링
                    if (dailyStats.highRiskRatio > 0f) {
                        CircularProgressIndicator(
                            progress = dailyStats.highRiskRatio,
                            modifier = Modifier.size(140.dp),
                            color = RiskHigh,
                            strokeWidth = 16.dp
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "일일 탐지",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            "%,d".format(dailyStats.highRiskDetail.count + dailyStats.mediumRiskDetail.count + dailyStats.lowRiskDetail.count),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            "건",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(20.dp))

                // 태그/비율 정보 (오른쪽)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RiskRatioRow("고위험 탐지", dailyStats.highRiskRatio, RiskHigh, R.drawable.ic_magnifyingglass_cross)
                    RiskRatioRow("중위험 탐지", dailyStats.mediumRiskRatio, RiskMedium, R.drawable.ic_magnifyingglass_exclamation)
                    RiskRatioRow("저위험 탐지", dailyStats.lowRiskRatio, RiskLow, R.drawable.ic_magnifyingglass_question)
                }
            }
        }
    }
}



@Composable
fun RiskRatioRow(title: String, ratio: Float, color: Color, iconRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.Bold)
            val safeRatio = if (ratio.isNaN() || ratio.isInfinite()) 0f else ratio
            val percentage = safeRatio * 100
            val formattedPercentage = when {
                percentage == 0f -> "0%"
                percentage == 100f -> "100%"
                percentage % 1 == 0f -> "${percentage.toInt()}%" // 정수로 떨어지는 경우도 소수점 제외 (선택 사항이나 깔끔하게)
                else -> "${String.format("%.1f", percentage)}%"
            }
            Text(formattedPercentage, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
        }
    }
}

@Composable
fun RiskStatItem(label: String, count: Int, color: Color, iconRes: Int) {
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
                androidx.compose.foundation.Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
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
                text = "건",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
    }
}

@Composable
fun CumulativeVsDailyComparisonCard(
    totalCumulative: Int,
    todayCumulative: Int
) {
    DashboardCard(backgroundColor = BackgroundLight) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
        ) {
            // 타이틀
            Text(
                "누적 탐지 비교",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 라벨: 총 누적 (왼쪽) / 오늘 (오른쪽)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "총 누적",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "%,d 건".format(totalCumulative),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "오늘",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "%,d 건".format(todayCumulative),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2979EA)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 단일 바: 배경 회색(총 누적), 오른쪽에서 오늘 탐지량 채우기
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE0E0E0))
            ) {
                val ratio = if (totalCumulative > 0) {
                    (todayCumulative.toFloat() / totalCumulative.toFloat()).coerceIn(0f, 1f)
                } else 0f
                
                // 오른쪽에서 왼쪽으로 채우기
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ratio)
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF2979EA),
                                    Color(0xFF5B9BFF)
                                )
                            )
                        )
                )
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
fun DashboardScreenPreview() {
    DashboardScreen(state = DashboardUiState())
}