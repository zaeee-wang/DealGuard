package com.onguard.di

import android.content.Context
import com.onguard.domain.usecase.PhoneAccountValidator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 앱 전역 DI 모듈.
 *
 * NOTE: LlamaManager는 Gemini API로 전환되어 더 이상 제공하지 않음.
 * 오프라인 LLM 지원이 필요한 경우 llm/ 패키지의 코드를 다시 활성화.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun providePhoneAccountValidator(): PhoneAccountValidator = PhoneAccountValidator.Default
}
