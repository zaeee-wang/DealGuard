package com.onguard.presentation.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // Import all icons for mapping
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.onguard.R
import com.onguard.presentation.theme.*

// ==== 6. 최근 알림 리스트 아이템 ====
@Composable
fun RecentAlertItem(
    alert: com.onguard.domain.model.ScamAlert,
    onDelete: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val riskColor = when {
        alert.confidence >= 0.7f -> RiskHigh
        alert.confidence >= 0.4f -> RiskMedium
        else -> RiskLow
    }
    
    val riskLevelText = when {
        alert.confidence >= 0.7f -> "고위험"
        alert.confidence >= 0.4f -> "중위험"
        else -> "저위험"
    }

    val riskTitle = "${(alert.confidence * 100).toInt()}% $riskLevelText 탐지"
    
    // 앱 이름 매핑 (SettingsScreen.kt의 monitoredApps 참조)
    val appNameMap = mapOf(
        "com.kakao.talk" to "카카오톡",
        "org.telegram.messenger" to "텔레그램",
        "jp.naver.line.android" to "라인",
        "com.facebook.orca" to "페이스북 메신저",
        "com.google.android.apps.messaging" to "Google 메시지",
        "com.samsung.android.messaging" to "삼성 메시지",
        "com.instagram.android" to "인스타그램 DM",
        "com.whatsapp" to "왓츠앱",
        "com.discord" to "디스코드",
        "kr.co.daangn" to "당근마켓"
    )
    
    // 앱 아이콘 매핑 (Drawable Resource ID, _red 버전 사용)
    val appIconMap = mapOf(
        "com.kakao.talk" to R.drawable.ic_massagebox_red,
        "org.telegram.messenger" to R.drawable.ic_massagebox_red,
        "jp.naver.line.android" to R.drawable.ic_massagebox_red,
        "com.facebook.orca" to R.drawable.ic_massagebox_red,
        "com.google.android.apps.messaging" to R.drawable.ic_massagebox_red,
        "com.samsung.android.messaging" to R.drawable.ic_massagebox_red,
        "com.instagram.android" to R.drawable.ic_massagebox_red,
        "com.whatsapp" to R.drawable.ic_call_red,
        "com.discord" to R.drawable.ic_call_red,
        "kr.co.daangn" to R.drawable.ic_cart_red
    )
    
    val appName = appNameMap[alert.sourceApp] ?: alert.sourceApp.substringAfterLast('.')
    val appIconRes = appIconMap[alert.sourceApp] ?: R.drawable.ic_massagebox_red

    // 타임스탬프 포맷팅
    val dateFormat = java.text.SimpleDateFormat("yyyy.MM.dd a h:mm", java.util.Locale.getDefault())
    val dateStr = dateFormat.format(alert.timestamp)

    // 메시지 cleaning: "(위험도: ...)" 패턴 제거
    val cleanMessage = alert.message.replace(Regex("\\(위험도:.*\\)"), "").trim()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)), // 연한 회색 배경
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
        ) {
            // 상단: 아이콘 + 앱이름 + 날짜
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = appIconRes),
                    contentDescription = null,
                    tint = Color.Unspecified, // 아이콘 원래 색상 사용
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = appName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 중간: 위험도 타이틀
            Text(
                text = riskTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = riskColor,
                fontSize = 18.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 내용
            Text(
                text = cleanMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 하단: 삭제 버튼 (중앙 정렬)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "알림 삭제",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier
                        .clickable { onDelete(alert.id) }
                        .padding(8.dp)
                )
            }
        }
    }
}
