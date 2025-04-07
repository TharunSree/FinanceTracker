package com.example.financetracker.utils // Or your preferred package

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager // Import PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern // For Regex used in looksLikeBankCode

object SenderListManager {

    private const val PREFS_NAME = "FinancialSendersPrefs"
    private const val KEY_APPROVED_SENDERS = "approved_senders"
    private const val KEY_DISABLED_DEFAULT_SENDERS = "disabled_default_senders"
    private const val TAG = "SenderListManager"

    // Base default senders (normalized to uppercase for easier comparison)
    private val defaultSenders = setOf(
        "SBIUPI", "SBI", "SBIPSG", "HDFCBK", "ICICI", "AXISBK", "PAYTM",
        "GPAY", "PHONEPE", "-SBIINB", "-HDFCBK", "-ICICI", "-AXISBK",
        "CENTBK", "BOIIND", "PNBSMS", "CANBNK", "UNIONB",
        "KOTAKB", "INDUSB", "YESBNK","JUSPAY", "IDFCBK",
        "HDFCBANK", "ICICIBK", "SBIBANK", "AXISBANK", "KOTAK",
        "SBI-UPI", "HDFC-UPI", "ICICI-UPI", "AXIS-UPI",
        "UPIBNK", "UPIPAY", "BHIMPAY", "RAZORPAY"
        // Prefixes like AD-, TM-, VK-, AM- handled by contains logic in SmsBroadcastReceiver
    ).map { it.uppercase() }.toSet() // Store uppercase

