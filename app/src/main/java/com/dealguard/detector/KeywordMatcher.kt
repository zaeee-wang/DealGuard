package com.dealguard.detector

import com.dealguard.domain.model.DetectionMethod
import com.dealguard.domain.model.ScamAnalysis
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 규칙 기반 키워드 매칭기
 *
 * 3단계 가중치 체계로 스캠 키워드를 분석합니다.
 * 스캠 판정 임계값: 0.5 (50%)
 *
 * 사용 예시:
 * - "급전 필요합니다" -> HIGH 키워드 1개 (0.25) -> 스캠 아님
 * - "계좌번호 알려주세요" -> CRITICAL 키워드 1개 (0.4) -> 스캠 아님
 * - "급전 필요, 계좌번호 보내세요" -> CRITICAL(0.4) + HIGH(0.25) = 0.65 -> 스캠!
 */
@Singleton
class KeywordMatcher @Inject constructor() {

    /**
     * 키워드 가중치 체계
     *
     * 가중치 설계 원칙:
     * - CRITICAL 2개 이상 또는 CRITICAL+HIGH 조합 시 스캠 판정
     * - 단일 키워드로는 스캠 판정 불가 (오탐 방지)
     *
     * CRITICAL (0.4f): 직접적 금전/인증 요구, 기관 사칭
     *   - 예: "계좌번호 알려주세요", "OTP번호 보내세요"
     *   - 2개 조합 시 0.8 (스캠 확정)
     *
     * HIGH (0.25f): 간접적 금전 관련, 피싱 키워드
     *   - 예: "급전", "대출", "인증번호"
     *   - CRITICAL과 조합 시 0.65 (스캠 판정)
     *
     * MEDIUM (0.15f): 의심스러운 표현
     *   - 예: "당첨", "환급", "무료"
     *   - 단독으로는 스캠 판정 불가, 보조 지표로 활용
     */
    private enum class KeywordWeight(val weight: Float) {
        CRITICAL(0.4f),
        HIGH(0.25f),
        MEDIUM(0.15f)
    }

    // 가중치별 키워드 맵
    private val weightedKeywords = mapOf(
        KeywordWeight.CRITICAL to setOf(
            // 직접적인 금전 요구 (매우 위험)
            "계좌번호알려주", "계좌번호보내", "입금해주", "송금해주", "이체해주",
            "선입금", "선결제", "선지급", "보증금입금", "착불결제",

            // 긴급 사기 (매우 위험)
            "급하게필요", "긴급송금", "지금당장", "빨리입금", "즉시송금",
            "오늘안에", "1시간이내", "30분이내",

            // 인증정보 요구 (매우 위험)
            "인증번호알려", "OTP번호", "보안카드번호", "비밀번호알려", "공인인증서",
            "카드번호알려", "CVC번호", "CVV번호", "유효기간알려",

            // 협박/사칭 (매우 위험)
            "체포영장", "구속영장", "압수수색", "벌금납부", "과태료납부",
            "검찰청에서", "경찰청에서", "금감원에서", "국세청에서", "법원에서"
        ),

        KeywordWeight.HIGH to setOf(
            // 금전 관련 (높은 위험)
            "급전", "급하게", "돈필요", "빌려주세요", "대출", "현금대출", "무담보대출",
            "신용대출", "소액대출", "당일대출", "즉시대출", "무서류대출",

            // 계좌/송금 (높은 위험)
            "계좌번호", "송금", "입금", "이체", "무통장입금", "현금입금",
            "송금확인", "입금확인", "이체확인", "계좌확인",

            // 피싱 관련 (높은 위험)
            "인증번호", "OTP", "보안카드", "비밀번호", "개인정보확인",
            "신분증사진", "신분증촬영", "통장사본", "주민번호",

            // 사칭 기관 (높은 위험)
            "경찰청", "검찰청", "금융감독원", "금감원", "국세청", "관세청",
            "법원", "행정안전부", "국민연금", "건강보험공단",

            // 불법 거래 (높은 위험)
            "대포폰", "대포통장", "명의대여", "계좌대여", "법인통장",
            "휴대폰개통", "휴대폰대납", "카드대납",

            // 투자 사기 (높은 위험)
            "원금보장", "수익보장", "고수익", "단기수익", "확실한수익",
            "비트코인투자", "코인투자", "해외선물", "FX마진", "주식리딩"
        ),

        KeywordWeight.MEDIUM to setOf(
            // 의심스러운 제안 (중간 위험)
            "당첨", "환급", "세금", "환불", "보상금", "지원금", "장려금",
            "무료증정", "무료지급", "무료제공", "무료나눔",

            // 협박성 단어 (중간 위험)
            "체포", "구속", "영장", "벌금", "과태료", "고소", "고발",
            "소송", "법적조치", "강제집행",

            // 거래 관련 (중간 위험)
            "선구매", "선예약", "선착순", "한정수량", "특가", "파격할인",
            "반값", "90%할인", "거의공짜", "무료배송",

            // 택배 사칭 (중간 위험)
            "택배발송", "택배비", "추가배송비", "배송비결제", "배송대행",
            "반품비용", "교환비용", "착불비",

            // 알바/구인 사기 (중간 위험)
            "재택알바", "고수익알바", "간단한알바", "쉬운알바", "누구나가능",
            "통장만있으면", "신분증만있으면", "휴대폰만있으면",

            // 기타 의심 단어 (중간 위험)
            "문자확인", "링크클릭", "앱설치", "프로그램설치", "원격제어",
            "팀뷰어", "애니데스크", "화면공유", "비밀보장", "절대비밀"
        )
    )

