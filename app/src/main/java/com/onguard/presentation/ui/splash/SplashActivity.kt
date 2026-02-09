package com.onguard.presentation.ui.splash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.onguard.R
import com.onguard.presentation.ui.main.MainActivity
import kotlinx.coroutines.delay

class SplashActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 전체 화면 설정
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        
        // 시스템 UI 숨기기
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        setContent {
            SplashScreen(
                onVideoComplete = {
                    navigateToMain()
                }
            )
        }
    }
    
    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

@Composable
fun SplashScreen(onVideoComplete: () -> Unit) {
    val context = LocalContext.current
    val fadeAlpha = remember { Animatable(0f) } // 시작은 검은 화면
    var videoDuration by remember { mutableStateOf(0) }
    var videoStarted by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 비디오 뷰 - 완전히 꽉 차게
        AndroidView(
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
                    val videoView = VideoView(ctx).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.view.Gravity.CENTER
                        )
                        
                        val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.intro}")
                        setVideoURI(videoUri)
                        
                        setOnPreparedListener { mediaPlayer ->
                            mediaPlayer.isLooping = false
                            mediaPlayer.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                            
                            // 화면 크기
                            val displayMetrics = context.resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels.toFloat()
                            val screenHeight = displayMetrics.heightPixels.toFloat()
                            
                            // 비디오 크기
                            val videoWidth = mediaPlayer.videoWidth.toFloat()
                            val videoHeight = mediaPlayer.videoHeight.toFloat()
                            
                            // 화면을 완전히 채우기 위한 스케일 계산 (큰 쪽 기준)
                            val scaleX = screenWidth / videoWidth
                            val scaleY = screenHeight / videoHeight
                            val scale = maxOf(scaleX, scaleY)
                            
                            // 스케일 적용된 크기
                            val scaledWidth = (videoWidth * scale).toInt()
                            val scaledHeight = (videoHeight * scale).toInt()
                            
                            // 레이아웃 파라미터 재설정
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                scaledWidth,
                                scaledHeight,
                                android.view.Gravity.CENTER
                            )
                            
                            videoDuration = mediaPlayer.duration
                            videoStarted = true
                            start()
                        }
                        
                        setOnCompletionListener {
                            // 비디오 완료 처리는 LaunchedEffect에서 수행
                        }
                    }
                    
                    addView(videoView)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // 페이드 오버레이 (검은색)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = fadeAlpha.value))
        )
    }
    
    LaunchedEffect(videoStarted) {
        if (videoStarted && videoDuration > 0) {
            // 페이드인 (검은색에서 비디오로)
            fadeAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 800)
            )
            
            // 비디오 재생 시간 대기 (페이드아웃 시간 제외)
            val waitTime = videoDuration - 800L
            if (waitTime > 0) {
                delay(waitTime)
            }
            
            // 페이드아웃 (비디오에서 검은색으로)
            fadeAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 800)
            )
            
            // 메인 화면으로 이동
            onVideoComplete()
        }
    }
}
