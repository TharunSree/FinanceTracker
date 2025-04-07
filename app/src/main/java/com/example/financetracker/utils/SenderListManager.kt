package com.example.financetracker.utils // Or your preferred package

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SenderListManager {

    private const val PREFS_NAME = "FinancialSendersPrefs"
    private const val KEY_APPROVED_SENDERS = "approved_senders"
    private const val KEY_DISABLED_DEFAULT_SENDERS = "disabled_default_senders"
    private const val TAG = "SenderListManager"

    // Base default senders (can be expanded)
    private val defaultSenders = setOf(
        // Copied from your SmsBroadcastReceiver - keep these as a base
        "SBIUPI", "SBI", "SBIPSG", "HDFCBK", "ICICI", "AXISBK", "PAYTM",
        "GPAY", "PHONEPE", "-SBIINB", "-HDFCBK", "-ICICI", "-AXISBK",
        "CENTBK", "BOIIND", "PNBSMS", "CANBNK", "UNIONB",
        "KOTAKB", "INDUSB", "YESBNK","JUSPAY", "IDFCBK",
        // Additional variations (consider if needed, matching logic handles prefixes)
        "HDFCBANK", "ICICIBK", "SBIBANK", "AXISBANK", "KOTAK",
        "SBI-UPI", "HDFC-UPI", "ICICI-UPI", "AXIS-UPI",
        "UPIBNK", "UPIPAY", "BHIMPAY", "RAZORPAY"
        // Prefixes like AD-, TM- etc. are better handled by 'contains' logic
    )

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Core Functions ---

    /**
     * Gets the combined set of default and user-approved senders.
     */
    private fun getCombinedSenders(context: Context): Set<String> {
        val approvedSenders = getApprovedSenders(context)
        // Combine defaults and approved, ensuring case-insensitivity for comparison later
        return (defaultSenders + approvedSenders).map { it.uppercase() }.toSet()
    }

    /**
     * Gets only the user-approved senders from SharedPreferences.
     */
    private fun getApprovedSenders(context: Context): Set<String> {
        val prefs = getPreferences(context)
        // Return an empty set if not found
        return prefs.getStringSet(KEY_APPROVED_SENDERS, emptySet()) ?: emptySet()
    }

    /**
     * Saves a set of newly approved senders, adding them to the existing set.
     */
    fun saveApprovedSenders(context: Context, newSenders: Set<String>) {
        if (newSenders.isEmpty()) return

        val prefs = getPreferences(context)
        val currentApproved = getApprovedSenders(context)
        val updatedSenders = currentApproved + newSenders // Add new ones to existing
        prefs.edit().putStringSet(KEY_APPROVED_SENDERS, updatedSenders).apply()
        Log.d(TAG, "Saved ${newSenders.size} new senders. Total approved: ${updatedSenders.size}")
    }

    fun getActiveSenders(context: Context): Set<String> {
        val userAdded = getUserAddedSenders(context)
        val disabledDefaults = getDisabledDefaultSenders(context)
        val enabledDefaults = defaultSenders - disabledDefaults
        return enabledDefaults + userAdded
    }

    /**
     * Removes a sender from the user-approved list.
     * Note: Default senders cannot be removed this way.
     */
    fun removeSender(context: Context, senderToRemove: String) {
        val prefs = getPreferences(context)
        val currentApproved = getApprovedSenders(context).toMutableSet()
        if (currentApproved.remove(senderToRemove)) {
            prefs.edit().putStringSet(KEY_APPROVED_SENDERS, currentApproved).apply()
            Log.d(TAG, "Removed sender: $senderToRemove. Total approved: ${currentApproved.size}")
        } else {
            Log.d(TAG, "Sender not found in approved list: $senderToRemove")
        }
    }

    // --- Sender Scanning Logic Outline ---

    /**
     * Scans SMS inbox for potential financial senders not already approved.
     * NOTE: This requires READ_SMS permission.
     * NOTE: Implement the actual ContentResolver query logic.
     * @return List of potential new sender IDs (strings).
     */
    suspend fun scanForPotentialSenders(context: Context): List<String> {
        Log.d(TAG, "Starting scan for potential new senders...")
        val currentSendersUpper = getCombinedSenders(context) // Get existing for comparison
        val potentialNewSenders = mutableListOf<String>()

        // !!! --- Placeholder for ContentResolver Query --- !!!
        // !!! This needs proper implementation with permissions and background thread !!!
        withContext(Dispatchers.IO) {
            try {
                // 1. Query ContentResolver for distinct senders ("address" column)
                //    val cursor = context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.Inbox.ADDRESS), null, null, null)
                // 2. Iterate through the cursor
                //    cursor?.use {
                //        val addressIndex = it.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
                //        while (it.moveToNext()) {
                //            val sender = it.getString(addressIndex)
                //            if (sender != null && sender.isNotBlank()) {
                //                // Process unique sender addresses
                //            }
                //        }
                //    }
                // --- FAKE DATA for demonstration ---
                val allSendersFromSms = listOf("VK-HDFCBK", "AM-PAYTM", "INFO-MSG", "MyBank-OTP", "AXISBK", "NEWFINCO")
                Log.d(TAG, "Found ${allSendersFromSms.size} unique senders in SMS (Example Data)")
                // ----------------------------------


                val uniqueSenders = allSendersFromSms.mapNotNull { it?.uppercase()?.trim() }.toSet()

                for (sender in uniqueSenders) {
                    // Check if sender is already known (case-insensitive)
                    if (sender !in currentSendersUpper && sender.length > 3) { // Basic check: not known and length > 3
                        // Apply heuristics (simple example)
                        if (containsFinancialKeyword(sender) || looksLikeBankCode(sender)) {
                            if (!potentialNewSenders.contains(sender)) { // Add only if not already in potential list
                                potentialNewSenders.add(sender)
                                Log.d(TAG, "Potential new sender identified: $sender")
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "READ_SMS permission missing for sender scan.", e)
                // Optionally inform the user via a different mechanism (e.g., LiveData in ViewModel)
            } catch (e: Exception) {
                Log.e(TAG, "Error querying SMS for senders.", e)
            }
        }


        // !!! --- End Placeholder --- !!!

        Log.d(TAG, "Scan complete. Found ${potentialNewSenders.size} potential new senders.")
        return potentialNewSenders.distinct() // Return unique list
    }

    fun updateSenderStatus(context: Context, sender: String, isEnabled: Boolean) {
        val upperSender = sender.uppercase()
        val prefs = getPreferences(context)
        val editor = prefs.edit()

        val userAdded = getUserAddedSenders(context).toMutableSet()
        val disabledDefaults = getDisabledDefaultSenders(context).toMutableSet()

        if (defaultSenders.contains(upperSender)) {
            // It's a default sender
            if (isEnabled) {
                // User is enabling a default sender (removing from disabled list)
                if (disabledDefaults.remove(upperSender)) {
                    Log.d(TAG, "Re-enabling default sender: $upperSender")
                }
            } else {
                // User is disabling a default sender (adding to disabled list)
                if (disabledDefaults.add(upperSender)) {
                    Log.d(TAG, "Disabling default sender: $upperSender")
                }
            }
            editor.putStringSet(KEY_DISABLED_DEFAULT_SENDERS, disabledDefaults)
        } else {
            // It's a user-added sender
            if (isEnabled) {
                // User is enabling (or re-adding) a custom sender
                if(userAdded.add(upperSender)){
                    Log.d(TAG, "Enabling/Adding user sender: $upperSender")
                }
            } else {
                // User is disabling (removing) a custom sender
                if(userAdded.remove(upperSender)){
                    Log.d(TAG, "Disabling/Removing user sender: $upperSender")
                }
            }
            editor.putStringSet(KEY_APPROVED_SENDERS, userAdded)
        }
        editor.apply() // Apply changes
    }

    // Example Heuristic Functions (can be expanded)
    private fun containsFinancialKeyword(sender: String): Boolean {
        val keywords = listOf("BANK", "PAY", "UPI", "CARD", "CREDIT", "DEBIT", "FIN", "WALLET", "BNK", "BK")
        return keywords.any { sender.contains(it, ignoreCase = true) }
    }

    data class SenderInfo(
        val name: String,
        val isEnabled: Boolean,
        val isDefault: Boolean // Flag to differentiate default vs user-added
    )

    private fun getUserAddedSenders(context: Context): Set<String> {
        val prefs = getPreferences(context)
        return prefs.getStringSet(KEY_APPROVED_SENDERS, emptySet())?.map { it.uppercase() }?.toSet() ?: emptySet()
    }

    /**
     * Gets the set of default senders that the user has explicitly disabled.
     */
    private fun getDisabledDefaultSenders(context: Context): Set<String> {
        val prefs = getPreferences(context)
        return prefs.getStringSet(KEY_DISABLED_DEFAULT_SENDERS, emptySet())?.map { it.uppercase() }?.toSet() ?: emptySet()
    }

    fun getManageableSenders(context: Context): List<SenderInfo> {
        val userAdded = getUserAddedSenders(context)
        val disabledDefaults = getDisabledDefaultSenders(context)

        val allKnownSenders = (defaultSenders + userAdded).sorted()

        return allKnownSenders.map { sender ->
            val isDefault = defaultSenders.contains(sender)
            val isEnabled = if (isDefault) {
                !disabledDefaults.contains(sender) // Enabled if NOT in the disabled set
            } else {
                userAdded.contains(sender) // Enabled if present in the user-added set (should always be true here)
            }
            SenderInfo(name = sender, isEnabled = isEnabled, isDefault = isDefault)
        }
    }

    private fun looksLikeBankCode(sender: String): Boolean {
        // Simple check for common patterns like XX-YYYYYY or YYYYYY (often alphanumeric)
        return sender.matches(Regex("""^[A-Z0-9]{2,}-[A-Z0-9]{3,}$""")) || // e.g., VK-HDFCBK
                sender.matches(Regex("""^[A-Z]{4,}BK$""")) || // e.g., AXISBK
                sender.matches(Regex("""^[A-Z]{3,}[0-9]*$""")) // e.g., SBIUPI, PAYTM
    }
}