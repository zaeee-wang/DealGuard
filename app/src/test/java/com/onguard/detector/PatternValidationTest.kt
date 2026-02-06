package com.onguard.detector

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 실제 한국 계좌번호/전화번호 형식 검증 테스트
 *
 * 목적:
 * 1. 현재 정규식 패턴이 실제 형식을 올바르게 탐지하는지 확인
 * 2. 오탐(False Positive) 케이스 확인
 * 3. 미탐(False Negative) 케이스 확인
 */
class PatternValidationTest {

    private lateinit var keywordMatcher: KeywordMatcher

    // 개선된 패턴 (KeywordMatcher.kt v0.2.3)
    // 계좌번호 패턴
    private val accountPattern3Part = Regex("\\d{3,4}-\\d{2,6}-\\d{4,7}")  // 3단 형식
    private val accountPatternKB = Regex("\\d{6}-\\d{2}-\\d{6}")           // 국민은행 6-2-6
    private val accountPattern4Part = Regex("\\d{3}-\\d{4}-\\d{4}-\\d{2}") // 농협 4단
    private val consecutiveDigits = Regex("\\d{10,14}")

    // 전화번호 패턴
    private val mobilePattern = Regex("01[016789]-?\\d{3,4}-?\\d{4}")      // 휴대폰
    private val phonePattern = Regex("0\\d{1,2}-\\d{3,4}-\\d{4}")          // 일반전화
    private val representativePattern = Regex("1[56][0-9]{2}-\\d{4}")       // 대표번호

    // 개인정보 패턴
    private val ssnPattern = Regex("\\d{6}-?[1-4]\\d{6}")

    // 가상화폐 지갑 주소
    private val btcPattern = Regex("(1|3|bc1)[a-zA-Z0-9]{25,39}")
    private val ethPattern = Regex("0x[a-fA-F0-9]{40}")

    @Before
    fun setup() {
        keywordMatcher = KeywordMatcher()
    }

    // ========== 실제 한국 은행 계좌번호 형식 테스트 ==========

    @Test
    fun `국민은행 계좌번호 형식 테스트`() {
        // 국민은행: 6자리-2자리-6자리 또는 3자리-2자리-4자리-3자리
        val kbFormats = listOf(
            "123456-12-123456",   // 14자리 (구형식)
            "123-12-1234-123",    // 12자리 (신형식)
            "12345612123456"      // 하이픈 없음
        )

        println("=== 국민은행 계좌번호 테스트 ===")
        kbFormats.forEach { account ->
            val matchesKB = accountPatternKB.containsMatchIn(account)
            val matches3Part = accountPattern3Part.containsMatchIn(account)
            val matchesConsecutive = consecutiveDigits.containsMatchIn(account)
            println("$account -> 국민패턴: $matchesKB, 3단패턴: $matches3Part, 연속숫자: $matchesConsecutive")
        }

        // 국민은행 6-2-6 형식 매칭 확인
        assertTrue("국민은행 6-2-6 형식", accountPatternKB.containsMatchIn("123456-12-123456"))
        assertTrue("14자리 연속 숫자 탐지", consecutiveDigits.containsMatchIn("12345612123456"))
    }

    @Test
    fun `신한은행 계좌번호 형식 테스트`() {
        // 신한은행: 3자리-3자리-6자리 (12자리)
        val shinhanFormats = listOf(
            "110-123-123456",     // 표준 형식
            "110123123456"        // 하이픈 없음
        )

        println("=== 신한은행 계좌번호 테스트 ===")
        shinhanFormats.forEach { account ->
            val matches3Part = accountPattern3Part.containsMatchIn(account)
            val matchesConsecutive = consecutiveDigits.containsMatchIn(account)
            println("$account -> 3단패턴: $matches3Part, 연속숫자: $matchesConsecutive")
        }

        // 110-123-123456 패턴 검증
        assertTrue("신한은행 표준 형식", accountPattern3Part.containsMatchIn("110-123-123456"))
    }

    @Test
    fun `우리은행 계좌번호 형식 테스트`() {
        // 우리은행: 4자리-3자리-6자리 (13자리)
        val wooriFormats = listOf(
            "1002-123-123456",    // 표준 형식
            "1002123123456"       // 하이픈 없음
        )

        println("=== 우리은행 계좌번호 테스트 ===")
        wooriFormats.forEach { account ->
            val matches3Part = accountPattern3Part.containsMatchIn(account)
            val matchesConsecutive = consecutiveDigits.containsMatchIn(account)
            println("$account -> 3단패턴: $matches3Part, 연속숫자: $matchesConsecutive")
        }

        // 1002-123-123456 패턴 검증
        assertTrue("우리은행 표준 형식", accountPattern3Part.containsMatchIn("1002-123-123456"))
    }

