package com.onguard.detector

import com.onguard.domain.model.DetectionMethod
import com.onguard.domain.model.ScamAnalysis
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 규칙 기반 키워드 매칭기.
 *
 * CRITICAL/HIGH/MEDIUM 3단계 가중치 체계로 스캠 키워드와 정규식 패턴을 분석한다.
 * 스캠 판정 임계값은 0.5(50%)이며, 격리된 단일 패턴만으로는 판정하지 않아 오탐을 줄인다.
 *
 * @see ScamAnalysis 분석 결과 반환
 */
@Singleton
class KeywordMatcher @Inject constructor() {

    /**
     * 키워드 가중치 체계
     *
     * 테스트 단계에서는 LLM/오버레이 동작을 쉽게 확인하기 위해
     * 기본 가중치를 다소 공격적으로 높여둔다.
     * (실 서비스 단계에서 다시 보수적으로 조정 예정)
     *
     * CRITICAL (0.6f): 직접적 금전/인증 요구, 기관 사칭
     * HIGH (0.4f): 간접적 금전 관련, 피싱 키워드
     * MEDIUM (0.25f): 의심스러운 표현
     */
    private enum class KeywordWeight(val weight: Float) {
        CRITICAL(0.6f),
        HIGH(0.4f),
        MEDIUM(0.25f)
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
            "돈빌려", "돈좀", "급하게돈",

            // 계좌/송금 (높은 위험)
            "계좌번호", "송금", "입금", "이체", "무통장입금", "현금입금",
            "송금확인", "입금확인", "이체확인", "계좌확인", "입금계좌",
            "국민은행", "신한은행", "우리은행", "하나은행", "농협",
            "카카오뱅크", "토스뱅크", "케이뱅크",

            // 피싱 관련 (높은 위험)
            "인증번호", "OTP", "보안카드", "비밀번호", "개인정보확인",
            "신분증사진", "신분증촬영", "통장사본", "주민번호",
            "본인확인", "계정잠금", "비밀번호변경", "로그인실패",

            // 사칭 기관 (높은 위험)
            "경찰청", "검찰청", "금융감독원", "금감원", "국세청", "관세청",
            "법원", "행정안전부", "국민연금", "건강보험공단",
            "우체국", "국민건강보험", "사이버수사대",

            // 불법 거래 (높은 위험)
            "대포폰", "대포통장", "명의대여", "계좌대여", "법인통장",
            "휴대폰개통", "휴대폰대납", "카드대납",

            // 투자 사기 (높은 위험)
            "원금보장", "수익보장", "고수익", "단기수익", "확실한수익",
            "비트코인투자", "코인투자", "해외선물", "FX마진", "주식리딩",
            "리딩방", "시그널방", "VIP방", "수익인증",

            // 가상화폐/NFT 사기 (높은 위험) - 신규 추가
            "이더리움", "리플", "도지코인", "NFT", "에어드랍",
            "ICO", "채굴", "지갑주소", "코인지갑", "트론",
            "바이낸스", "업비트", "빗썸", "코인원",

            // 로맨스 스캠 (높은 위험) - 신규 추가
            "사랑해요", "보고싶어요", "결혼하고싶어", "만나고싶어",
            "항공권비용", "비자비용", "병원비", "수술비",
            "외국에있는데", "해외출장중"
        ),

