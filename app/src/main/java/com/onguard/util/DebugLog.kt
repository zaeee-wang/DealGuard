package com.onguard.util

import android.util.Log
import com.onguard.BuildConfig

/**
 * 디버깅용 공통 로깅 유틸.
 *
 * - debugLog: DEBUG 빌드에서만 Log.d 출력
 * - maskText: 앞 maxLen 글자만 남기고 나머지는 마스킹
 */
object DebugLog {

    fun maskText(text: String, maxLen: Int = 30): String {
        if (text.length <= maxLen) return text
        return text.substring(0, maxLen) + "..."
    }

    inline fun debugLog(tag: String, crossinline message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message())
        }
    }

    inline fun infoLog(tag: String, crossinline message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message())
        }
    }

    inline fun warnLog(tag: String, crossinline message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, message())
        }
    }
}