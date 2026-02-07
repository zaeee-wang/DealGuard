package com.onguard.device

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 온디바이스 LLM(Gemma) 사용 가능 기기 여부를 판단한다.
 *
 * 모델명(칩셋) 화이트리스트 기반으로 "이 기기면 온디바이스" 허용.
 * - Build.HARDWARE (칩셋 코드명) 기준
 * - Build.VERSION.SDK_INT >= 29 권장
 * - BuildConfig 또는 local.properties 플래그로 강제 on/off 가능 (디버깅용)
 */
interface OnDeviceEligibilityChecker {

    /**
     * 현재 기기가 온디바이스 LLM 사용 대상인지 반환한다.
     * true이면 [OnDeviceScamDetector](Gemma) 사용, false이면 [com.onguard.detector.LLMScamDetector](API) 사용.
     */
    fun isOnDeviceEligible(): Boolean
}

@Singleton
class DefaultOnDeviceEligibilityChecker @Inject constructor() : OnDeviceEligibilityChecker {

    override fun isOnDeviceEligible(): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SDK_FOR_ON_DEVICE) {
            return false
        }
        val hardware = Build.HARDWARE?.lowercase() ?: return false
        return ELIGIBLE_HARDWARE.contains(hardware)
    }

    companion object {
        private const val MIN_SDK_FOR_ON_DEVICE = 29

        /**
         * 온디바이스 허용 칩셋( Build.HARDWARE ) 화이트리스트.
         * Pixel / 삼성 Exynos / 퀄컴 등 NPU 또는 고성능 SoC 탑재 기기.
         * 실기기 테스트 후 단계적으로 추가 권장.
         */
        private val ELIGIBLE_HARDWARE = setOf(
            // Pixel (Tensor)
            "tensor",
            "kalama",
            "tango",
            // 삼성 Exynos
            "exynos2200",
            "exynos2300",
            "exynos2400",
            // 퀄컴
            "taro",
            "diablo",
            "kalama",
            "pineapple",
            "ukee",
            // 미디어텍 (NPU 탑재 SoC 코드명은 기기별 추가)
            "mt6983",
            "mt6895",
            "mt6877",
        )
    }
}
