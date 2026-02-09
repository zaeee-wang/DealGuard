package com.onguard.presentation.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import com.onguard.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.util.concurrent.TimeUnit
import com.onguard.presentation.theme.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.shadow
import kotlinx.coroutines.delay

/**
 * 모니터링 대상 앱 목록
 */
data class MonitoredApp(
    val packageName: String,
    val displayName: String,
    val enabledIconRes: Int,
    val disabledIconRes: Int,
    val category: String
)

private val monitoredApps = listOf(
    // 메신저
    MonitoredApp("com.kakao.talk", "카카오톡", R.drawable.ic_massagebox_blue, R.drawable.ic_massagebox_red, "메신저"),
    MonitoredApp("org.telegram.messenger", "텔레그램", R.drawable.ic_massagebox_blue, R.drawable.ic_massagebox_red, "메신저"),
    MonitoredApp("jp.naver.line.android", "라인", R.drawable.ic_massagebox_blue, R.drawable.ic_massagebox_red, "메신저"),
    MonitoredApp("com.facebook.orca", "페이스북 메신저", R.drawable.ic_massagebox_blue, R.drawable.ic_massagebox_red, "메신저"),
    MonitoredApp("com.google.android.apps.messaging", "Google 메시지", R.drawable.ic_massagebox_blue, R.drawable.ic_massagebox_red, "메신저"),
    MonitoredApp("com.samsung.android.messaging", "삼성 메시지", R.drawable.ic_massagebox_blue, R.drawable.ic_massagebox_red, "메신저"),
    MonitoredApp("com.instagram.android", "인스타그램 DM", R.drawable.ic_massagebox_blue, R.drawable.ic_massagebox_red, "메신저"),
    
    // 통화
    MonitoredApp("com.whatsapp", "왓츠앱", R.drawable.ic_call_blue, R.drawable.ic_call_red, "통화"),
    MonitoredApp("com.discord", "디스코드", R.drawable.ic_call_blue, R.drawable.ic_call_red, "통화"),
    
    // 중고거래
    MonitoredApp("kr.co.daangn", "당근마켓", R.drawable.ic_cart_blue, R.drawable.ic_cart_red, "중고거래")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.pointerInput(Unit) {
             var totalDrag = 0f
             detectHorizontalDragGestures(
                 onDragStart = { totalDrag = 0f },
                 onDragEnd = {
                     if (totalDrag > 150f) { // 오른쪽으로 충분히 스와이프
                         onBackClick()
                     }
                 }
             ) { change, dragAmount ->
                 totalDrag += dragAmount
             }
        },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("설정", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        contentWindowInsets = WindowInsets.systemBars
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()) // 상단 패딩만 적용 (하단은 contentPadding으로 처리)
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(
                    top = 16.dp, 
                    bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                    start = 0.dp,
                    end = 0.dp
                ) // 하단 네비게이션 바 고려
            ) {
                // 전역 탐지 설정
                item {
                    GlobalDetectionCard(
                        isEnabled = uiState.settings.isDetectionEnabled,
                        isPaused = !uiState.settings.isActiveNow() && uiState.settings.remainingPauseTime() > 0,
                        remainingPauseTime = uiState.settings.remainingPauseTime(),
                        detectionStartTime = uiState.settings.detectionStartTime,
                        sessionAccumulatedTime = uiState.settings.sessionAccumulatedTime,
                        onToggle = viewModel::setDetectionEnabled,
                        onPauseClick = { viewModel.showPauseDialog() },
                        onResumeClick = { viewModel.resumeDetection() }
                    )
                }

                // 권한 상태 섹션
                item {
                    Text(
                        text = "권한 설정",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Text(
                        text = "앱이 정상 작동하려면 아래 권한이 필요합니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    PermissionsCard(
                        isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                        isOverlayEnabled = uiState.isOverlayEnabled,
                        onRefresh = { viewModel.checkPermissions() }
                    )
                }


                // 섹션 헤더
                item {
                    Text(
                        text = "앱별 탐지 설정",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = "특정 앱에서 탐지를 비활성화할 수 있습니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 앱별 설정 카드
                item {
                    AppSettingsCard(
                        disabledApps = uiState.settings.disabledApps,
                        onAppToggle = { packageName, enabled ->
                            viewModel.setAppEnabled(packageName, enabled)
                        },
                        onEnableAll = viewModel::enableAllApps,
                        onEnableApps = viewModel::enableApps
                    )
                }

                }
            }
        }


    // 일시 중지 다이얼로그
    if (uiState.showPauseDialog) {
        PauseDetectionDialog(
            onDismiss = { viewModel.dismissPauseDialog() },
            onPause = { duration -> viewModel.pauseDetection(duration.minutes) }
        )
    }
}