    private fun getPreferences(context: Context): SharedPreferences {
        // Use application context to avoid leaks
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Core Data Access ---

    /** Gets the set of default senders defined in the code. */
    fun getDefaultSenders(): Set<String> = defaultSenders

    /** Gets only the user-approved senders from SharedPreferences (always returns uppercase). */
    private fun getUserAddedSenders(context: Context): Set<String> {
        val prefs = getPreferences(context)
        return prefs.getStringSet(KEY_APPROVED_SENDERS, emptySet())
            ?.mapNotNull { it?.uppercase() }?.toSet() ?: emptySet()
    }

    /** Gets the set of default senders that the user has explicitly disabled (always returns uppercase). */
    private fun getDisabledDefaultSenders(context: Context): Set<String> {
        val prefs = getPreferences(context)
        return prefs.getStringSet(KEY_DISABLED_DEFAULT_SENDERS, emptySet())
            ?.mapNotNull { it?.uppercase() }?.toSet() ?: emptySet()
    }

    /** Gets the set of senders currently considered active (enabled defaults + user-added). */
    fun getActiveSenders(context: Context): Set<String> {
        val userAdded = getUserAddedSenders(context)
        val disabledDefaults = getDisabledDefaultSenders(context)
        val enabledDefaults = defaultSenders - disabledDefaults // Set difference
        return enabledDefaults + userAdded // Set union
    }

    // --- Modifying Sender Lists ---

    /** Saves a set of newly approved senders, adding them to the existing approved set. */
    fun saveApprovedSenders(context: Context, newSenders: Set<String>) {
        if (newSenders.isEmpty()) return
        val uppercaseNewSenders = newSenders.mapNotNull { it?.uppercase() }.toSet()
        if (uppercaseNewSenders.isEmpty()) return

        val prefs = getPreferences(context)
        val currentApproved = getUserAddedSenders(context) // Already uppercase
        val updatedSenders = currentApproved + uppercaseNewSenders
        prefs.edit().putStringSet(KEY_APPROVED_SENDERS, updatedSenders).apply()
        Log.d(TAG, "Saved ${uppercaseNewSenders.size} new senders. Total approved: ${updatedSenders.size}")
    }

    /** Removes a specific sender from the user-approved list. Does not affect default or disabled lists. */
    fun removeSender(context: Context, senderToRemove: String) {
        val upperSender = senderToRemove.uppercase()
        val prefs = getPreferences(context)
        val currentApproved = getUserAddedSenders(context).toMutableSet() // Get mutable copy
        if (currentApproved.remove(upperSender)) { // Try removing uppercase version
            prefs.edit().putStringSet(KEY_APPROVED_SENDERS, currentApproved).apply()
            Log.d(TAG, "Removed sender: $upperSender. Total approved: ${currentApproved.size}")
        } else {
            Log.d(TAG, "Sender '$upperSender' not found in user-added list for removal.")
        }
    }

    /** Updates the status (enabled/disabled) of a sender. Handles both default and user-added senders. */
    fun updateSenderStatus(context: Context, sender: String, isEnabled: Boolean) {
        val upperSender = sender.uppercase()
        val prefs = getPreferences(context)
        val editor = prefs.edit()

        val userAdded = getUserAddedSenders(context).toMutableSet()
        val disabledDefaults = getDisabledDefaultSenders(context).toMutableSet()

        if (defaultSenders.contains(upperSender)) {
            // Handling a default sender
            if (isEnabled) { // Enabling a default sender
                if (disabledDefaults.remove(upperSender)) {
                    Log.d(TAG, "Re-enabling default sender: $upperSender")
                    editor.putStringSet(KEY_DISABLED_DEFAULT_SENDERS, disabledDefaults)
                }
            } else { // Disabling a default sender
                if (disabledDefaults.add(upperSender)) {
                    Log.d(TAG, "Disabling default sender: $upperSender")
                    editor.putStringSet(KEY_DISABLED_DEFAULT_SENDERS, disabledDefaults)
                }
            }
        } else {
            // Handling a user-added sender
            if (isEnabled) { // Enabling/Adding a user sender
                if(userAdded.add(upperSender)){
                    Log.d(TAG, "Enabling/Adding user sender: $upperSender")
                    editor.putStringSet(KEY_APPROVED_SENDERS, userAdded)
                }
            } else { // Disabling/Removing a user sender (same as removeSender)
                if(userAdded.remove(upperSender)){
                    Log.d(TAG, "Disabling/Removing user sender: $upperSender")
                    editor.putStringSet(KEY_APPROVED_SENDERS, userAdded)
                }
            }
        }
        editor.apply() // Apply changes
    }

    // --- List for Management UI ---

    data class SenderInfo(
        val name: String,
        val isEnabled: Boolean,
        val isDefault: Boolean // Flag to differentiate default vs user-added
    )

    /** Generates the list of all known senders (default + user-added) with their current status for the UI. */
    fun getManageableSenders(context: Context): List<SenderInfo> {
        val userAdded = getUserAddedSenders(context)
        val disabledDefaults = getDisabledDefaultSenders(context)
        val enabledDefaults = defaultSenders - disabledDefaults // Defaults that are currently active

        // Combine ALL defaults (enabled + disabled) with user-added for the full list view
        val allKnownSenders = (defaultSenders + userAdded).toList().sorted() // Sort for consistent display

        Log.d(TAG, "getManageableSenders: UserAdded(${userAdded.size}), DisabledDefaults(${disabledDefaults.size}), TotalDefaults(${defaultSenders.size})")
        Log.d(TAG, "getManageableSenders: Total items for UI: ${allKnownSenders.size}")

        return allKnownSenders.map { sender ->
            val isDefault = defaultSenders.contains(sender) // Check if it's in the original default list
            val isEnabled = if (isDefault) {
                !disabledDefaults.contains(sender) // Default is enabled if NOT in the disabled set
            } else {
                userAdded.contains(sender) // User-added is enabled if present in the user-added set
            }
            SenderInfo(name = sender, isEnabled = isEnabled, isDefault = isDefault)
        }
    }

    // --- Sender Scanning ---

    /** Scans SMS inbox for potential financial senders not already known (default or user-added). Requires READ_SMS permission. */
    suspend fun scanForPotentialSenders(context: Context): List<String> {
        Log.d(TAG, "Starting scan for potential new senders...")
        val appContext = context.applicationContext // Use application context

        if (appContext.checkSelfPermission(android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "READ_SMS permission missing for sender scan.")
            // Consider notifying the user through UI instead of just logging
            return emptyList()
        }

        val knownSendersUpper = (defaultSenders + getUserAddedSenders(appContext)) // All known senders uppercase

        val potentialNewSenders = mutableSetOf<String>()

        return withContext(Dispatchers.IO) {
            var cursor: android.database.Cursor? = null
            val uniqueSendersFromSms = mutableSetOf<String>()

            try {
                val projection = arrayOf("DISTINCT ${android.provider.Telephony.Sms.ADDRESS}")
                cursor = appContext.contentResolver.query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    projection, null, null, null
                )

                cursor?.use {
                    val addressIndex = it.getColumnIndex(android.provider.Telephony.Sms.ADDRESS)
                    if (addressIndex != -1) {
                        while (it.moveToNext()) {
                            val sender = it.getString(addressIndex)
                            // Basic filtering: not null, not empty, potentially exclude short codes or pure numbers if desired
                            if (!sender.isNullOrBlank() && sender.length > 3 && !sender.all { char -> char.isDigit() }) {
                                uniqueSendersFromSms.add(sender.uppercase().trim())
                            }
                        }
                    } else { Log.e(TAG, "SMS address column not found.") }
                } ?: Log.w(TAG, "SMS query cursor was null.")

                Log.d(TAG, "Found ${uniqueSendersFromSms.size} unique senders in SMS.")

                // Filter against known and apply heuristics
                for (sender in uniqueSendersFromSms) {
                    if (!knownSendersUpper.contains(sender) && (containsFinancialKeyword(sender) || looksLikeBankCode(sender))) {
                        potentialNewSenders.add(sender) // Add original case or uppercase? Add uppercase for consistency
                        Log.d(TAG, "Potential new sender identified: $sender")
                    }
                }

            } catch (e: SecurityException) { // Catch is redundant due to check at start, but good practice
                Log.e(TAG, "READ_SMS permission error during scan execution.", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error querying SMS for senders.", e)
            } finally {
                cursor?.close()
            }

            Log.d(TAG, "Scan complete. Found ${potentialNewSenders.size} potential new senders.")
            potentialNewSenders.toList().sorted() // Return sorted list
        }
    }

    // --- Heuristics (Keep private) ---
    private fun containsFinancialKeyword(sender: String): Boolean {
        val keywords = setOf("BANK", "PAY", "UPI", "CARD", "CREDIT", "DEBIT", "FIN", "WALLET", "BNK", "BK", "CRDT", "DBIT", "TXN", "TRXN", "AC", "ACCT", "ACCOUNT", "RS", "INR", "AMT", "AMOUNT", "BAL", "BALANCE")
        // Check if sender contains any keyword, ignoring case
        return keywords.any { sender.contains(it, ignoreCase = true) }
    }

    private fun looksLikeBankCode(sender: String): Boolean {
        // Requires common suffixes OR specific prefix patterns
        val knownSuffixes = listOf("BK", "BANK", "PAY", "UPI")
        val prefixPatterns = listOf(
            Regex("^[A-Z]{2}-[A-Z0-9]{3,}$"), // XX-YYYYY (e.g., VM-HDFCBK)
            Regex("^[A-Z]{2}_[A-Z0-9]{3,}$")  // XX_YYYYY
            // Add more known patterns if needed
        )
        return knownSuffixes.any { sender.endsWith(it, ignoreCase = true) } ||
                prefixPatterns.any { it.matches(sender) } ||
                sender.equals("PAYTM", ignoreCase = true) // Explicitly allow known ones
    }
}