    // 정규식 패턴 (가중치별)
    private data class PatternInfo(val regex: Regex, val weight: Float, val description: String)

    /**
     * 정규식 패턴 가중치 (False Positive 방지를 위해 조정됨)
     *
     * 가중치 조정 이유:
     * - 전화번호/계좌번호 패턴은 키보드 UI, 자동완성 등에서 자주 탐지됨
     * - 격리된 단일 패턴은 스캠 증거로 약함 → 가중치 감소
     * - URL, 주민번호는 높은 위험 → 가중치 유지
     */
    private val highRiskPatterns = listOf(
        // 계좌번호 패턴 - 가중치 감소 (0.35 → 0.2)
        // UI 요소에서 자주 오탐지되므로 감소
        PatternInfo(Regex("\\d{3,4}-\\d{3,4}-\\d{4,6}"), 0.2f, "계좌번호 패턴"),
        // 연속 숫자 - 가중치 대폭 감소 (0.3 → 0.1)
        // 키보드 숫자열 "0123456789" 오탐 방지
        PatternInfo(Regex("\\d{10,14}"), 0.1f, "연속된 숫자"),

        // 전화번호 패턴 - 가중치 감소 (0.2 → 0.1)
        // 자동완성, 연락처 UI에서 자주 오탐지
        PatternInfo(Regex("010-?\\d{4}-?\\d{4}"), 0.1f, "휴대폰 번호"),
        PatternInfo(Regex("\\d{3}-\\d{3,4}-\\d{4}"), 0.1f, "전화번호"),

        // 주민번호 패턴 - 가중치 유지 (0.4)
        // 매우 위험한 개인정보이므로 높은 가중치 유지
        PatternInfo(Regex("\\d{6}-?[1-4]\\d{6}"), 0.4f, "주민등록번호"),

        // URL 패턴 - 가중치 유지
        // 단축 URL과 무료 도메인은 피싱에 자주 사용
        PatternInfo(Regex("https?://bit\\.ly/\\S+"), 0.25f, "단축 URL (bit.ly)"),
        PatternInfo(Regex("https?://goo\\.gl/\\S+"), 0.25f, "단축 URL (goo.gl)"),
        PatternInfo(Regex("https?://\\S*\\.(tk|ml|ga|cf|gq)\\S*"), 0.3f, "무료 도메인 URL"),

        // 금액 패턴 - 가중치 유지
        PatternInfo(Regex("\\d{1,3}(,\\d{3})+원"), 0.15f, "금액 표시"),
        PatternInfo(Regex("\\d+만원"), 0.15f, "만원 단위 금액")
    )

