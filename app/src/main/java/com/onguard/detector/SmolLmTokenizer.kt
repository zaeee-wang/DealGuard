package com.onguard.detector

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SmolLM2용 간단 토크나이저.
 *
 * - Hugging Face 토크나이저/어휘 파일을 assets에서 읽어온다.
 * - 현재는 정확한 BPE 구현 대신, vocab 기반의 매우 단순한 인코더/디코더만 제공한다.
 *   (공백 기준 토큰화 + vocab 매칭, 없으면 unk 토큰으로 대체)
 *
 * 이 클래스의 목적은:
 * - ONNX LLM이 실제 토큰 ID를 입력으로 받을 수 있게 하는 MVP 단계
 * - 전체 파이프라인(텍스트 → 토큰 → LLM → 토큰 → 텍스트)을 점진적으로 완성하기 위한 기반
 */
@Singleton
class SmolLmTokenizer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "SmolLmTokenizer"

        private const val TOKENIZER_DIR = "tokenizers/smollm2"
        private const val VOCAB_FILE = "$TOKENIZER_DIR/vocab.json"
        private const val TOKENIZER_CONFIG_FILE = "$TOKENIZER_DIR/tokenizer_config.json"
        private const val SPECIAL_TOKENS_FILE = "$TOKENIZER_DIR/special_tokens_map.json"
    }

    private val gson = Gson()

    /** token → id 매핑 */
    private val vocab: Map<String, Int>

    /** id → token 매핑 (길이 = vocab.size) */
    private val idToToken: Array<String>

    /** special token IDs */
    val unkTokenId: Int
    val bosTokenId: Int?
    val eosTokenId: Int?

    init {
        // 1) vocab.json 로드
        val vocabType = object : TypeToken<Map<String, Int>>() {}.type
        vocab = context.assets.open(VOCAB_FILE).use { input ->
            InputStreamReader(input).use { reader ->
                gson.fromJson<Map<String, Int>>(reader, vocabType)
            }
        }

        idToToken = Array(vocab.size) { "" }
        vocab.forEach { (token, id) ->
            if (id in idToToken.indices) {
                idToToken[id] = token
            }
        }

        // 2) special_tokens_map.json / tokenizer_config.json 에서 special token 문자열 추출
        val specialTokens = runCatching {
            context.assets.open(SPECIAL_TOKENS_FILE).use { input ->
                InputStreamReader(input).use { reader ->
                    gson.fromJson<Map<String, String>>(reader, object : TypeToken<Map<String, String>>() {}.type)
                }
            }
        }.getOrElse {
            Log.w(TAG, "Failed to load special_tokens_map.json: ${it.message}")
            emptyMap()
        }

        val tokenizerConfig = runCatching {
            context.assets.open(TOKENIZER_CONFIG_FILE).use { input ->
                InputStreamReader(input).use { reader ->
                    gson.fromJson<Map<String, Any>>(reader, object : TypeToken<Map<String, Any>>() {}.type)
                }
            }
        }.getOrElse {
            Log.w(TAG, "Failed to load tokenizer_config.json: ${it.message}")
            emptyMap()
        }

        val unkTokenStr = specialTokens["unk_token"]
            ?: (tokenizerConfig["unk_token"] as? String)
        val bosTokenStr = specialTokens["bos_token"]
            ?: (tokenizerConfig["bos_token"] as? String)
        val eosTokenStr = specialTokens["eos_token"]
            ?: (tokenizerConfig["eos_token"] as? String)

        unkTokenId = vocab[unkTokenStr] ?: 0
        bosTokenId = bosTokenStr?.let { vocab[it] }
        eosTokenId = eosTokenStr?.let { vocab[it] }

        Log.i(TAG, "=== SmolLM2 Tokenizer Loaded ===")
        Log.i(TAG, "  - Vocab size: ${vocab.size}")
        Log.i(TAG, "  - unk_token: '$unkTokenStr' (id=$unkTokenId)")
        Log.i(TAG, "  - bos_token: '$bosTokenStr' (id=$bosTokenId)")
        Log.i(TAG, "  - eos_token: '$eosTokenStr' (id=$eosTokenId)")
    }

    /**
     * 주어진 텍스트를 매우 단순한 방식으로 토큰 ID 시퀀스로 변환한다.
     *
     * - 공백 기준으로 단어를 나눈 뒤, vocab에서 그대로 찾는다.
     * - vocab에 없는 토큰은 unk_token으로 대체한다.
     * - 향후에는 SmolLM2에 맞는 BPE/SentencePiece 스타일 토크나이저로 확장할 수 있다.
     */
    fun encode(text: String, addBosEos: Boolean = true): LongArray {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val ids = mutableListOf<Int>()

        if (addBosEos) {
            bosTokenId?.let { ids.add(it) }
        }

        for (token in tokens) {
            val id = vocab[token] ?: unkTokenId
            ids.add(id)
        }

        if (addBosEos) {
            eosTokenId?.let { ids.add(it) }
        }

        val result = ids.map { it.toLong() }.toLongArray()

        Log.d(TAG, "Encoded text: \"$text\"")
        Log.d(TAG, "  - Token count: ${result.size}")
        Log.d(TAG, "  - Token ids preview: ${result.take(16)}")

        return result
    }

    /**
     * 토큰 ID 시퀀스를 매우 단순한 방식으로 텍스트로 변환한다.
     *
     * - id → token 매핑 후 공백으로 join.
     * - bos/eos 등 special token은 기본적으로 제거한다.
     */
    fun decode(ids: LongArray): String {
        if (ids.isEmpty()) return ""

        val skipIds = buildSet {
            add(unkTokenId)
            bosTokenId?.let { add(it) }
            eosTokenId?.let { add(it) }
        }

        val tokens = ids
            .mapNotNull { id ->
                val intId = id.toInt()
                if (intId in idToToken.indices && intId !in skipIds) {
                    idToToken[intId]
                } else {
                    null
                }
            }

        val text = tokens.joinToString(" ")
        Log.d(TAG, "Decoded ids: ${ids.toList()}")
        Log.d(TAG, "  - Text: \"$text\"")

        return text
    }
}