    @Test
    fun `하나은행 계좌번호 형식 테스트`() {
        // 하나은행: 3자리-6자리-5자리 (14자리)
        val hanaFormats = listOf(
            "123-123456-12345",   // 표준 형식
            "12312345612345"      // 하이픈 없음
        )

        println("=== 하나은행 계좌번호 테스트 ===")
        hanaFormats.forEach { account ->
            val matches3Part = accountPattern3Part.containsMatchIn(account)
            println("$account -> 3단패턴: $matches3Part")
        }

        // 개선된 패턴으로 하나은행 형식 탐지 확인
        assertTrue("하나은행 형식 탐지", accountPattern3Part.containsMatchIn("123-123456-12345"))
    }

    @Test
    fun `농협 계좌번호 형식 테스트`() {
        // 농협: 3자리-4자리-4자리-2자리 (13자리) 또는 3자리-2자리-6자리
        val nhFormats = listOf(
            "351-1234-1234-13",   // 4단 형식
            "302-12-123456"       // 3단 형식
        )

        println("=== 농협 계좌번호 테스트 ===")
        nhFormats.forEach { account ->
            val matches4Part = accountPattern4Part.containsMatchIn(account)
            val matches3Part = accountPattern3Part.containsMatchIn(account)
            println("$account -> 4단패턴: $matches4Part, 3단패턴: $matches3Part")
        }

        // 농협 4단 형식 탐지 확인
        assertTrue("농협 4단 형식", accountPattern4Part.containsMatchIn("351-1234-1234-13"))
        assertTrue("농협 3단 형식", accountPattern3Part.containsMatchIn("302-12-123456"))
    }

    // ========== 전화번호 형식 테스트 ==========

    @Test
    fun `휴대폰 번호 형식 테스트`() {
        val mobileNumbers = listOf(
            "010-1234-5678",      // 표준 형식
            "01012345678",        // 하이픈 없음
            "010 1234 5678",      // 공백 구분
            "010.1234.5678"       // 점 구분
        )

        println("=== 휴대폰 번호 테스트 ===")
        mobileNumbers.forEach { phone ->
            val matches = mobilePattern.containsMatchIn(phone)
            println("$phone -> 휴대폰패턴: $matches")
        }

        assertTrue("표준 휴대폰 형식", mobilePattern.containsMatchIn("010-1234-5678"))
        assertTrue("하이픈 없는 형식", mobilePattern.containsMatchIn("01012345678"))
    }

    @Test
    fun `일반 전화번호 형식 테스트`() {
        val phoneNumbers = listOf(
            "02-1234-5678",       // 서울 (2자리 지역번호)
            "031-123-4567",       // 경기 (3자리 지역번호)
            "051-1234-5678",      // 부산
            "080-123-4567"        // 수신자부담
        )

        println("=== 일반 전화번호 테스트 ===")
        phoneNumbers.forEach { phone ->
            val matches = phonePattern.containsMatchIn(phone)
            println("$phone -> 전화번호패턴: $matches")
        }

        assertTrue("서울 전화번호", phonePattern.containsMatchIn("02-1234-5678"))
        assertTrue("경기 전화번호", phonePattern.containsMatchIn("031-123-4567"))
    }

    @Test
    fun `대표번호 형식 테스트`() {
        val representativeNumbers = listOf(
            "1588-1234",          // 대표번호
            "1544-1234",          // 대표번호
            "1566-1234",          // 대표번호
            "1600-1234"           // 대표번호
        )

        println("=== 대표번호 테스트 ===")
        representativeNumbers.forEach { phone ->
            val matches = representativePattern.containsMatchIn(phone)
            println("$phone -> 대표번호패턴: $matches")
        }

        assertTrue("1588 대표번호", representativePattern.containsMatchIn("1588-1234"))
        assertTrue("1544 대표번호", representativePattern.containsMatchIn("1544-1234"))
    }

