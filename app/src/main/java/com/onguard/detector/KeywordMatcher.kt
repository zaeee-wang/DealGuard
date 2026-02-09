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
     * CRITICAL (0.6f): 직접적 금전/인증 요구, 기관 사칭 → UI에서 "고위험"으로 표시
     * HIGH (0.4f): 간접적 금전 관련, 피싱 키워드 → UI에서 "중위험"으로 표시
     * MEDIUM (0.25f): 의심스러운 표현 → UI에서 "저위험"으로 표시
     */
    private enum class KeywordWeight(val weight: Float) {
        CRITICAL(0.6f),  // UI: 고위험
        HIGH(0.4f),      // UI: 중위험
        MEDIUM(0.25f)    // UI: 저위험
    }

    // 가중치별 키워드 맵
    private val weightedKeywords = mapOf(
        KeywordWeight.CRITICAL to setOf(
            // 직접적인 금전 요구 (매우 위험)
            "계좌번호알려주", "계좌번호보내", "입금해주", "송금해주", "이체해주",
            "선입금", "선결제", "선지급", "보증금입금", "착불결제",

            // 긴급 금전 요구 (매우 위험 - 명확한 조합만)
            "급하게필요", "긴급송금", "빨리입금", "즉시송금",

            // 인증정보 요구 (매우 위험)
            "인증번호알려", "OTP번호", "보안카드번호", "비밀번호알려", "공인인증서",
            "카드번호알려", "CVC번호", "CVV번호", "유효기간알려",

            // 협박/사칭 (매우 위험)
            "체포영장", "구속영장", "압수수색", "벌금납부", "과태료납부",
            "검찰청에서", "경찰청에서", "금감원에서", "국세청에서", "법원에서"
        ),

        KeywordWeight.HIGH to setOf(
            // 금전 관련 (높은 위험) - 일상 대화 표현 제거
            "급전", "현금대출", "무담보대출",
            "신용대출", "소액대출", "당일대출", "즉시대출", "무서류대출",

            // 계좌/송금 (높은 위험)
            // 주의: 은행 이름은 제거됨 (일반 대화에서 자주 언급되므로 오탐 유발)
            "계좌번호", "송금", "입금", "이체", "무통장입금", "현금입금",
            "송금확인", "입금확인", "이체확인", "계좌확인", "입금계좌",

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
            "리딩방", "시그널방", "VIP방", "수익인증", "프리미엄방",
            "텔레방", "오픈채팅방", "단톡방", "수익공유",
            "따라만하세요", "복사만하세요", "무조건수익", "원금2배",

            // 가상화폐/NFT 사기 (높은 위험) - 신규 추가
            "이더리움", "리플", "도지코인", "NFT", "에어드랍",
            "ICO", "채굴", "지갑주소", "코인지갑", "트론",
            "바이낸스", "업비트", "빗썸", "코인원",

            // 로맨스 스캠 (높은 위험) - 신규 추가
            "사랑해요", "보고싶어요", "결혼하고싶어", "만나고싶어",
            "항공권비용", "비자비용", "병원비", "수술비",
            "외국에있는데", "해외출장중", "군인이라서", "선물보내고싶어",
            "세관문제", "통관비용", "짐보관비", "호텔비"
        ),

        KeywordWeight.MEDIUM to setOf(
            // 일상 대화 표현 (중간 위험으로 완화)
            "급하게", "돈필요", "빌려주세요", "대출",
            "돈빌려", "돈좀", "급하게돈",
            
            // 축약형/이모티콘 표현 (신규 추가)
            "계좌ㄱ", "송금ㄱ", "입금ㄱ", "리딩방ㄱ",
            "계ㄱ", "돈ㄱ", "급ㄱ",
            
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

            // 긴급성 표현 (중간 위험으로 완화 - 조합에서만 의미)
            "긴급", "지금당장", "오늘안에", "빨리", "즉시",
            "1시간이내", "30분이내", "시간없어", "마감임박",
            
            // SNS/계정 관련 (중간 위험) - 신규 추가
            "계정복구", "계정잠김", "로그인시도", "의심스러운활동",
            "인스타그램", "페이스북", "카카오계정", "네이버계정",

            // 정부 지원금 사칭 (중간 위험) - 신규 추가
            "긴급재난지원금", "소상공인지원", "청년지원금", "복지급여",
            "정부지원", "지원대상자", "신청기한",
            
            // 일반 거래/알바 표현 (중간 위험)
            "재택", "부업", "투잡", "간단업무", "쉬운일"
        )
    )

    // 정규식 패턴 (가중치별)
    private data class PatternInfo(val regex: Regex, val weight: Float, val description: String)

    /**
     * 정규식 패턴 가중치
     *
     * ## 중요: 전화번호/계좌번호 패턴은 여기서 제외됨
     * - 전화번호: PhoneAnalyzer가 Counter Scam 112 API로 확인
     * - 계좌번호: AccountAnalyzer가 경찰청 API로 확인
     * - API에서 스캠으로 확인된 경우에만 위험도에 반영됨
     *
     * ## 여기서 탐지하는 패턴 (API 확인 불필요한 것들)
     * - URL: UrlAnalyzer에서 KISA DB로 추가 확인
     * - 가상화폐 지갑: 스캠에서 자주 사용
     * - 금액 패턴: 키워드와 함께 맥락적 위험
     *
     * ## 제거된 패턴 (API 조회 불가)
     * - 주민등록번호: 범죄자 여부 조회 불가, 오탐 유발
     * - 여권번호: 범죄자 여부 조회 불가
     */
    private val highRiskPatterns = listOf(

        // ========== URL 패턴 (UrlAnalyzer에서 KISA DB 추가 확인) ==========
        // 단축 URL 서비스 (피싱에 자주 사용)
        PatternInfo(Regex("https?://(bit\\.ly|goo\\.gl|tinyurl\\.com|t\\.co|is\\.gd|v\\.gd|ow\\.ly|buff\\.ly)/\\S+"), 0.25f, "단축 URL"),
        // 무료/의심 도메인 (피싱에 자주 사용)
        PatternInfo(Regex("https?://\\S*\\.(tk|ml|ga|cf|gq|xyz|top|work|click|link|online)(/\\S*)?"), 0.3f, "무료/의심 도메인"),
        // IP 직접 접근 (피싱 특징)
        PatternInfo(Regex("https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?(/\\S*)?"), 0.35f, "IP 직접 접근 URL"),

        // ========== 금액 패턴 (키워드와 맥락적 위험) ==========
        PatternInfo(Regex("\\d{1,3}(,\\d{3})+원"), 0.15f, "금액 표시"),
        PatternInfo(Regex("\\d+만원"), 0.15f, "만원 단위 금액"),

        // ========== 가상화폐 지갑 주소 (스캠에서 자주 사용) ==========
        // 비트코인 주소 (1, 3, bc1로 시작)
        PatternInfo(Regex("(1|3|bc1)[a-zA-Z0-9]{25,39}"), 0.3f, "비트코인 지갑 주소"),
        // 이더리움 주소 (0x로 시작, 40자 hex)
        PatternInfo(Regex("0x[a-fA-F0-9]{40}"), 0.3f, "이더리움 지갑 주소")

        // ========== 제거된 패턴 (PhoneAnalyzer/AccountAnalyzer가 API로 확인) ==========
        // - 계좌번호 패턴: AccountAnalyzer → 경찰청 API (count >= 3 시에만 위험)
        // - 전화번호 패턴: PhoneAnalyzer → Counter Scam 112 API (등록 시에만 위험)
        // - 연속 숫자: 위의 분석기에서 처리
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
        val highKeywords = mutableListOf<String>()
        val mediumKeywords = mutableListOf<String>()
        val lowKeywords = mutableListOf<String>()

        // 1. 가중치별 키워드 분석
        weightedKeywords.forEach { (weight, keywords) ->
            val detected = keywords.filter { keyword ->
                normalizedText.contains(keyword.lowercase())
            }

            if (detected.isNotEmpty()) {
                val keywordConfidence = detected.size * weight.weight
                totalConfidence += keywordConfidence
                allDetectedKeywords.addAll(detected)

                when (weight) {
                    KeywordWeight.CRITICAL -> {
                        highKeywords.addAll(detected)  // UI에서 "고위험"으로 표시됨
                        reasons.add("매우 위험 키워드 ${detected.size}개 발견: ${detected.take(3).joinToString(", ")}")
                    }
                    KeywordWeight.HIGH -> {
                        mediumKeywords.addAll(detected)  // UI에서 "중위험"으로 표시됨
                        reasons.add("위험 키워드 ${detected.size}개 발견: ${detected.take(3).joinToString(", ")}")
                    }
                    KeywordWeight.MEDIUM -> {
                        lowKeywords.addAll(detected)  // UI에서 "저위험"으로 표시됨
                        reasons.add("의심 키워드 ${detected.size}개 발견: ${detected.take(3).joinToString(", ")}")
                    }
                }
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
                // 패턴도 태그로 표시하기 위해 중위험군에 포함 (또는 별도 분류 가능)
                mediumKeywords.add(pattern.description)
            }
        }

        // 3. 조합 패턴 보너스 (여러 카테고리 동시 발견 시 위험도 증가)
        val hasMoney = allDetectedKeywords.any { 
            it.contains("급전") || it.contains("송금") || it.contains("입금") || 
            it.contains("계좌") || it.contains("이체") 
        }
        val hasUrgency = allDetectedKeywords.any { 
            it.contains("급하") || it.contains("빨리") || it.contains("즉시") || 
            it.contains("긴급") || it.contains("지금당장") || it.contains("오늘안에")
        }
        val hasAuth = allDetectedKeywords.any { 
            it.contains("인증") || it.contains("OTP") || it.contains("비밀번호") ||
            it.contains("보안카드")
        }
        val hasInvestment = allDetectedKeywords.any {
            it.contains("수익") || it.contains("투자") || it.contains("리딩") ||
            it.contains("보장")
        }

        // 위험한 조합 보너스 (조합일 때만 높은 점수)
        var comboBonus = 0f
        if (hasMoney && hasUrgency) {
            comboBonus += 0.15f  // 금전 + 긴급
            reasons.add("복합 스캠 패턴: 금전 요구 + 긴급성")
        }
        if (hasMoney && hasAuth) {
            comboBonus += 0.2f  // 금전 + 인증 (더 위험)
            reasons.add("복합 스캠 패턴: 금전 요구 + 인증 정보")
        }
        if (hasAuth && hasUrgency) {
            comboBonus += 0.15f  // 인증 + 긴급
            reasons.add("복합 스캠 패턴: 인증 정보 + 긴급성")
        }
        if (hasInvestment && hasMoney) {
            comboBonus += 0.1f  // 투자 + 금전
            reasons.add("복합 스캠 패턴: 투자 사기")
        }
        
        totalConfidence += comboBonus
        
        // 의심스러운 조합 여부
        val hasSuspiciousCombination = comboBonus > 0f

        // 최종 신뢰도 계산 (0~1 범위로 정규화)
        val finalConfidence = totalConfidence.coerceIn(0f, 1f)

        return ScamAnalysis(
            isScam = finalConfidence >= 0.5f,
            confidence = finalConfidence,
            reasons = reasons,
            detectedKeywords = allDetectedKeywords,
            highRiskKeywords = highKeywords,
            mediumRiskKeywords = mediumKeywords,
            lowRiskKeywords = lowKeywords,
            hasSuspiciousCombination = hasSuspiciousCombination,
            detectionMethod = DetectionMethod.RULE_BASED
        )
    }
}