        KeywordWeight.MEDIUM to setOf(
            // 의심스러운 제안 (중간 위험)
            "당첨", "환급", "세금", "환불", "보상금", "지원금", "장려금",
            "무료증정", "무료지급", "무료제공", "무료나눔",
            "축하합니다", "행운의주인공", "추첨결과",

            // 협박성 단어 (중간 위험)
            "체포", "구속", "영장", "벌금", "과태료", "고소", "고발",
            "소송", "법적조치", "강제집행", "압류", "연체",

            // 거래 관련 (중간 위험)
            "선구매", "선예약", "선착순", "한정수량", "특가", "파격할인",
            "반값", "90%할인", "거의공짜", "무료배송",
            "직거래안됨", "택배만가능", "물건보내드림",

            // 택배 사칭 (중간 위험)
            "택배발송", "택배비", "추가배송비", "배송비결제", "배송대행",
            "반품비용", "교환비용", "착불비",
            "배송조회", "배송실패", "주소확인",

            // 알바/구인 사기 (중간 위험)
            "재택알바", "고수익알바", "간단한알바", "쉬운알바", "누구나가능",
            "통장만있으면", "신분증만있으면", "휴대폰만있으면",
            "일당", "주급", "간단업무", "투잡",

            // 기타 의심 단어 (중간 위험)
            "문자확인", "링크클릭", "앱설치", "프로그램설치", "원격제어",
            "팀뷰어", "애니데스크", "화면공유", "비밀보장", "절대비밀",

            // SNS/계정 관련 (중간 위험) - 신규 추가
            "계정복구", "계정잠김", "로그인시도", "의심스러운활동",
            "인스타그램", "페이스북", "카카오계정", "네이버계정",

            // 정부 지원금 사칭 (중간 위험) - 신규 추가
            "긴급재난지원금", "소상공인지원", "청년지원금", "복지급여",
            "정부지원", "지원대상자", "신청기한"
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
     *
     * 한국 은행 계좌번호 형식 (2024년 기준):
     * - 국민은행: 6-2-6 (123456-12-123456)
     * - 신한은행: 3-3-6 (110-123-123456)
     * - 우리은행: 4-3-6 (1002-123-123456)
     * - 하나은행: 3-6-5 (123-123456-12345)
     * - 농협: 3-4-4-2 (351-1234-1234-13)
     * - 카카오뱅크: 4-2-7 (3333-12-1234567)
     * - 토스뱅크: 4-4-4 (1000-1234-1234)
     */
    private val highRiskPatterns = listOf(
        // ========== 계좌번호 패턴 (한국 주요 은행 형식) ==========
        // 3단 형식: 3~4자리 - 2~6자리 - 4~7자리
        PatternInfo(Regex("\\d{3,4}-\\d{2,6}-\\d{4,7}"), 0.2f, "계좌번호 패턴 (3단)"),
        // 국민은행 구형식: 6-2-6
        PatternInfo(Regex("\\d{6}-\\d{2}-\\d{6}"), 0.2f, "계좌번호 패턴 (국민)"),
        // 농협/새마을금고 4단 형식: 3-4-4-2
        PatternInfo(Regex("\\d{3}-\\d{4}-\\d{4}-\\d{2}"), 0.2f, "계좌번호 패턴 (4단)"),
        // 연속 숫자 (하이픈 없는 계좌번호)
        PatternInfo(Regex("\\d{10,14}"), 0.1f, "연속된 숫자 (계좌/전화)"),

        // ========== 전화번호 패턴 ==========
        // 휴대폰: 010-XXXX-XXXX
        PatternInfo(Regex("01[016789]-?\\d{3,4}-?\\d{4}"), 0.1f, "휴대폰 번호"),
        // 일반전화: 02/031 등 지역번호
        PatternInfo(Regex("0\\d{1,2}-\\d{3,4}-\\d{4}"), 0.1f, "일반 전화번호"),
        // 대표번호: 1588, 1544, 1566, 1600, 1670 등
        PatternInfo(Regex("1[56][0-9]{2}-\\d{4}"), 0.15f, "대표번호 (사칭 주의)"),

        // ========== 개인정보 패턴 ==========
        // 주민등록번호: YYMMDD-[1-4]XXXXXX
        PatternInfo(Regex("\\d{6}-?[1-4]\\d{6}"), 0.4f, "주민등록번호"),
        // 여권번호: M12345678 형식
        PatternInfo(Regex("[A-Z][A-Z]?\\d{7,8}"), 0.3f, "여권번호 패턴"),

        // ========== URL 패턴 ==========
        // 단축 URL 서비스 (피싱에 자주 사용)
        PatternInfo(Regex("https?://(bit\\.ly|goo\\.gl|tinyurl\\.com|t\\.co|is\\.gd|v\\.gd|ow\\.ly|buff\\.ly)/\\S+"), 0.25f, "단축 URL"),
        // 무료/의심 도메인 (피싱에 자주 사용)
        PatternInfo(Regex("https?://\\S*\\.(tk|ml|ga|cf|gq|xyz|top|work|click|link|online)(/\\S*)?"), 0.3f, "무료/의심 도메인"),
        // IP 직접 접근 (피싱 특징)
        PatternInfo(Regex("https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?(/\\S*)?"), 0.35f, "IP 직접 접근 URL"),

        // ========== 금액 패턴 ==========
        PatternInfo(Regex("\\d{1,3}(,\\d{3})+원"), 0.15f, "금액 표시"),
        PatternInfo(Regex("\\d+만원"), 0.15f, "만원 단위 금액"),

        // ========== 가상화폐 지갑 주소 ==========
        // 비트코인 주소 (1, 3, bc1로 시작)
        PatternInfo(Regex("(1|3|bc1)[a-zA-Z0-9]{25,39}"), 0.3f, "비트코인 지갑 주소"),
        // 이더리움 주소 (0x로 시작, 40자 hex)
        PatternInfo(Regex("0x[a-fA-F0-9]{40}"), 0.3f, "이더리움 지갑 주소")
    )

    /**
     * 텍스트에서 위험 키워드·패턴을 분석하여 [ScamAnalysis]를 반환한다.
     *
     * @param text 분석할 채팅/메시지 텍스트
     * @return [ScamAnalysis] (신뢰도, 이유, 탐지된 키워드, RULE_BASED)
     */
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
            // 테스트 단계에서는 단일 패턴도 신뢰도에 포함하여
            // 민감도를 높인다. (실서비스 시 조건 재조정 권장)
            val patternConfidence = detectedPatterns.sumOf { it.weight.toDouble() }.toFloat()
            totalConfidence += patternConfidence

            detectedPatterns.forEach { pattern ->
                reasons.add("${pattern.description} 감지")
            }
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
            isScam = finalConfidence >= 0.5f,
            confidence = finalConfidence,
            reasons = reasons,
            detectedKeywords = allDetectedKeywords,
            detectionMethod = DetectionMethod.RULE_BASED
        )
    }

    /**
     * [analyze]와 동일. 하위 호환용 별칭.
     *
     * @param text 분석할 텍스트
     * @return [ScamAnalysis]
     */
    fun match(text: String): ScamAnalysis = analyze(text)
}
