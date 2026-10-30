package dev.ryuya.galaxyreload

/** This opt-in path retains the keyguard and never creates an authentication handover. */
internal object LockedReloadPolicy {
    fun rejection(
        user: Int,
        credentialReady: Boolean,
        interactive: Boolean,
        locked: Boolean,
    ): String? =
        when {
            user != 0 -> "UNSUPPORTED_USER"
            !credentialReady -> "USER_LOCKED"
            !interactive -> "SCREEN_OFF"
            !locked -> "NOT_LOCKED"
            else -> null
        }
}
