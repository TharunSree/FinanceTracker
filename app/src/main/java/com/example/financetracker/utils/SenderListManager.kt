package com.example.financetracker.utils // Or your preferred package

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay // Import delay
import kotlinx.coroutines.withContext
// Removed Pattern import as Regex is not used here anymore for heuristics

object SenderListManager {

    private const val PREFS_NAME = "FinancialSendersPrefs"
    private const val KEY_APPROVED_SENDERS = "approved_senders"
    private const val KEY_DISABLED_DEFAULT_SENDERS = "disabled_default_senders"
    private const val TAG = "SenderListManager"

    // Base default senders (normalized to uppercase) - KEEP THIS UPDATED
    private val defaultSenders = setOf(
        "HDFCBK", "ICICIB", "AXISBK", "SBIBNK", "KOTAKB", "PNBSMS",
        "CANBNK", "UNIONB", "BOIIND", "CENTBK", "IDFCBK", "INDUSB", "YESBNK",
        "PAYTM", "GPAY", "PHONEPE", "BHIM", "MOBIKWIK", "AMZPAY", "AIRBNK",
        "SBIUPI", "SBIPSG", "SBIINB",
        "JUSPAY", "RAZORPAY", "BILLDESK", "PAYU",
        "MYAMEX", "CITIBK"
    ).map { it.uppercase() }.toSet()

