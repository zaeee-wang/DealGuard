package com.onguard.presentation.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.TimeUnit

/**
 * 모니터링 대상 앱 목록
 */
data class MonitoredApp(
    val packageName: String,
    val displayName: String,
    val icon: ImageVector
)

private val monitoredApps = listOf(
    MonitoredApp("com.kakao.talk", "카카오톡", Icons.Default.Chat),
    MonitoredApp("org.telegram.messenger", "텔레그램", Icons.Default.Send),
    MonitoredApp("com.whatsapp", "왓츠앱", Icons.Default.Phone),
    MonitoredApp("com.facebook.orca", "페이스북 메신저", Icons.Default.Message),
    MonitoredApp("com.instagram.android", "인스타그램", Icons.Default.CameraAlt),
    MonitoredApp("kr.co.daangn", "당근마켓", Icons.Default.ShoppingCart),
    MonitoredApp("jp.naver.line.android", "라인", Icons.Default.Chat),
    MonitoredApp("com.discord", "디스코드", Icons.Default.Headphones),
    MonitoredApp("com.google.android.apps.messaging", "Google 메시지", Icons.Default.Sms),
    MonitoredApp("com.samsung.android.messaging", "삼성 메시지", Icons.Default.Sms)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
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
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // 전역 탐지 설정
                item {
                    GlobalDetectionCard(
                        isEnabled = uiState.settings.isDetectionEnabled,
                        isPaused = uiState.settings.remainingPauseTime() > 0,
                        remainingPauseTime = uiState.settings.remainingPauseTime(),
                        onToggle = { viewModel.setDetectionEnabled(it) },
                        onPauseClick = { viewModel.showPauseDialog() },
                        onResumeClick = { viewModel.resumeDetection() }
                    )
                }

                // 섹션 헤더
                item {
                    Text(
                        text = "앱별 탐지 설정",
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

                // 앱별 설정 카드
                item {
                    AppSettingsCard(
                        disabledApps = uiState.settings.disabledApps,
                        onAppToggle = { packageName, enabled ->
                            viewModel.setAppEnabled(packageName, enabled)
                        },
                        onEnableAll = { viewModel.enableAllApps() }
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
fun GlobalDetectionCard(
    isEnabled: Boolean,
    isPaused: Boolean,
    remainingPauseTime: Long,
    onToggle: (Boolean) -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled && !isPaused)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
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
                    Icon(
                        imageVector = if (isEnabled && !isPaused)
                            Icons.Default.Shield
                        else
                            Icons.Default.ShieldMoon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (isEnabled && !isPaused)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "스캠 탐지",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                !isEnabled -> "비활성화됨"
                                isPaused -> "일시 중지됨"
                                else -> "활성화됨"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle
                )
            }

            // 일시 중지 상태 표시
            if (isPaused && isEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
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
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "남은 시간: ${formatRemainingTime(remainingPauseTime)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        TextButton(onClick = onResumeClick) {
                            Text("재개")
                        }
                    }
                }
            }

            // 일시 중지 버튼 (활성화 상태이고 일시 중지 아닐 때)
            if (isEnabled && !isPaused) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onPauseClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("일시 중지")
                }
            }
        }
    }
}

@Composable
fun AppSettingsCard(
    disabledApps: Set<String>,
    onAppToggle: (String, Boolean) -> Unit,
    onEnableAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 모두 활성화 버튼 (비활성화된 앱이 있을 때만)
            if (disabledApps.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onEnableAll) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("모두 활성화")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 앱 목록
            monitoredApps.forEachIndexed { index, app ->
                val isEnabled = app.packageName !in disabledApps

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onAppToggle(app.packageName, !isEnabled) }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = app.icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (isEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = app.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isEnabled)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { onAppToggle(app.packageName, it) }
                    )
                }

                if (index < monitoredApps.size - 1) {
                    Divider(
                        modifier = Modifier.padding(start = 48.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "탐지 일시 중지",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "일시 중지 시간을 선택하세요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                PauseDuration.entries.forEach { duration ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPause(duration) },
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = duration.label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
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
