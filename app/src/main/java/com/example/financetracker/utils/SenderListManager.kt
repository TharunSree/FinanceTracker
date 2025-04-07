package com.example.financetracker.utils // Or your preferred package

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SenderListManager {

    private const val PREFS_NAME = "FinancialSendersPrefs"
    private const val KEY_APPROVED_SENDERS = "approved_senders"
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
    fun getCombinedSenders(context: Context): Set<String> {
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

    // Example Heuristic Functions (can be expanded)
    private fun containsFinancialKeyword(sender: String): Boolean {
        val keywords = listOf("BANK", "PAY", "UPI", "CARD", "CREDIT", "DEBIT", "FIN", "WALLET", "BNK", "BK")
        return keywords.any { sender.contains(it, ignoreCase = true) }
    }

    private fun looksLikeBankCode(sender: String): Boolean {
        // Simple check for common patterns like XX-YYYYYY or YYYYYY (often alphanumeric)
        return sender.matches(Regex("""^[A-Z0-9]{2,}-[A-Z0-9]{3,}$""")) || // e.g., VK-HDFCBK
                sender.matches(Regex("""^[A-Z]{4,}BK$""")) || // e.g., AXISBK
                sender.matches(Regex("""^[A-Z]{3,}[0-9]*$""")) // e.g., SBIUPI, PAYTM
    }
}