    fun analyze(text: String): ScamAnalysis {
        val normalizedText = text.lowercase().replace("\\s".toRegex(), "")

        val reasons = mutableListOf<String>()
        var totalConfidence = 0f
        val allDetectedKeywords = mutableListOf<String>()

        // 1. 가중치별 키워드 분석
        weightedKeywords.forEach { (weight, keywords) ->
            val detected = keywords.filter { keyword ->
                normalizedText.contains(keyword.lowercase())
            }

            if (detected.isNotEmpty()) {
                val keywordConfidence = detected.size * weight.weight
                totalConfidence += keywordConfidence
                allDetectedKeywords.addAll(detected)

                val level = when (weight) {
                    KeywordWeight.CRITICAL -> "매우 위험"
                    KeywordWeight.HIGH -> "위험"
                    KeywordWeight.MEDIUM -> "의심"
                }
                reasons.add("$level 키워드 ${detected.size}개 발견: ${detected.take(3).joinToString(", ")}")
            }
        }

        // 2. 정규식 패턴 분석 (최소 매칭 요구사항 적용)
        //
        // False Positive 방지:
        // - 격리된 단일 패턴(전화번호, 계좌번호)만으로는 스캠 판정 안함
        // - 조건: 2개 이상 패턴 OR (1개 패턴 + 키워드)
        val detectedPatterns = highRiskPatterns.filter { patternInfo ->
            patternInfo.regex.containsMatchIn(text)
        }

        val hasKeywords = allDetectedKeywords.isNotEmpty()
        val patternCount = detectedPatterns.size

        if (detectedPatterns.isNotEmpty()) {
            // 조건: 2개 이상 패턴 OR (1개 패턴 + 키워드 존재)
            if (patternCount >= 2 || (patternCount >= 1 && hasKeywords)) {
                val patternConfidence = detectedPatterns.sumOf { it.weight.toDouble() }.toFloat()
                totalConfidence += patternConfidence

                detectedPatterns.forEach { pattern ->
                    reasons.add("${pattern.description} 감지")
                }
            }
            // else: 격리된 단일 패턴은 신뢰도에 포함하지 않음 (오탐 방지)
        }

        // 3. 조합 패턴 보너스 (여러 카테고리 동시 발견 시 위험도 증가)
        val hasMoney = allDetectedKeywords.any { it.contains("급전") || it.contains("송금") || it.contains("입금") }
        val hasUrgency = allDetectedKeywords.any { it.contains("급하") || it.contains("빨리") || it.contains("즉시") }
        val hasAuth = allDetectedKeywords.any { it.contains("인증") || it.contains("OTP") || it.contains("비밀번호") }

        if ((hasMoney && hasUrgency) || (hasMoney && hasAuth) || (hasAuth && hasUrgency)) {
            totalConfidence += 0.2f
            reasons.add("복합 스캠 패턴 (조합 공격)")
        }

        // 최종 신뢰도 계산 (0~1 범위로 정규화)
        val finalConfidence = totalConfidence.coerceIn(0f, 1f)

        return ScamAnalysis(
            isScam = finalConfidence > 0.5f,
            confidence = finalConfidence,
            reasons = reasons,
            detectedKeywords = allDetectedKeywords,
            detectionMethod = DetectionMethod.RULE_BASED
        )
    }

    // 하위 호환성을 위한 메서드 (기존 match 메서드 유지)
    fun match(text: String): ScamAnalysis = analyze(text)
}
