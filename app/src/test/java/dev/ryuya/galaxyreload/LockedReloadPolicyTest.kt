package dev.ryuya.galaxyreload

import org.junit.Assert.*
import org.junit.Test

class LockedReloadPolicyTest {
    @Test
    fun onlyTheLitOwnerLockScreenWithCredentialStorageAlreadyAvailableCanReload() {
        assertNull(LockedReloadPolicy.rejection(0, true, true, true))
    }

    @Test
    fun anotherAndroidUserIsRejected() {
        assertEquals("UNSUPPORTED_USER", LockedReloadPolicy.rejection(10, true, true, true))
    }

    @Test
    fun firstUnlockAfterBootIsRequired() {
        assertEquals("USER_LOCKED", LockedReloadPolicy.rejection(0, false, true, true))
    }

    @Test
    fun screenOffIsRejected() {
        assertEquals("SCREEN_OFF", LockedReloadPolicy.rejection(0, true, false, true))
    }

    @Test
    fun anUnlockedSessionCannotEnterTheLockedOnlyPath() {
        assertEquals("NOT_LOCKED", LockedReloadPolicy.rejection(0, true, true, false))
    }
}
