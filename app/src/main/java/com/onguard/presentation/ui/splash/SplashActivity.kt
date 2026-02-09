package com.onguard.presentation.ui.splash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.onguard.R
import com.onguard.presentation.ui.main.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // WindowCompat을 사용하여 시스템 바 뒤에 그리기
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // 시스템 UI 컨트롤러 설정
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            // 시스템 바 숨기기
            hide(WindowInsetsCompat.Type.systemBars())
            // 스와이프 시 일시적으로만 표시
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        // 상태바와 네비게이션 바를 투명하게
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        // 디스플레이 컷아웃 영역까지 사용
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = 
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        setContent {
            SplashScreen(
                onVideoComplete = {
                    navigateToMain()
                }
            )
        }
    }
    
    @Suppress("DEPRECATION")
    private fun navigateToMain() {
        try {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            // 빠른 페이드 인 애니메이션
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            finish()
        }
    }
}

@Composable
fun SplashScreen(onVideoComplete: () -> Unit) {
    val context = LocalContext.current
    val fadeAlpha = remember { Animatable(0f) }
    var videoDuration by remember { mutableStateOf(0) }
    var videoStarted by remember { mutableStateOf(false) }
    var videoError by remember { mutableStateOf(false) }
    var videoReady by remember { mutableStateOf(false) } // 비디오 준비 상태
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 비디오 뷰 - 전체 화면 채우기
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
                    // 투명 배경 설정
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setZOrderOnTop(false)
                    
                    try {
                        val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.intro}")
                        setVideoURI(videoUri)
                        
                        setOnPreparedListener { mediaPlayer ->
                            try {
                                mediaPlayer.isLooping = false
                                // 크롭 모드로 화면 전체 채우기
                                mediaPlayer.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                                
                                videoDuration = mediaPlayer.duration
                                videoReady = true
                                videoStarted = true
                                start()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                videoError = true
                            }
                        }
                        
                        setOnErrorListener { _, what, extra ->
                            android.util.Log.e("SplashScreen", "Video error: what=$what, extra=$extra")
                            videoError = true
                            true
                        }
                        
                        setOnCompletionListener {
                            // 비디오 완료
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        videoError = true
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // 비디오 준비 전까지 검은색 화면 표시
        if (!videoReady && !videoError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
        
        // 페이드 오버레이
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = fadeAlpha.value))
        )
    }
    
    LaunchedEffect(videoStarted, videoError) {
        if (videoError) {
            delay(500)
            onVideoComplete()
        } else if (videoStarted && videoDuration > 0) {
            // 비디오 재생 대기 (페이드 아웃 시간 제외)
            val waitTime = videoDuration - 200L
            if (waitTime > 0) {
                delay(waitTime)
            }
            
            // 페이드 아웃 (빠르게)
            fadeAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 200)
            )
            
            // 페이드 아웃 완료 후 메인 화면으로 이동
            onVideoComplete()
        } else {
            // 타임아웃
            delay(3000)
            if (!videoStarted && !videoError) {
                onVideoComplete()
            }
        }
    }
}
