package com.example.financetracker.utils

import android.content.Context
import java.util.UUID

/**
 * Manager class for handling guest user functionality
 */
object GuestUserManager {
    private const val PREF_NAME = "guest_user_prefs"
    private const val KEY_GUEST_USER_ID = "guest_user_id"

    /**
     * Get or create a guest user ID
     */
    fun getGuestUserId(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var guestId = prefs.getString(KEY_GUEST_USER_ID, null)

        if (guestId == null) {
            // Create a new guest ID if it doesn't exist
            guestId = "guest-" + UUID.randomUUID().toString()
            prefs.edit().putString(KEY_GUEST_USER_ID, guestId).apply()
        }

        return guestId
    }

    /**
     * Check if we're using a guest account
     */
    fun isGuestMode(userId: String?): Boolean {
        return userId?.startsWith("guest-") == true
    }

    /**
     * Clear guest user data (for example, when logging in with a real account)
     */
    fun clearGuestData(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}