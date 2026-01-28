package com.dealguard.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class PermissionHelperTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.packageName } returns "com.dealguard"
    }

    @Test
    fun `getAccessibilitySettingsIntent returns correct intent`() {
        val intent = PermissionHelper.getAccessibilitySettingsIntent()

        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `getOverlayPermissionIntent returns correct intent`() {
        val intent = PermissionHelper.getOverlayPermissionIntent(context)

        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
        assertEquals("package:com.dealguard", intent.data?.toString())
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `PermissionStatus missingPermissions returns correct list when all disabled`() {
        val status = PermissionHelper.PermissionStatus(
            accessibilityEnabled = false,
            overlayEnabled = false,
            notificationEnabled = false
        )

        assertEquals(3, status.missingPermissions.size)
        assertTrue(status.missingPermissions.contains("접근성 서비스"))
        assertTrue(status.missingPermissions.contains("오버레이 권한"))
        assertTrue(status.missingPermissions.contains("알림 권한"))
        assertFalse(status.allGranted)
    }

    @Test
    fun `PermissionStatus allGranted returns true when all permissions enabled`() {
        val status = PermissionHelper.PermissionStatus(
            accessibilityEnabled = true,
            overlayEnabled = true,
            notificationEnabled = true
        )

        assertTrue(status.allGranted)
        assertTrue(status.missingPermissions.isEmpty())
    }

    @Test
    fun `PermissionStatus allGranted returns false when some permissions missing`() {
        val status = PermissionHelper.PermissionStatus(
            accessibilityEnabled = true,
            overlayEnabled = false,
            notificationEnabled = true
        )

        assertFalse(status.allGranted)
        assertEquals(1, status.missingPermissions.size)
        assertEquals("오버레이 권한", status.missingPermissions.first())
    }

    @Test
    fun `hasAllRequiredPermissions returns true only when all permissions granted`() {
        mockkStatic(PermissionHelper::class)

        // Case 1: All permissions granted
        every { PermissionHelper.isAccessibilityServiceEnabled(any()) } returns true
        every { PermissionHelper.canDrawOverlays(any()) } returns true
        every { PermissionHelper.hasNotificationPermission(any()) } returns true

        assertTrue(PermissionHelper.hasAllRequiredPermissions(context))

        // Case 2: One permission missing
        every { PermissionHelper.canDrawOverlays(any()) } returns false

        assertFalse(PermissionHelper.hasAllRequiredPermissions(context))
    }

    @Test
    fun `getPermissionStatus returns correct status object`() {
        mockkStatic(PermissionHelper::class)

        every { PermissionHelper.isAccessibilityServiceEnabled(any()) } returns true
        every { PermissionHelper.canDrawOverlays(any()) } returns false
        every { PermissionHelper.hasNotificationPermission(any()) } returns true
        every { PermissionHelper.getPermissionStatus(any()) } answers { callOriginal() }

        val status = PermissionHelper.getPermissionStatus(context)

        assertTrue(status.accessibilityEnabled)
        assertFalse(status.overlayEnabled)
        assertTrue(status.notificationEnabled)
        assertFalse(status.allGranted)
    }
}
