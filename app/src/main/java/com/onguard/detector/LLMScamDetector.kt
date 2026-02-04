package com.onguard.detector

import android.content.Context
import android.util.Log
import com.onguard.domain.model.DetectionMethod
import com.onguard.domain.model.ScamAnalysis
import com.onguard.domain.model.ScamType
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM 기반 스캠 탐지기.
 *
 * ONNX Runtime와 SmolLM2 계열 ONNX 모델을 사용하여
 * 채팅 메시지를 분석하고, 스캠 여부·신뢰도·경고 메시지·의심 문구를 생성한다.
 * 모델 파일이 없거나 초기화 실패 시 [analyze]는 null을 반환하며, Rule-based만 사용된다.
 *
 * @param context [ApplicationContext] 앱 컨텍스트 (assets/filesDir 접근용)
 */
@Singleton
class LLMScamDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LLMScamDetector"
        /** assets 내 ONNX 모델 상대 경로 (경량 LLM) */
        private const val MODEL_PATH = "models/model_q4f16.onnx"
        /** LLM 입력 길이 상한 (문자 수 기준, 토큰/메모리 절감) */
        private const val MAX_INPUT_CHARS = 1500
    }

    /**
     * Rule 기반·URL 분석 결과 등, LLM에 전달할 추가 컨텍스트.
     *
     * DB(KISA 피싱 URL 등)를 이미 활용한 UrlAnalyzer/KeywordMatcher의 결과를
     * LLM 프롬프트에 함께 담기 위해 사용한다.
     */
    data class LlmContext(
        /** 규칙 기반 신뢰도 (0.0~1.0) */
        val ruleConfidence: Float? = null,
        /** 규칙 기반 탐지 사유 목록 */
        val ruleReasons: List<String> = emptyList(),
        /** 규칙 기반에서 탐지된 키워드 목록 */
        val detectedKeywords: List<String> = emptyList(),
        /** 텍스트에서 추출된 전체 URL 목록 */
        val urls: List<String> = emptyList(),
        /** UrlAnalyzer 기준으로 위험하다고 간주된 URL 목록 */
        val suspiciousUrls: List<String> = emptyList(),
        /** URL 관련 위험 사유 (KISA DB 매치, 무료 도메인 등) */
        val urlReasons: List<String> = emptyList()
    )

    // ONNX Runtime 환경 및 세션
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val gson = Gson()
    private var isInitialized = false
    /** 초기화를 한 번 시도했고 실패한 경우 true (재시도 방지) */
    private var initializationAttempted = false

    /**
     * LLM 모델을 초기화한다.
     *
     * assets에서 [MODEL_PATH]로 모델을 복사한 뒤 ONNX Runtime으로 로드한다.
     * 이미 초기화된 경우 즉시 true를 반환한다.
     *
     * @return 성공 시 true, 모델 없음/예외 시 false
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== LLM Initialization Started ===")
        
        if (isInitialized) {
            Log.d(TAG, "LLM already initialized, skipping")
            return@withContext true
        }
        try {
            val modelFile = File(context.filesDir, MODEL_PATH)
            Log.d(TAG, "Target model file path: ${modelFile.absolutePath}")
            Log.d(TAG, "Model file parent exists: ${modelFile.parentFile?.exists()}")

            // assets에서 모델 파일 확인 및 복사
            val assetPath = MODEL_PATH
            Log.d(TAG, "Checking assets for model: $assetPath")
            
            try {
                // assets에 파일이 있는지 확인
                val assetExists = try {
                    context.assets.open(assetPath).use { true }
                } catch (e: Exception) {
                    Log.e(TAG, "Model file not found in assets: $assetPath", e)
                    false
                }
                
                if (!assetExists) {
                    Log.e(TAG, "ONNX model file not found in assets: $assetPath")
                    Log.w(TAG, "LLM detection will be disabled. Please add ONNX model to assets/models/")
                    return@withContext false
                }
                
                // 기존 파일이 있으면 검증
                if (modelFile.exists()) {
                    val fileSize = modelFile.length()
                    val fileReadable = modelFile.canRead()
                    Log.d(TAG, "Existing ONNX model file found: ${modelFile.absolutePath}")
                    Log.d(TAG, "  - Size: $fileSize bytes (${fileSize / 1_000_000}MB)")
                    Log.d(TAG, "  - Readable: $fileReadable")
                    
                    // 파일 크기가 비정상적으로 작으면 삭제 후 재복사 (경량 모델 기준 50MB 미만은 이상)
                    if (fileSize < 50_000_000L) {
                        Log.w(TAG, "ONNX model file seems corrupted (too small: ${fileSize} bytes), deleting and re-copying")
                        val deleted = modelFile.delete()
                        Log.d(TAG, "  - Deleted: $deleted")
                    } else {
                        Log.d(TAG, "Existing ONNX model file size is valid, skipping copy")
                    }
                }
                
                // assets에서 복사 (없거나 손상된 경우)
                if (!modelFile.exists()) {
                    Log.d(TAG, "Copying model from assets to filesDir...")
                    context.assets.open(assetPath).use { input ->
                        val assetSize = input.available()
                        Log.d(TAG, "  - Asset size: $assetSize bytes")
                        
                        modelFile.parentFile?.mkdirs()
                        val parentCreated = modelFile.parentFile?.exists() ?: false
                        Log.d(TAG, "  - Parent directory created: $parentCreated")
                        
                        modelFile.outputStream().use { output ->
                            var bytesCopied = 0L
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesCopied += read
                                if (bytesCopied % (10 * 1024 * 1024) == 0L) { // 10MB마다 로그
                                    Log.d(TAG, "  - Copied: ${bytesCopied / 1_000_000}MB")
                                }
                            }
                            Log.d(TAG, "  - Total copied: ${bytesCopied / 1_000_000}MB")
                        }
                    }
                    
                    val copiedSize = modelFile.length()
                    val copiedReadable = modelFile.canRead()
                    Log.d(TAG, "ONNX model copied successfully")
                    Log.d(TAG, "  - Final size: $copiedSize bytes (${copiedSize / 1_000_000}MB)")
                    Log.d(TAG, "  - Readable: $copiedReadable")
                    
                    // 복사 후 크기 검증
                    if (copiedSize < 50_000_000L) {
                        Log.e(TAG, "Copied ONNX model file is too small ($copiedSize bytes), likely corrupted")
                        modelFile.delete()
                        return@withContext false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error copying ONNX model from assets: $assetPath", e)
                Log.e(TAG, "Exception type: ${e.javaClass.name}")
                Log.e(TAG, "Exception message: ${e.message}")
                e.printStackTrace()
                Log.w(TAG, "LLM detection will be disabled. Please add ONNX model to assets/models/")
                return@withContext false
            }

            // ONNX Runtime 세션 생성
            Log.d(TAG, "=== Creating ONNX Runtime Session ===")
            Log.d(TAG, "  - Model path: ${modelFile.absolutePath}")
            Log.d(TAG, "  - Model file exists: ${modelFile.exists()}")
            Log.d(TAG, "  - Model file readable: ${modelFile.canRead()}")
            Log.d(TAG, "  - Model file size: ${modelFile.length()} bytes")
            
            // LLM 로드 직전 메모리 확보 (저사양 기기 대응)
            System.gc()

            try {
                val env = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions()
                val session = env.createSession(modelFile.absolutePath, sessionOptions)

                // 입출력 메타데이터 로깅 (모델 구조 파악용)
                Log.d(TAG, "=== ONNX Model IO Info ===")
                session.inputNames.forEach { name ->
                    val info: NodeInfo? = session.inputInfo[name]
                    val tensorInfo = info?.info as? TensorInfo
                    Log.d(
                        TAG,
                        "  - Input: $name, type=${tensorInfo?.type}, shape=${tensorInfo?.shape?.joinToString()}"
                    )
                }
                session.outputNames.forEach { name ->
                    val info: NodeInfo? = session.outputInfo[name]
                    val tensorInfo = info?.info as? TensorInfo
                    Log.d(
                        TAG,
                        "  - Output: $name, type=${tensorInfo?.type}, shape=${tensorInfo?.shape?.joinToString()}"
                    )
                }

                ortEnv = env
                ortSession = session
                isInitialized = true
                initializationAttempted = true

                Log.i(TAG, "=== ONNX LLM Initialized Successfully ===")
                true
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "=== Out of Memory during ONNX LLM initialization ===", e)
                val runtime = Runtime.getRuntime()
                Log.e(TAG, "  - Total memory: ${runtime.totalMemory() / 1_000_000}MB")
                Log.e(TAG, "  - Free memory: ${runtime.freeMemory() / 1_000_000}MB")
                Log.e(TAG, "  - Max memory: ${runtime.maxMemory() / 1_000_000}MB")
                e.printStackTrace()
                false
            } catch (e: Exception) {
                Log.e(TAG, "=== Exception during ONNX LLM initialization ===", e)
                Log.e(TAG, "  - Exception type: ${e.javaClass.name}")
                Log.e(TAG, "  - Exception message: ${e.message}")
                e.printStackTrace()
                false
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "=== Out of Memory Error (Outer) ===", e)
            Log.e(TAG, "Not enough memory to initialize ONNX LLM")
            val runtime = Runtime.getRuntime()
            Log.e(TAG, "  - Total memory: ${runtime.totalMemory() / 1_000_000}MB")
            Log.e(TAG, "  - Free memory: ${runtime.freeMemory() / 1_000_000}MB")
            Log.e(TAG, "  - Max memory: ${runtime.maxMemory() / 1_000_000}MB")
            e.printStackTrace()
            false
        } catch (e: Exception) {
            Log.e(TAG, "=== General Exception (Outer Catch) ===", e)
            Log.e(TAG, "Failed to initialize ONNX LLM")
            Log.e(TAG, "  - Exception type: ${e.javaClass.name}")
            Log.e(TAG, "  - Exception message: ${e.message}")
            Log.e(TAG, "  - Exception cause: ${e.cause}")
            e.printStackTrace()
            false
        } catch (e: Throwable) {
            Log.e(TAG, "=== Fatal Error ===", e)
            Log.e(TAG, "Fatal error during LLM initialization")
            Log.e(TAG, "  - Error type: ${e.javaClass.name}")
            Log.e(TAG, "  - Error message: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * LLM 모델 사용 가능 여부를 반환한다.
     *
     * @return 초기화 완료 및 인스턴스 존재 시 true
     */
    fun isAvailable(): Boolean = isInitialized && ortSession != null

    /**
     * 주어진 텍스트를 LLM으로 분석하여 스캠 여부와 상세 결과를 반환한다.
     *
     * @param text 분석할 채팅 메시지
     * @param context Rule/URL 기반 1차 분석 결과 등 추가 컨텍스트 (선택)
     * @return [ScamAnalysis] 분석 결과. 모델 미사용/빈 응답/파싱 실패 시 null
     */
    suspend fun analyze(text: String, context: LlmContext? = null): ScamAnalysis? = withContext(Dispatchers.Default) {
        val input = if (text.length > MAX_INPUT_CHARS) {
            Log.d(TAG, "Input truncated for LLM: ${text.length} -> $MAX_INPUT_CHARS chars")
            text.take(MAX_INPUT_CHARS)
        } else text
        Log.d(TAG, "=== LLM Analysis Started ===")
        Log.d(TAG, "  - Text length: ${input.length} chars")
        Log.d(TAG, "  - Context provided: ${context != null}")
        
        if (!isAvailable()) {
            Log.w(TAG, "LLM not available (isInitialized=$isInitialized, ortSession=${ortSession != null}), skipping analysis")
            return@withContext null
        }

        try {
            Log.d(TAG, "Building prompt...")
            val prompt = buildPrompt(input, context)
            Log.d(TAG, "  - Prompt length: ${prompt.length} chars")
            Log.v(TAG, "  - Prompt preview: ${prompt.take(200)}...")

            // 1단계: 토크나이저를 통해 프롬프트를 토큰 ID로 변환
            val tokenizer = SmolLmTokenizer(context)
            val promptIds = tokenizer.encode(prompt, addBosEos = true)
            Log.d(TAG, "Encoded prompt to token ids (SmolLM2)")
            Log.d(TAG, "  - Token count: ${promptIds.size}")
            Log.d(TAG, "  - Ids preview: ${promptIds.take(16)}")

            // 2단계: ONNX 모델에 한 스텝(inference 1회) 넣어서 다음 토큰 1개만 생성
            //  - input_ids / attention_mask / position_ids + past_key_values.*(zero 초기값)
            //  - logits에서 마지막 토큰 위치의 argmax를 nextTokenId로 사용
            val env = ortEnv ?: OrtEnvironment.getEnvironment()
            val session = ortSession
            if (session == null) {
                Log.e(TAG, "ONNX session is null despite isAvailable() == true")
                return@withContext null
            }

            val seqLen = promptIds.size
            if (seqLen == 0) {
                Log.w(TAG, "Prompt token sequence is empty, skipping LLM inference")
                return@withContext null
            }

            // [1, seqLen] 형태의 Long 텐서들 생성
            val inputIds2d = arrayOf(promptIds)
            val attentionMask1d = LongArray(seqLen) { 1L }
            val attentionMask2d = arrayOf(attentionMask1d)
            val positionIds1d = LongArray(seqLen) { idx -> idx.toLong() }
            val positionIds2d = arrayOf(positionIds1d)

            val inputs = mutableMapOf<String, OnnxTensor>()

            try {
                inputs["input_ids"] = OnnxTensor.createTensor(env, inputIds2d)
                inputs["attention_mask"] = OnnxTensor.createTensor(env, attentionMask2d)
                inputs["position_ids"] = OnnxTensor.createTensor(env, positionIds2d)

                // past_key_values.* : 첫 스텝에서는 0으로 채운 작은 캐시를 사용
                // shape: [1, 3, pastSeqLen, 64]  (pastSeqLen은 1로 설정)
                val numLayers = 30
                val numHeads = 3
                val pastSeqLen = 1
                val headDim = 64
                val pastShape = longArrayOf(1L, numHeads.toLong(), pastSeqLen.toLong(), headDim.toLong())
                val pastSize = pastShape.fold(1L) { acc, v -> acc * v }.toInt()

                for (layer in 0 until numLayers) {
                    val keyName = "past_key_values.$layer.key"
                    val valueName = "past_key_values.$layer.value"

                    // FLOAT16 텐서는 ShortArray로 생성 (0으로 초기화)
                    val zeroKey = ShortArray(pastSize) { 0 }
                    val zeroValue = ShortArray(pastSize) { 0 }

                    inputs[keyName] = OnnxTensor.createTensor(env, zeroKey, pastShape)
                    inputs[valueName] = OnnxTensor.createTensor(env, zeroValue, pastShape)
                }

                Log.d(TAG, "Running ONNX session for 1-step generation...")

                val nextTokenId: Int
                val nextTokenText: String

                session.run(inputs).use { results ->
                    val logitsTensor = results.get("logits") as? OnnxTensor
                    if (logitsTensor == null) {
                        Log.e(TAG, "ONNX output 'logits' is null or missing")
                        return@withContext null
                    }

                    @Suppress("UNCHECKED_CAST")
                    val logitsArray = logitsTensor.value as Array<Array<FloatArray>>
                    if (logitsArray.isEmpty() || logitsArray[0].isEmpty()) {
                        Log.e(TAG, "ONNX logits tensor is empty")
                        return@withContext null
                    }

                    val lastLogits = logitsArray[0][logitsArray[0].size - 1]
                    if (lastLogits.isEmpty()) {
                        Log.e(TAG, "Last logits array is empty")
                        return@withContext null
                    }

                    // greedy argmax
                    var maxIdx = 0
                    var maxVal = lastLogits[0]
                    for (i in 1 until lastLogits.size) {
                        val v = lastLogits[i]
                        if (v > maxVal) {
                            maxVal = v
                            maxIdx = i
                        }
                    }
                    nextTokenId = maxIdx
                    nextTokenText = tokenizer.decode(longArrayOf(nextTokenId.toLong()))

                    Log.d(TAG, "ONNX 1-step generation finished")
                    Log.d(TAG, "  - nextTokenId: $nextTokenId")
                    Log.d(TAG, "  - nextTokenText: \"$nextTokenText\"")
                }

                Log.d(TAG, "Generating LLM response (SIMULATED JSON, USING ONNX TOKEN)...")

                // 현재는 개발 단계이므로, Rule/URL 컨텍스트 + ONNX에서 선택한 토큰 정보를
                // 합쳐서 LLM 응답 JSON을 임시로 생성하여 전체 파이프라인(알림 표시)을 검증한다.

                val baseConfidence = (context?.ruleConfidence ?: 0.5f).coerceIn(0f, 1f)
                val isScam = baseConfidence > 0.5f
                val scamType = when {
                    context?.ruleReasons?.any { it.contains("투자") || it.contains("수익") || it.contains("코인") || it.contains("주식") } == true ->
                        "투자사기"
                    context?.ruleReasons?.any { it.contains("중고") || it.contains("입금") || it.contains("선결제") || it.contains("거래") } == true ->
                        "중고거래사기"
                    context?.urls?.isNotEmpty() == true || context?.suspiciousUrls?.isNotEmpty() == true ->
                        "피싱"
                    else -> if (isScam) "사기" else "정상"
                }

                val reasons = buildList {
                    addAll(context?.ruleReasons?.take(5) ?: emptyList())
                    if (context?.suspiciousUrls?.isNotEmpty() == true) {
                        add("의심 URL 포함: ${context.suspiciousUrls.take(3).joinToString()}")
                    }
                    if (nextTokenText.isNotBlank()) {
                        add("ONNX LLM이 생성한 토큰: \"$nextTokenText\" (id=$nextTokenId)")
                    } else {
                        add("ONNX LLM이 선택한 토큰 id: $nextTokenId")
                    }
                }

                val suspiciousParts = buildList {
                    addAll(context?.detectedKeywords?.take(3) ?: emptyList())
                    if (context?.suspiciousUrls?.isNotEmpty() == true) {
                        addAll(context.suspiciousUrls.take(2))
                    }
                    if (nextTokenText.isNotBlank()) {
                        add(nextTokenText)
                    }
                }

                val tokenSnippet = if (nextTokenText.isNotBlank()) {
                    " (모델이 바로 다음으로 \"${nextTokenText}\" 토큰을 선택함)"
                } else {
                    " (모델이 다음 토큰 id=$nextTokenId 를 선택함)"
                }

                val warningMessage = if (isScam) {
                    "이 대화는 ${scamType} 유형으로 강하게 의심됩니다 (ONNX LLM 1스텝 결과). 상대방의 요청을 바로 따르지 말고 한 번 더 확인하세요.$tokenSnippet"
                } else {
                    "현재까지는 뚜렷한 사기 패턴이 감지되지 않았습니다 (ONNX LLM 1스텝 결과). 그래도 링크 클릭이나 송금 전에는 항상 주의하세요.$tokenSnippet"
                }

                val simulatedJson = """
                {
                  "isScam": $isScam,
                  "confidence": $baseConfidence,
                  "scamType": "$scamType",
                  "warningMessage": "$warningMessage",
                  "reasons": ${gson.toJson(reasons)},
                  "suspiciousParts": ${gson.toJson(suspiciousParts)}
                }
                """.trimIndent()

                Log.d(TAG, "Simulated LLM JSON (with ONNX token): $simulatedJson")

                val result = parseResponse(simulatedJson)

                if (result == null) {
                    Log.w(TAG, "Failed to parse simulated LLM response")
                } else {
                    Log.d(TAG, "=== LLM Analysis Success (ONNX 1-step + SIMULATED JSON) ===")
                    Log.d(TAG, "  - isScam: ${result.isScam}")
                    Log.d(TAG, "  - confidence: ${result.confidence}")
                    Log.d(TAG, "  - scamType: ${result.scamType}")
                    Log.d(TAG, "  - reasons count: ${result.reasons.size}")
                }

                result
            } finally {
                // 입력 텐서 리소스 정리
                inputs.values.forEach {
                    try {
                        it.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error while closing input tensor", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "=== Error during LLM analysis ===", e)
            Log.e(TAG, "  - Exception type: ${e.javaClass.name}")
            Log.e(TAG, "  - Exception message: ${e.message}")
            Log.e(TAG, "  - Exception cause: ${e.cause}")
            Log.e(TAG, "  - Text length: ${text.length} chars")
            Log.e(TAG, "  - LLM available: ${isAvailable()}")
            e.printStackTrace()
            null
        } catch (e: Throwable) {
            Log.e(TAG, "=== Fatal error during LLM analysis ===", e)
            Log.e(TAG, "  - Error type: ${e.javaClass.name}")
            Log.e(TAG, "  - Error message: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * 스캠 탐지용 시스템 프롬프트와 사용자 메시지를 조합한 프롬프트를 생성한다.
     *
     * @param text 분석 대상 채팅 메시지
     * @param context Rule 기반·URL 분석 등 추가 컨텍스트
     * @return JSON만 출력하도록 지시한 프롬프트 문자열
     */
    private fun buildPrompt(text: String, context: LlmContext?): String {
        val maxListItems = 8
        val contextBlock = buildString {
            if (context == null) return@buildString

            if (context.ruleConfidence != null || context.ruleReasons.isNotEmpty() || context.detectedKeywords.isNotEmpty()) {
                appendLine("[Rule-based 1차 분석 요약]")
                context.ruleConfidence?.let {
                    appendLine("- rule_confidence: $it")
                }
                if (context.detectedKeywords.isNotEmpty()) {
                    val kw = context.detectedKeywords.take(maxListItems)
                    appendLine("- detected_keywords: ${kw.joinToString()}")
                }
                if (context.ruleReasons.isNotEmpty()) {
                    appendLine("- rule_reasons:")
                    context.ruleReasons.take(maxListItems).forEach { reason ->
                        appendLine("  - $reason")
                    }
                }
                appendLine()
            }

            if (context.urls.isNotEmpty() || context.suspiciousUrls.isNotEmpty() || context.urlReasons.isNotEmpty()) {
                appendLine("[URL/DB 기반 분석 요약]")
                if (context.urls.isNotEmpty()) {
                    val urls = context.urls.take(maxListItems)
                    appendLine("- urls: ${urls.joinToString()}")
                }
                if (context.suspiciousUrls.isNotEmpty()) {
                    val sus = context.suspiciousUrls.take(maxListItems)
                    appendLine("- suspicious_urls: ${sus.joinToString()}")
                }
                if (context.urlReasons.isNotEmpty()) {
                    appendLine("- url_reasons:")
                    context.urlReasons.take(maxListItems).forEach { reason ->
                        appendLine("  - $reason")
                    }
                }
            }
        }.trimEnd()

        return """
당신은 사기 탐지 전문가입니다. 다음 메시지를 분석하고 JSON 형식으로만 응답하세요.

[배경 정보]
- 먼저 규칙 기반 분석기와 URL 분석기가 1차로 메시지를 평가했습니다.
- 아래 [Rule-based 1차 분석 요약]과 [URL/DB 기반 분석 요약]을 참고하여 최종 판단을 내려주세요.
- KISA 피싱사이트 DB 등록 URL 등은 매우 강한 스캠 신호입니다.

[Rule-based 1차 분석 요약과 URL/DB 기반 분석 요약은 없을 수도 있습니다]
${if (contextBlock.isNotBlank()) contextBlock else "(제공된 사전 분석 정보 없음)"}

[탐지 대상]
1. 투자 사기: 고수익 보장, 원금 보장, 긴급 투자 권유, 비공개 정보 제공, 코인/주식 리딩방
2. 중고거래 사기: 선입금 요구, 안전결제 우회, 급매 압박, 타 플랫폼 유도, 직거래 회피, 허위 매물

[메시지]
$text

[응답 형식 - JSON만 출력하세요]
{
  "isScam": true 또는 false,
  "confidence": 0.0부터 1.0 사이 숫자,
  "scamType": "투자사기" 또는 "중고거래사기" 또는 "피싱" 또는 "정상",
  "warningMessage": "사용자에게 보여줄 경고 메시지 (한국어, 2문장 이내)",
  "reasons": ["위험 요소 1", "위험 요소 2"],
  "suspiciousParts": ["의심되는 문구 인용"]
}
""".trimIndent()
    }

    /**
     * LLM 응답 문자열에서 JSON 블록을 추출해 [ScamAnalysis]로 파싱한다.
     *
     * @param response LLM 원시 응답 (앞뒤 일반 텍스트 허용)
     * @return 파싱 성공 시 [ScamAnalysis], 실패 시 null
     */
    private fun parseResponse(response: String): ScamAnalysis? {
        Log.d(TAG, "Parsing LLM response...")
        return try {
            // JSON 부분만 추출 (LLM이 추가 텍스트를 생성할 수 있음)
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}')

            Log.d(TAG, "  - JSON start index: $jsonStart")
            Log.d(TAG, "  - JSON end index: $jsonEnd")

            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
                Log.w(TAG, "No valid JSON found in response")
                Log.w(TAG, "  - Response length: ${response.length}")
                Log.w(TAG, "  - Response preview: ${response.take(500)}")
                return null
            }

            val jsonString = response.substring(jsonStart, jsonEnd + 1)
            Log.d(TAG, "  - Extracted JSON length: ${jsonString.length}")
            Log.v(TAG, "  - JSON content: $jsonString")
            
            val llmResult = gson.fromJson(jsonString, LLMResponse::class.java)
            Log.d(TAG, "  - Parsed LLM result: isScam=${llmResult.isScam}, confidence=${llmResult.confidence}")

            ScamAnalysis(
                isScam = llmResult.isScam,
                confidence = llmResult.confidence.coerceIn(0f, 1f),
                reasons = llmResult.reasons.orEmpty(),
                detectedKeywords = emptyList(),
                detectionMethod = DetectionMethod.LLM,
                scamType = parseScamType(llmResult.scamType),
                warningMessage = llmResult.warningMessage,
                suspiciousParts = llmResult.suspiciousParts.orEmpty()
            )
        } catch (e: Exception) {
            Log.e(TAG, "=== Failed to parse LLM response ===", e)
            Log.e(TAG, "  - Exception type: ${e.javaClass.name}")
            Log.e(TAG, "  - Exception message: ${e.message}")
            Log.e(TAG, "  - Response length: ${response.length}")
            Log.e(TAG, "  - Response preview: ${response.take(500)}")
            e.printStackTrace()
            null
        }
    }

    /**
     * LLM이 반환한 스캠 유형 문자열을 [ScamType] enum으로 변환한다.
     *
     * @param typeString "투자사기", "중고거래사기", "정상" 등 한글 문자열
     * @return 대응되는 [ScamType]
     */
    private fun parseScamType(typeString: String): ScamType {
        return when {
            typeString.contains("투자") -> ScamType.INVESTMENT
            typeString.contains("중고") || typeString.contains("거래") -> ScamType.USED_TRADE
            typeString.contains("피싱") -> ScamType.PHISHING
            typeString.contains("사칭") -> ScamType.IMPERSONATION
            typeString.contains("로맨스") -> ScamType.ROMANCE
            typeString.contains("대출") -> ScamType.LOAN
            typeString.contains("정상") -> ScamType.SAFE
            else -> ScamType.UNKNOWN
        }
    }

    /**
     * LLM 인스턴스를 해제하고 리소스를 반환한다.
     *
     * 앱 종료 또는 탐지기 교체 시 호출 권장.
     */
    fun close() {
        try {
            ortSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error while closing ONNX session", e)
        }
        ortSession = null
        // OrtEnvironment는 전역 singleton이라 명시적으로 닫지 않고 null만 클리어
        ortEnv = null
        isInitialized = false
        initializationAttempted = false
    }

    /**
     * LLM 응답 JSON 파싱용 내부 DTO.
     *
     * @property isScam 스캠 여부
     * @property confidence 신뢰도 0~1
     * @property scamType 한글 유형 문자열
     * @property warningMessage 사용자용 경고 문구
     * @property reasons 위험 요소 목록
     * @property suspiciousParts 의심 문구 인용 목록
     */
    private data class LLMResponse(
        @SerializedName("isScam")
        val isScam: Boolean = false,

        @SerializedName("confidence")
        val confidence: Float = 0f,

        @SerializedName("scamType")
        val scamType: String = "정상",

        @SerializedName("warningMessage")
        val warningMessage: String = "",

        @SerializedName("reasons")
        val reasons: List<String>? = null,

        @SerializedName("suspiciousParts")
        val suspiciousParts: List<String>? = null
    )
}
