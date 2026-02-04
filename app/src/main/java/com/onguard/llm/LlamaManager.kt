package com.onguard.llm

import android.content.Context
import android.util.Log
import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * llama.cpp(java-llama.cpp) 기반 온디바이스 LLM 매니저.
 *
 * - assets/models/model.gguf  →  filesDir/models/model.gguf 로 복사
 * - Qwen 2.5 ChatML 프롬프트 포맷으로 피싱 여부/위험도 분석
 * - 코루틴(Dispatchers.IO)으로 비동기 추론
 */
class LlamaManager(private val context: Context) {

    companion object {
        private const val TAG = "LlamaManager"

        // assets 하위 경로 (app/src/main/assets/models/model.gguf)
        private const val ASSET_MODEL_PATH = "models/model.gguf"

        // 내부 저장소에 복사될 상대 경로
        private const val LOCAL_MODEL_DIR = "models"
        private const val LOCAL_MODEL_NAME = "model.gguf"

        // 실패 시 반환할 기본 메시지
        private const val FALLBACK_MESSAGE = "분석 실패"
    }

    // llama.cpp Java 바인딩 모델 인스턴스
    private var llamaModel: LlamaModel? = null

    /**
     * 모델 파일을 assets → filesDir 로 복사하고,
     * 해당 경로를 사용해 llama.cpp 모델을 초기화한다.
     */
    suspend fun initModel(): Boolean = withContext(Dispatchers.IO) {
        if (llamaModel != null) {
            Log.d(TAG, "Llama model already initialized.")
            return@withContext true
        }

        try {
            // 1) assets/models/model.gguf → filesDir/models/model.gguf 복사
            val modelsDir = File(context.filesDir, LOCAL_MODEL_DIR).apply {
                if (!exists()) mkdirs()
            }
            val localModelFile = File(modelsDir, LOCAL_MODEL_NAME)

            if (!localModelFile.exists()) {
                Log.d(TAG, "Copying GGUF model from assets to filesDir...")
                context.assets.open(ASSET_MODEL_PATH).use { input ->
                    localModelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Model copied to: ${localModelFile.absolutePath}")
            } else {
                Log.d(TAG, "Using existing local model: ${localModelFile.absolutePath}")
            }

            // 2) java-llama.cpp ModelParameters 설정 후 모델 로드
            val modelParams = ModelParameters()
                .setModel(localModelFile.absolutePath)
                // Android에선 GPU 레이어를 0으로 두고 CPU만 사용하는 것이 안전
                .setGpuLayers(0)

            Log.d(TAG, "Initializing LlamaModel with GGUF: ${localModelFile.absolutePath}")
            val model = LlamaModel(modelParams)

            llamaModel = model
            Log.i(TAG, "LlamaModel initialized successfully.")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy or load GGUF model from assets.", e)
            llamaModel = null
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while initializing LlamaModel.", e)
            llamaModel = null
            false
        }
    }

    /**
     * Qwen 2.5 ChatML 포맷으로 프롬프트를 구성한다.
     *
     * 포맷:
     * <|im_start|>system
     * {시스템명령}
     * <|im_end|>
     * <|im_start|>user
     * {사용자입력}
     * <|im_end|>
     * <|im_start|>assistant
     */
    private fun buildQwenPrompt(userInput: String): String {
        val systemInstruction = """
            너는 금융 사기 탐지 전문가 'On-Guard'야. 
            입력된 텍스트가 피싱인지 분석해서 '위험도'와 '이유'를 짧게 한국어로 답변해.
        """.trimIndent()

        return buildString {
            append("<|im_start|>system\n")
            append(systemInstruction)
            append("\n<|im_end|>\n")
            append("<|im_start|>user\n")
            append(userInput.trim())
            append("\n<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    /**
     * 입력 텍스트에 대해 LLM으로 피싱 여부/위험도를 분석한다.
     *
     * - Dispatchers.IO 에서 실행
     * - 에러 시 "분석 실패" 반환
     */
    suspend fun analyzeText(input: String): String = withContext(Dispatchers.IO) {
        val model = llamaModel
        if (model == null) {
            Log.w(TAG, "analyzeText() called before initModel().")
            return@withContext FALLBACK_MESSAGE
        }

        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return@withContext FALLBACK_MESSAGE
        }

        val prompt = buildQwenPrompt(trimmed)

        return@withContext try {
            Log.d(TAG, "Starting inference. Prompt length=${prompt.length}")

            val inferParams = InferenceParameters(prompt)
                .setTemperature(0.7f)
                .setPenalizeNl(true)
                // Qwen ChatML은 보통 <|im_end|> 등을 stop 토큰으로 사용
                .setStopStrings("<|im_end|>")
                .setMaxTokens(256)

            val sb = StringBuilder()
            for (output in model.generate(inferParams)) {
                sb.append(output.toString())
            }

            val result = sb.toString().trim()
            Log.d(TAG, "LLM result: ${result.take(200)}")

            if (result.isBlank()) FALLBACK_MESSAGE else result
        } catch (e: Exception) {
            Log.e(TAG, "Error during LLM inference.", e)
            FALLBACK_MESSAGE
        }
    }

    /**
     * 모델과 네이티브 리소스를 해제한다.
     */
    fun close() {
        try {
            llamaModel?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error while closing LlamaModel.", e)
        } finally {
            llamaModel = null
        }
    }
}