    @Test
    fun `가상화폐 지갑 주소 테스트`() {
        val cryptoAddresses = listOf(
            // 비트코인 주소
            "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",      // Legacy (1로 시작)
            "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",      // SegWit (3으로 시작)
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", // Bech32 (bc1으로 시작)

            // 이더리움 주소
            "0x742d35Cc6634C0532925a3b844Bc9e7595f4E4A3"
        )

        println("=== 가상화폐 지갑 주소 테스트 ===")
        cryptoAddresses.forEachIndexed { index, address ->
            val matchesBTC = btcPattern.containsMatchIn(address)
            val matchesETH = ethPattern.containsMatchIn(address)
            println("[$index] $address -> BTC: $matchesBTC, ETH: $matchesETH")
        }

        assertTrue("비트코인 Legacy 주소", btcPattern.containsMatchIn("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"))
        assertTrue("비트코인 SegWit 주소", btcPattern.containsMatchIn("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"))
        assertTrue("비트코인 Bech32 주소", btcPattern.containsMatchIn("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertTrue("이더리움 주소", ethPattern.containsMatchIn("0x742d35Cc6634C0532925a3b844Bc9e7595f4E4A3"))
    }

    @Test
    fun `주민등록번호 형식 테스트`() {
        val ssnNumbers = listOf(
            "950101-1234567",     // 표준 형식 (남자 1900년대)
            "000101-3234567",     // 2000년대 남자
            "9501011234567",      // 하이픈 없음
            "850101-2345678"      // 여자
        )

        println("=== 주민등록번호 테스트 ===")
        ssnNumbers.forEach { ssn ->
            val matches = ssnPattern.containsMatchIn(ssn)
            println("$ssn -> 주민번호패턴: $matches")
        }

        assertTrue("표준 주민번호 형식", ssnPattern.containsMatchIn("950101-1234567"))
        assertTrue("하이픈 없는 형식", ssnPattern.containsMatchIn("9501011234567"))
    }

    // ========== 오탐 방지 테스트 (False Positive) ==========

    @Test
    fun `키보드 숫자열 오탐 테스트`() {
        val keyboardStrings = listOf(
            "0123456789",         // 키보드 숫자열
            "1234567890",         // 순차 숫자
            "0 1 2 3 4 5 6 7 8 9" // 공백 포함
        )

        println("=== 키보드 숫자열 오탐 테스트 ===")
        keyboardStrings.forEach { str ->
            val normalized = str.replace("\\s".toRegex(), "")
            val matchesConsecutive = consecutiveDigits.containsMatchIn(normalized)
            println("$str -> 연속숫자(정규화후): $matchesConsecutive")
        }

        // 10자리 연속 숫자는 매칭되지만, 키워드 없이 단독으로는 스캠 판정 안함
        val result = keywordMatcher.analyze("0 1 2 3 4 5 6 7 8 9")
        assertFalse("키보드 숫자열만으로는 스캠 아님", result.isScam)
    }

    @Test
    fun `일반 날짜 형식 오탐 테스트`() {
        val dates = listOf(
            "2024-01-15",         // ISO 날짜
            "2024-1-5",           // 짧은 날짜
            "24-01-15"            // 짧은 연도
        )

        println("=== 날짜 형식 오탐 테스트 ===")
        dates.forEach { date ->
            val matchesAccount = accountPattern3Part.containsMatchIn(date)
            println("$date -> 계좌패턴: $matchesAccount")
        }
    }

    // ========== 실제 스캠 메시지 테스트 ==========

    @Test
    fun `실제 스캠 메시지 탐지 테스트`() {
        val scamMessages = listOf(
            // 보이스피싱
            "검찰청입니다. 귀하의 계좌가 범죄에 연루되었습니다. 확인을 위해 110-123-456789로 송금해주세요.",

            // 메신저 피싱
            "급전 필요하시면 연락주세요. 계좌번호 123-456-789012로 입금해주세요. 인증번호 알려주시면 바로",

            // 대출 사기
            "무담보 당일대출 가능! 신용불량자도 OK. 010-1234-5678로 문의하세요. 선입금 없음!",

            // 중고거래 사기
            "직거래 힘들어서 택배로 보내드릴게요. 선입금 부탁드립니다. 국민은행 123456-12-123456",

            // 투자 사기
            "원금보장 고수익! 비트코인 투자로 월 30% 수익! 지금 바로 참여하세요. 입금계좌: 110-123-123456"
        )

        println("=== 실제 스캠 메시지 탐지 테스트 ===")
        scamMessages.forEachIndexed { index, message ->
            val result = keywordMatcher.analyze(message)
            println("\n[$index] 메시지: ${message.take(50)}...")
            println("    스캠여부: ${result.isScam}, 신뢰도: ${result.confidence}")
            println("    탐지키워드: ${result.detectedKeywords.take(5)}")
            println("    이유: ${result.reasons.take(3)}")

            assertTrue("스캠 메시지 $index 탐지 실패", result.isScam)
        }
    }

    @Test
    fun `정상 메시지 오탐 방지 테스트`() {
        val normalMessages = listOf(
            "내일 점심 같이 먹을래? 12시에 학교 앞에서 만나자",
            "오늘 회의 몇 시야? 3시에 회의실에서 봐",
            "생일 축하해! 좋은 하루 보내",
            "주말에 영화 볼래? 강남역에서 만나자",
            "택배 왔어? 현관문 앞에 놔뒀대"
        )

        println("=== 정상 메시지 오탐 방지 테스트 ===")
        normalMessages.forEachIndexed { index, message ->
            val result = keywordMatcher.analyze(message)
            println("[$index] $message -> 스캠: ${result.isScam}, 신뢰도: ${result.confidence}")

            assertFalse("정상 메시지 $index 오탐", result.isScam)
        }
    }

    // ========== 추가 권장 키워드 테스트 ==========

    @Test
    fun `추가 권장 키워드 - 로맨스 스캠`() {
        val romanceScamMessage = "사랑해요 자기야. 나 지금 외국에있는데 급하게 병원비가 필요해. 100만원만 보내줄 수 있어? 계좌번호 123-456-789012"

        val result = keywordMatcher.analyze(romanceScamMessage)
        println("=== 로맨스 스캠 메시지 테스트 ===")
        println("메시지: $romanceScamMessage")
        println("스캠여부: ${result.isScam}, 신뢰도: ${result.confidence}")
        println("탐지키워드: ${result.detectedKeywords}")

        // 이제 로맨스 스캠 키워드가 포함되어 탐지되어야 함
        assertTrue("로맨스 스캠 메시지 탐지", result.isScam)
        assertTrue("병원비 키워드 탐지", result.detectedKeywords.any { it.contains("병원비") })
    }

    @Test
    fun `추가 권장 키워드 - 가상화폐 스캠`() {
        val cryptoScamMessage = "이더리움 에어드랍 이벤트! 지갑주소 보내시면 즉시 지급! 선착순 100명! 참여비 5만원 입금하세요."

        val result = keywordMatcher.analyze(cryptoScamMessage)
        println("=== 가상화폐 스캠 메시지 테스트 ===")
        println("메시지: $cryptoScamMessage")
        println("스캠여부: ${result.isScam}, 신뢰도: ${result.confidence}")
        println("탐지키워드: ${result.detectedKeywords}")

        // 가상화폐 키워드가 포함되어 탐지되어야 함
        assertTrue("가상화폐 스캠 메시지 탐지", result.isScam)
        assertTrue("이더리움 키워드 탐지", result.detectedKeywords.any { it.contains("이더리움") })
        assertTrue("에어드랍 키워드 탐지", result.detectedKeywords.any { it.contains("에어드랍") })
    }

    @Test
    fun `추가 권장 키워드 - SNS 사칭`() {
        val snsScamMessage = "카카오계정 비밀번호변경이 필요합니다. 본인확인을 위해 인증번호를 알려주세요."

        val result = keywordMatcher.analyze(snsScamMessage)
        println("=== SNS 사칭 스캠 메시지 테스트 ===")
        println("메시지: $snsScamMessage")
        println("스캠여부: ${result.isScam}, 신뢰도: ${result.confidence}")
        println("탐지키워드: ${result.detectedKeywords}")

        // SNS 사칭 키워드가 포함되어 탐지되어야 함
        assertTrue("SNS 사칭 스캠 메시지 탐지", result.isScam)
        assertTrue("카카오계정 키워드 탐지", result.detectedKeywords.any { it.contains("카카오계정") })
        assertTrue("인증번호 키워드 탐지", result.detectedKeywords.any { it.contains("인증번호") })
    }

    @Test
    fun `정부 지원금 사칭 스캠 테스트`() {
        val govScamMessage = "긴급재난지원금 신청 안내입니다. 지원대상자로 선정되셨습니다. 신청기한 내 본인확인을 위해 아래 링크 클릭해주세요."

        val result = keywordMatcher.analyze(govScamMessage)
        println("=== 정부 지원금 사칭 테스트 ===")
        println("메시지: $govScamMessage")
        println("스캠여부: ${result.isScam}, 신뢰도: ${result.confidence}")
        println("탐지키워드: ${result.detectedKeywords}")

        assertTrue("정부 지원금 사칭 탐지", result.isScam)
    }
}
