package com.onguard.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAccountValidatorTest {

    private val validator = PhoneAccountValidator.Default

    @Test
    fun `10자리 전화번호 유효`() {
        assertTrue(validator.isValidPhoneNumber("0101234567"))
        assertTrue(validator.isValidPhoneNumber("0212345678"))
    }

    @Test
    fun `11자리 전화번호 유효`() {
        assertTrue(validator.isValidPhoneNumber("01012345678"))
    }

    @Test
    fun `하이픈 포함 전화번호 정규화 후 유효`() {
        assertTrue(validator.isValidPhoneNumber("010-1234-5678"))
    }

    @Test
    fun `9자리 이하 전화번호 무효`() {
        assertFalse(validator.isValidPhoneNumber("010123456"))
        assertFalse(validator.isValidPhoneNumber("123"))
    }

    @Test
    fun `12자리 이상 전화번호 무효`() {
        assertFalse(validator.isValidPhoneNumber("010123456789"))
    }

    @Test
    fun `10자리 계좌번호 유효`() {
        assertTrue(validator.isValidAccountNumber("1234567890"))
    }

    @Test
    fun `14자리 계좌번호 유효`() {
        assertTrue(validator.isValidAccountNumber("12345678901234"))
    }

    @Test
    fun `하이픈 포함 계좌번호 정규화 후 유효`() {
        assertTrue(validator.isValidAccountNumber("123-456-789012"))
    }

    @Test
    fun `9자리 이하 계좌번호 무효`() {
        assertFalse(validator.isValidAccountNumber("123456789"))
    }

    @Test
    fun `15자리 이상 계좌번호 무효`() {
        assertFalse(validator.isValidAccountNumber("123456789012345"))
    }
}