@Composable
fun ModernSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF143D95) else Color(0xFFE9E9EA), // Blue : iOS Gray
        label = "trackColor"
    )
    
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        label = "thumbOffset"
    )

    Box(
        modifier = modifier
            .width(51.dp)
            .height(31.dp)
            .clip(RoundedCornerShape(100))
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .size(27.dp)
                .offset(x = thumbOffset)
                .align(Alignment.CenterStart)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .background(Color.White, CircleShape)
        )
    }
}

@Composable
fun GlobalDetectionCard(
    isEnabled: Boolean,
    isPaused: Boolean,
    remainingPauseTime: Long,
    detectionStartTime: Long,
    sessionAccumulatedTime: Long,
    onToggle: (Boolean) -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit
) {
    // 세션 타이머 상태
    var currentSessionTime by remember { mutableLongStateOf(sessionAccumulatedTime) }

    // 타이머 갱신 로직
    LaunchedEffect(isEnabled, isPaused, detectionStartTime, sessionAccumulatedTime) {
        if (isEnabled && !isPaused && detectionStartTime > 0) {
            while (true) {
                val now = System.currentTimeMillis()
                currentSessionTime = sessionAccumulatedTime + (now - detectionStartTime)
                delay(1000L) // 1초마다 갱신
            }
        } else {
            // 정지 또는 일시정지 상태에서는 누적된 시간만 표시
            currentSessionTime = sessionAccumulatedTime
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (isEnabled && !isPaused) Color(0xFF143D95).copy(alpha = 0.1f)
                                else Color(0xFF5E0B0B).copy(alpha = 0.1f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = if (isEnabled && !isPaused)
                                R.drawable.ic_shield_blue
                            else
                                R.drawable.ic_shield_red),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "스캠 탐지",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                !isEnabled -> "비활성화됨"
                                isPaused -> "일시 중지됨"
                                else -> "탐지 중: ${formatDuration(currentSessionTime)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!isEnabled || isPaused) {
                    // 재생 버튼 (활성화 또는 재개)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (isPaused) onResumeClick()
                                    else onToggle(true)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(id = R.drawable.ic_action_play),
                                    contentDescription = "활성화",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                } else {
                    // 정지 및 일시정지 버튼 그룹
                    Surface(
                        shape = RoundedCornerShape(50), // 완전 둥근 형태 (Pill shape)
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // 일시정지 버튼
                            IconButton(
                                onClick = onPauseClick,
                                modifier = Modifier.size(32.dp)
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(id = R.drawable.ic_action_pause),
                                    contentDescription = "일시정지",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            // 정지 버튼 (비활성화)
                            IconButton(
                                onClick = { onToggle(false) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(id = R.drawable.ic_action_end),
                                    contentDescription = "비활성화",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 일시 중지 상태 표시
            if (isPaused && isEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFB74D).copy(alpha = 0.15f), // Orange tint
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color(0xFFFFB74D) // Orange
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "남은 시간: ${formatRemainingTime(remainingPauseTime)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextWhite
                            )
                        }
                        TextButton(onClick = onResumeClick) {
                            Text("재개", color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }


        }
    }
}

@Composable
fun AppSettingsCard(
    disabledApps: Set<String>,
    onAppToggle: (String, Boolean) -> Unit,
    onEnableAll: () -> Unit,
    onEnableApps: (Collection<String>) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // 1. Header Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    val allEnabled = disabledApps.isEmpty()
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (allEnabled) Color(0xFF143D95).copy(alpha = 0.1f)
                                else Color(0xFF5E0B0B).copy(alpha = 0.1f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = if (allEnabled) R.drawable.ic_appgroup_blue else R.drawable.ic_appgroup_red),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "탐지 활성화",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "아래 앱을 선택해주세요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                if (disabledApps.isNotEmpty()) {
                    IconButton(
                        onClick = onEnableAll,
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = R.drawable.ic_refresh_all),
                            contentDescription = "모두 활성화",
                            modifier = Modifier.size(28.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. Grouped App List
            val groupedApps = monitoredApps.groupBy { it.category }
            
            groupedApps.forEach { (category, apps) ->
                val appsInGroup = apps.map { it.packageName }
                val isAnyDisabledInGroup = appsInGroup.any { it in disabledApps }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (isAnyDisabledInGroup) {
                        IconButton(
                            onClick = { onEnableApps(appsInGroup) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.drawable.ic_refresh_all),
                                contentDescription = "$category 그룹 활성화",
                                modifier = Modifier.size(24.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    }
                }
                
                apps.forEach { app ->
                    val isEnabled = app.packageName !in disabledApps

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) // Card-like background
                            .clickable { onAppToggle(app.packageName, !isEnabled) }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = if (isEnabled) app.enabledIconRes else app.disabledIconRes),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = app.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        ModernSwitch(
                            checked = isEnabled,
                            onCheckedChange = { onAppToggle(app.packageName, it) }
                        )
                    }
                }
                
                if (category != groupedApps.keys.last()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun PauseDetectionDialog(
    onDismiss: () -> Unit,
    onPause: (PauseDuration) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFB74D).copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = Color(0xFFFFB74D)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "탐지 일시 중지",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "일시 중지할 시간을 선택하세요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Duration Options in Grid or List
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PauseDuration.entries.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowItems.forEach { duration ->
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onPause(duration) },
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = duration.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            // Fill empty space if row has only 1 item
                            if (rowItems.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Cancel Button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "취소",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 남은 시간 포맷팅 (밀리초 → "X시간 Y분" 또는 "X분")
 */
private fun formatRemainingTime(remainingMs: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60

    return when {
        hours > 0 && minutes > 0 -> "${hours}시간 ${minutes}분"
        hours > 0 -> "${hours}시간"
        minutes > 0 -> "${minutes}분"
        else -> "1분 미만"
    }
}

/**
 * 권한 상태 카드
 */
@Composable

fun PermissionsCard(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (isAccessibilityEnabled && isOverlayEnabled) Color(0xFF143D95).copy(alpha = 0.1f)
                                else Color(0xFF5E0B0B).copy(alpha = 0.1f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = if (isAccessibilityEnabled && isOverlayEnabled)
                                R.drawable.ic_setting_blue
                            else
                                R.drawable.ic_setting_red),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = if (isAccessibilityEnabled && isOverlayEnabled)
                                "모든 권한이 활성화됨"
                            else
                                "권한이 필요합니다",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isAccessibilityEnabled && isOverlayEnabled)
                                "앱이 정상적으로 작동합니다"
                            else
                                "아래 권한을 활성화해주세요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onRefresh) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.ic_refresh_setting),
                        contentDescription = "새로고침",
                        modifier = Modifier.size(24.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 접근성 서비스 권한
            // 접근성 서비스 권한
            PermissionRow(
                title = "접근성 서비스",
                description = "메시지 모니터링에 필요",
                isEnabled = isAccessibilityEnabled,
                enabledIconRes = R.drawable.ic_access_blue,
                disabledIconRes = R.drawable.ic_access_red,
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 화면 위에 표시 권한
            // 화면 위에 표시 권한
            PermissionRow(
                title = "화면 위에 표시",
                description = "스캠 경고 배너 표시에 필요",
                isEnabled = isOverlayEnabled,
                enabledIconRes = R.drawable.ic_overlay_blue,
                disabledIconRes = R.drawable.ic_overlay_red,
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )
        }
    }
}

/**
 * 개별 권한 행
 */
@Composable
fun PermissionRow(
    title: String,
    description: String = "",
    isEnabled: Boolean,
    enabledIconRes: Int,
    disabledIconRes: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = if (isEnabled) enabledIconRes else disabledIconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (description.isNotEmpty()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        ModernSwitch(
            checked = isEnabled,
            onCheckedChange = { onClick() }
        )
    }
}

// ============================================
// Preview
// ============================================

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingsScreenPreview() {
    com.onguard.presentation.theme.OnGuardTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // Preview를 위한 Mock UI State
            val mockUiState = SettingsUiState(
                settings = com.onguard.data.local.DetectionSettings(
                    isDetectionEnabled = true,
                    pauseUntilTimestamp = 0L,
                    disabledApps = setOf("com.whatsapp", "com.discord")
                ),
                isLoading = false,
                showPauseDialog = false,
                isAccessibilityEnabled = true,
                isOverlayEnabled = false
            )
            
            // Mock 화면 구조 (ViewModel 없이 직접 UI 렌더링)
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("설정", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = {}) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {

                    // 전역 탐지 설정
                    item {
                        GlobalDetectionCard(
                            isEnabled = mockUiState.settings.isDetectionEnabled,
                            isPaused = false,
                            remainingPauseTime = 0,
                            detectionStartTime = 0L,
                            sessionAccumulatedTime = 0L,
                            onToggle = {},
                            onPauseClick = {},
                            onResumeClick = {}
                        )
                    }

                    // 권한 상태 섹션
                    item {
                        Text(
                            text = "권한 설정",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "앱이 정상 작동하려면 아래 권한이 필요합니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    item {
                        PermissionsCard(
                            isAccessibilityEnabled = mockUiState.isAccessibilityEnabled,
                            isOverlayEnabled = mockUiState.isOverlayEnabled,
                            onRefresh = {}
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 앱별 탐지 설정
                    item {
                        Text(
                            text = "앱별 설정",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = "특정 앱에서 탐지를 비활성화할 수 있습니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    item {
                        AppSettingsCard(
                            disabledApps = mockUiState.settings.disabledApps,
                            onAppToggle = { _, _ -> },
                            onEnableAll = {},
                            onEnableApps = {}
                        )
                    }
                }
            }
        }
    }
}
/**
 * 시간(밀리초)을 "HH:mm:ss" 형식으로 변환
 */
private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
