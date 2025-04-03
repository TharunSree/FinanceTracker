package com.example.financetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
// Removed unused imports: NotificationChannel, NotificationManager, PendingIntent,
// SmsMessage, CoroutineScope, Dispatchers, SupervisorJob, FirebaseFirestore, etc.
// Removed database and entity imports as they are not used here.

class SmsBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsBroadcastReceiver"
        // Moved channel IDs to Service

        // Define constants for intent extras (matching SmsProcessingService)
        // Using strings as provided in the input code, but constants are recommended
        const val EXTRA_SENDER = "sender"
        const val EXTRA_MESSAGE = "message"
        // const val EXTRA_TIMESTAMP = "timestamp" // Timestamp is no longer passed

        // Regex patterns for filtering
        private val currencyPatterns = mapOf(
            "INR" to listOf(
                Regex("""(?:Rs\.?|INR|₹)\s*([\d,]+\.?\d*)"""),
                Regex("""(?:INR|Rs\.?)\s*([\d,]+\.?\d*)"""),
                Regex("""debited by\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE),
                Regex("""Payment of Rs\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE),
                Regex("""debited for INR\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
            ),
            "USD" to listOf(Regex("""\$\s*([\d,]+\.?\d*)""")),
            "EUR" to listOf(Regex("""€\s*([\d,]+\.?\d*)""")),
            "GBP" to listOf(Regex("""£\s*([\d,]+\.?\d*)"""))
            // Add other currencies as needed
        )

        // List of known financial sender IDs or keywords
        private val financialSenders = listOf(
            // Main bank senders
            "SBIUPI", "SBI", "SBIPSG", "HDFCBK", "ICICI", "AXISBK", "PAYTM",
            "GPAY", "PHONEPE", "-SBIINB", "-HDFCBK", "-ICICI", "-AXISBK",
            "CENTBK", "BOIIND", "PNBSMS", "CANBNK", "UNIONB",
            "KOTAKB", "INDUSB", "YESBNK","JUSPAY", "IDFCBK",

            // Additional variations
            "HDFCBANK", "ICICIBK", "SBIBANK", "AXISBANK", "KOTAK",
            "SBI-UPI", "HDFC-UPI", "ICICI-UPI", "AXIS-UPI",
            "VK-HDFCBK", "VM-HDFCBK", "VK-ICICI", "VM-ICICI",
            "VK-SBIINB", "VM-SBIINB", "BZ-HDFCBK", "BZ-ICICI",

            // UPI services
            "UPIBNK", "UPIPAY", "BHIMPAY", "RAZORPAY",

            // Common variations with different prefixes (handle with contains)
            "AD-", "TM-", "DM-", "BZ-", "VM-", "VK-" // Prefixes best handled by 'contains' in senderMatches
        )
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.e(TAG, "Null context or intent in onReceive")
            return
        }

        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            messages?.forEach { smsMessage ->
                // Use displayOriginatingAddress for sender ID
                val sender = smsMessage.displayOriginatingAddress
                val messageBody = smsMessage.messageBody
                // val timestamp = smsMessage.timestampMillis // Timestamp no longer passed

                if (sender == null || messageBody == null) {
                    Log.w(TAG, "Received SMS with null sender or body.")
                    return@forEach // Skip this message
                }

                Log.d(TAG, "SMS received. From: $sender")
                // Log.v(TAG, "Message body: $messageBody") // Use verbose for full body

                // Check if it's a financial message based on sender and content
                if (isFinancialMessage(sender, messageBody)) {
                    Log.i(TAG, "Financial message detected from sender: $sender. Starting service.")
                    // Removed notification channel creation from receiver

                    // Start the SmsProcessingService
                    val serviceIntent = Intent(context, SmsProcessingService::class.java).apply {
                        // Use the defined constants (or matching strings) for extras
                        putExtra(EXTRA_SENDER, sender)
                        putExtra(EXTRA_MESSAGE, messageBody)
                        // putExtra(EXTRA_TIMESTAMP, timestamp) // Not passing timestamp anymore
                    }

                    // Start service appropriately based on Android version
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } else {
                    Log.d(TAG, "Not identified as a financial message from: $sender. Skipping service start.")
                }
            }
        } else {
            Log.d(TAG, "Received broadcast with non-SMS action: ${intent.action}")
        }
    }

    // Checks if the message likely represents a financial transaction.
    private fun isFinancialMessage(sender: String, message: String): Boolean {
        // Check 1: Sender must match known financial institutions/services
        if (!senderMatches(sender)) {
            // Log.d(TAG, "Sender '$sender' does not match known financial senders.")
            return false
        }

        // Check 2: Message must contain a recognizable amount pattern
        if (!hasAmount(message)) {
            // Log.d(TAG, "Message does not contain a recognizable amount pattern.")
            return false
        }

        // Check 3: Message should contain keywords indicating a transaction (debit focus)
        if (!isDebitTransaction(message)) {
            // Log.d(TAG, "Message does not contain debit-related keywords.")
            return false
        }

        Log.d(TAG, "Message from '$sender' identified as financial.")
        return true // Passed all checks
    }

    // Checks if the message contains any of the defined currency/amount patterns.
    private fun hasAmount(message: String): Boolean {
        return currencyPatterns.values.flatten().any { pattern ->
            pattern.find(message) != null
        }
        // Log.d(TAG, "Amount check result: $hasAmount")
        // return hasAmount // No need for intermediate variable
    }

    // Checks if the message contains common keywords associated with debit transactions.
    private fun isDebitTransaction(message: String): Boolean {
        // Keywords indicating a debit or general transaction
        val debitKeywords = listOf(
            "debited", "debit", "spent", "paid", "withdrawn", "purchase",
            "payment", "transferred", "transaction", "sent", "dr" // Added 'sent', 'dr'
        )
        return debitKeywords.any { keyword -> message.contains(keyword, ignoreCase = true) }
        // Log.d(TAG, "Debit keyword check result: $isDebit")
        // return isDebit // No need for intermediate variable
    }

    // Checks if the sender address contains any of the known financial sender IDs.
    private fun senderMatches(sender: String): Boolean {
        // Normalize sender? (e.g., remove country code prefix if present) - Optional
        val normalizedSender = sender // .replace("+91", "") // Example normalization

        return financialSenders.any { knownSender ->
            // Check if the normalized sender contains the known ID (e.g., "HDFCBK" in "VM-HDFCBK")
            // Also check exact match for IDs that don't usually have prefixes (e.g., "PAYTM")
            normalizedSender.contains(knownSender, ignoreCase = true)
        }
        // Log.d(TAG, "Sender '$normalizedSender' match result: $matched")
        // return matched // No need for intermediate variable
    }
}
