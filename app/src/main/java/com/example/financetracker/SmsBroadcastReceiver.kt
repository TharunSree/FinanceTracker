package com.example.financetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
// Import the SenderListManager
import com.example.financetracker.utils.SenderListManager

class SmsBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsBroadcastReceiver"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TIMESTAMP = "timestamp"

        // Keep currency patterns or move them if desired
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

        // REMOVE the hardcoded financialSenders list
        // private val financialSenders = listOf(...)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.e(TAG, "Null context or intent in onReceive")
            return
        }

        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            // *** Load the combined sender list HERE ***
            val currentActiveSenders = SenderListManager.getActiveSenders(context)
            Log.v(TAG, "Using ${currentActiveSenders.size} active senders for filtering.")



            messages?.forEach { smsMessage ->
                val sender = smsMessage.displayOriginatingAddress
                val messageBody = smsMessage.messageBody
                val timestamp = smsMessage.timestampMillis

                if (sender == null || messageBody == null) {
                    Log.w(TAG, "Received SMS with null sender or body.")
                    return@forEach
                }

                Log.d(TAG, "SMS received. From: $sender")

                // *** Pass the loaded sender list to the check ***
                if (isFinancialMessage(sender, messageBody, currentActiveSenders)) {
                    Log.i(TAG, "Financial message detected from sender: $sender. Starting service.")
                    val serviceIntent = Intent(context, SmsProcessingService::class.java).apply {
                        putExtra(EXTRA_SENDER, sender)
                        putExtra(EXTRA_MESSAGE, messageBody)
                        putExtra(EXTRA_TIMESTAMP, timestamp)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } else {
                    Log.d(TAG, "Not identified as a financial message from: $sender.")
                }
            }
        } else {
            Log.d(TAG, "Received broadcast with non-SMS action: ${intent.action}")
        }
    }

    // *** Modify isFinancialMessage to accept the sender list ***
    private fun isFinancialMessage(sender: String, message: String, activeSenders: Set<String>): Boolean {
        if (!senderMatches(sender, activeSenders)) { // Pass the list
            return false
        }
        if (!hasAmount(message)) {
            return false
        }
        if (!isDebitTransaction(message)) {
            return false
        }
        return true
    }

    // Modify senderMatches to use the provided Set
    private fun senderMatches(sender: String, activeSenders: Set<String>): Boolean {
        val normalizedSender = sender.uppercase().trim() // Normalize for comparison
        // Check if the normalized sender *contains* any of the allowed sender IDs
        // This handles prefixes like VK-, AM- etc.
        return activeSenders.any { knownSender ->
            normalizedSender.contains(knownSender)
        }
    }

    // hasAmount and isDebitTransaction remain the same as before
    private fun hasAmount(message: String): Boolean {
        return currencyPatterns.values.flatten().any { pattern ->
            pattern.find(message) != null
        }
    }
    private fun isDebitTransaction(message: String): Boolean {
        val debitKeywords = listOf(
            "debited", "debit", "spent", "paid", "withdrawn", "purchase",
            "payment", "transferred", "sent", "dr"
        )
        return debitKeywords.any { keyword -> message.contains(keyword, ignoreCase = true) }
    }
}