    private fun getPreferences(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Core Data Access (Unchanged) ---
    fun getDefaultSenders(): Set<String> = defaultSenders
    private fun getUserAddedSenders(context: Context): Set<String> = getPreferences(context).getStringSet(KEY_APPROVED_SENDERS, emptySet())?.mapNotNull { it?.uppercase() }?.toSet() ?: emptySet()
    private fun getDisabledDefaultSenders(context: Context): Set<String> = getPreferences(context).getStringSet(KEY_DISABLED_DEFAULT_SENDERS, emptySet())?.mapNotNull { it?.uppercase() }?.toSet() ?: emptySet()
    fun getActiveSenders(context: Context): Set<String> = (defaultSenders - getDisabledDefaultSenders(context)) + getUserAddedSenders(context)

    // --- Modifying Sender Lists (Unchanged - Ensure Trim is included) ---
    fun saveApprovedSenders(context: Context, newSenders: Set<String>) {
        if (newSenders.isEmpty()) return
        val uppercaseNewSenders = newSenders.mapNotNull { it?.uppercase()?.trim() }.filter { it.isNotEmpty() }.toSet()
        if (uppercaseNewSenders.isEmpty()) return
        val prefs = getPreferences(context)
        val currentApproved = getUserAddedSenders(context)
        val updatedSenders = currentApproved + uppercaseNewSenders
        prefs.edit().putStringSet(KEY_APPROVED_SENDERS, updatedSenders).apply()
        Log.d(TAG, "Saved ${uppercaseNewSenders.size} new senders. Total approved: ${updatedSenders.size}")
    }

    fun removeSender(context: Context, senderToRemove: String) {
        val upperSender = senderToRemove.uppercase().trim()
        if (upperSender.isEmpty()) return
        val prefs = getPreferences(context)
        val currentApproved = getUserAddedSenders(context).toMutableSet()
        if (currentApproved.remove(upperSender)) {
            prefs.edit().putStringSet(KEY_APPROVED_SENDERS, currentApproved).apply()
            Log.d(TAG, "Removed sender: $upperSender. Total approved: ${currentApproved.size}")
        } else {
            Log.d(TAG, "Sender '$upperSender' not found for removal.")
        }
    }

    fun updateSenderStatus(context: Context, sender: String, isEnabled: Boolean) {
        val upperSender = sender.uppercase().trim()
        if (upperSender.isEmpty()) return
        val prefs = getPreferences(context); val editor = prefs.edit()
        val userAdded = getUserAddedSenders(context).toMutableSet()
        val disabledDefaults = getDisabledDefaultSenders(context).toMutableSet()
        if (defaultSenders.contains(upperSender)) {
            if (isEnabled) { if (disabledDefaults.remove(upperSender)) editor.putStringSet(KEY_DISABLED_DEFAULT_SENDERS, disabledDefaults) }
            else { if (disabledDefaults.add(upperSender)) editor.putStringSet(KEY_DISABLED_DEFAULT_SENDERS, disabledDefaults) }
        } else {
            if (isEnabled) { if (userAdded.add(upperSender)) editor.putStringSet(KEY_APPROVED_SENDERS, userAdded) }
            else { if (userAdded.remove(upperSender)) editor.putStringSet(KEY_APPROVED_SENDERS, userAdded) }
        }
        editor.apply()
    }

    // --- List for Management UI (Unchanged) ---
    data class SenderInfo(val name: String, val isEnabled: Boolean, val isDefault: Boolean)
    fun getManageableSenders(context: Context): List<SenderInfo> {
        val userAdded = getUserAddedSenders(context); val disabledDefaults = getDisabledDefaultSenders(context)
        val allKnownSenders = (defaultSenders + userAdded).toList().sorted()
        Log.d(TAG, "getManageableSenders: UserAdded(${userAdded.size}), DisabledDefaults(${disabledDefaults.size}), TotalDefaults(${defaultSenders.size}), TotalItems(${allKnownSenders.size})")
        return allKnownSenders.map { sender ->
            val isDefault = defaultSenders.contains(sender); val isEnabled = if (isDefault) !disabledDefaults.contains(sender) else userAdded.contains(sender)
            SenderInfo(name = sender, isEnabled = isEnabled, isDefault = isDefault)
        }
    }

    // --- Sender Scanning (Using Gemini Classification) ---

    // Inside SenderListManager.kt

    // --- Sender Scanning (Using Gemini Batch Classification) ---
    suspend fun scanForPotentialSenders(context: Context): List<String> {
        Log.d(TAG, "Starting BATCH scan for potential new senders using Gemini...")
        val appContext = context.applicationContext

        if (appContext.checkSelfPermission(android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "READ_SMS permission missing for sender scan.")
            return emptyList()
        }

        val knownSendersUpper = (defaultSenders + getUserAddedSenders(appContext))
        val unknownSenders = mutableListOf<String>() // List to hold unknowns

        // --- Stage 1: Collect all unique, unknown senders ---
        withContext(Dispatchers.IO) {
            var cursor: android.database.Cursor? = null
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
                            if (!sender.isNullOrBlank() && sender.length >= 5 && !sender.all { c -> c.isDigit() || c == '+' }) {
                                val upperSender = sender.uppercase().trim()
                                if (upperSender.isNotEmpty() && !knownSendersUpper.contains(upperSender)) {
                                    unknownSenders.add(upperSender) // Add unique, unknown sender
                                }
                            }
                        }
                    }
                }
                Log.d(TAG, "Collected ${unknownSenders.size} unique unknown senders for batch classification.")
            } catch (e: Exception) {
                Log.e(TAG, "Error querying SMS for senders.", e)
                // Allow proceeding with an empty list if query fails
            } finally {
                cursor?.close()
            }
        } // End Stage 1

        // --- Stage 2: Classify unknown senders in a batch ---
        val potentialNewSenders = mutableSetOf<String>()
        if (unknownSenders.isNotEmpty()) {
            try {
                // Get Gemini Extractor instance
                val geminiExtractor = GeminiMessageExtractor(appContext)

                // *** Call the NEW batch classification function ***
                // Consider chunking if unknownSenders is very large (e.g., > 200)
                // val chunkSize = 100
                // unknownSenders.chunked(chunkSize).forEach { chunk -> ... call classifySenderIdBatch(chunk) ... }
                val financialSendersFromBatch = geminiExtractor.classifySenderIdBatch(unknownSenders)

                potentialNewSenders.addAll(financialSendersFromBatch)
                Log.d(TAG, "Gemini batch classification identified ${financialSendersFromBatch.size} potential senders.")

            } catch (e: Exception) {
                Log.e(TAG, "Error during Gemini batch classification", e)
                // Handle error - maybe log, show message, return empty list?
            }
        } else {
            Log.d(TAG, "No unknown senders to classify.")
        }

        Log.d(TAG, "Scan complete. Found ${potentialNewSenders.size} potential new senders (batch method).")
        return potentialNewSenders.toList().sorted() // Return sorted list
    } // End scanForPotentialSenders



    // --- REMOVED Heuristic Functions ---
    // private fun containsFinancialKeyword(sender: String): Boolean { ... }
    // private fun looksLikeBankCode(sender: String): Boolean { ... }

} // End object SenderListManager