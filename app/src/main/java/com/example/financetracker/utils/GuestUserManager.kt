package com.example.financetracker.utils

import android.content.Context
import android.content.SharedPreferences

object GuestUserManager {
    private const val PREF_NAME = "guest_user_prefs"
    private const val KEY_GUEST_USER_ID = "guest_user_id"
    private const val KEY_IS_GUEST_MODE = "is_guest_mode"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getGuestUserId(context: Context): String {
        val prefs = getPreferences(context)
        var guestUserId = prefs.getString(KEY_GUEST_USER_ID, null)

        if (guestUserId == null) {
            // Generate a new guest user ID if none exists
            guestUserId = "guest_${System.currentTimeMillis()}"
            prefs.edit().putString(KEY_GUEST_USER_ID, guestUserId).apply()
        }

        return guestUserId
    }

    fun setGuestMode(context: Context, isGuestMode: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_IS_GUEST_MODE, isGuestMode).apply()
    }

    fun isGuestMode(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_IS_GUEST_MODE, false)
    }

    fun isGuestMode(userId: String): Boolean {
        return userId.startsWith("guest_")
    }

    fun clearGuestData(context: Context) {
        getPreferences(context).edit().clear().apply()
    }
}