package com.example.financetracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.utils.MessageExtractor
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsBroadcastReceiver : BroadcastReceiver() {

    // Use SupervisorJob to prevent cancellation of all coroutines if one fails
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "SmsBroadcastReceiver"
        private const val TRANSACTION_CHANNEL_ID = "transaction_channel"
        private const val DETAILS_CHANNEL_ID = "details_channel"

        // Add a static property to track receiver initialization
        var isInitialized = false

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
        )

        private val financialSenders = listOf(
            // Main bank senders
            "SBIUPI", "SBI", "SBIPSG", "HDFCBK", "ICICI", "AXISBK", "PAYTM",
            "GPAY", "PHONEPE", "-SBIINB", "-HDFCBK", "-ICICI", "-AXISBK",
            "CENTBK", "BOIIND", "PNBSMS", "CANBNK", "UNIONB",
            "KOTAKB", "INDUSB", "YESBNK",

            // Additional variations
            "HDFCBANK", "ICICIBK", "SBIBANK", "AXISBANK", "KOTAK",
            "SBI-UPI", "HDFC-UPI", "ICICI-UPI", "AXIS-UPI",
            "VK-HDFCBK", "VM-HDFCBK", "VK-ICICI", "VM-ICICI",
            "VK-SBIINB", "VM-SBIINB", "BZ-HDFCBK", "BZ-ICICI",

            // UPI services
            "UPIBNK", "UPIPAY", "BHIMPAY", "RAZORPAY",

            // Common variations with different prefixes
            "AD-", "TM-", "DM-", "BZ-", "VM-", "VK-"
        )
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.e(TAG, "Null context or intent")
            return
        }

        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            messages?.forEach { smsMessage ->
                val sender = smsMessage.displayOriginatingAddress
                val messageBody = smsMessage.messageBody

                Log.d(TAG, "SMS from: $sender")
                Log.d(TAG, "Message body: $messageBody")

                if (isFinancialMessage(sender, messageBody)) {
                    Log.d(TAG, "Financial message detected from sender: $sender")
                    createNotificationChannels(context)

                    // Only start the service, remove direct processing
                    val serviceIntent = Intent(context, SmsProcessingService::class.java).apply {
                        putExtra("sender", sender)
                        putExtra("message", messageBody)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } else {
                    Log.d(TAG, "Not a financial message from: $sender")
                }
            }
        }
    }

    private fun isFinancialMessage(sender: String, message: String): Boolean {
        // Check if the sender is a known financial sender
        if (!senderMatches(sender)) {
            Log.d(TAG, "Sender does not match financial senders: $sender")
            return false
        }

        // Check if message contains amount pattern
        if (!hasAmount(message)) {
            Log.d(TAG, "Message does not contain amount pattern")
            return false
        }

        // Check if message contains debit keywords
        if (!isDebitTransaction(message)) {
            Log.d(TAG, "Message does not contain debit keywords")
            return false
        }

        Log.d(TAG, "Message identified as financial message")
        return true
    }

    private fun hasAmount(message: String): Boolean {
        val hasAmount = currencyPatterns.values.flatten().any { pattern ->
            pattern.find(message) != null
        }
        Log.d(TAG, "Message has amount: $hasAmount")
        return hasAmount
    }

    private fun isDebitTransaction(message: String): Boolean {
        val debitKeywords = listOf(
            "debited",
            "debit",
            "spent",
            "paid",
            "withdrawn",
            "purchase",
            "payment",
            "transferred",
            "transaction"
        )

        val isDebitTransaction = debitKeywords.any { keyword ->
            message.contains(keyword, ignoreCase = true)
        }
        Log.d(TAG, "Message is debit transaction: $isDebitTransaction")
        return isDebitTransaction
    }

    private fun senderMatches(sender: String): Boolean {
        val matched = financialSenders.any {
            sender.contains(it, ignoreCase = true) ||
                    it.startsWith(sender, ignoreCase = true)
        }

        Log.d(TAG, "Sender check: $sender -> $matched")
        return matched
    }

    private fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Transaction channel
            NotificationChannel(
                TRANSACTION_CHANNEL_ID,
                "Transactions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Transaction notifications"
                enableLights(true)
                enableVibration(true)
                notificationManager.createNotificationChannel(this)
            }

            // Details needed channel
            NotificationChannel(
                DETAILS_CHANNEL_ID,
                "Details Required",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications requiring user input"
                enableLights(true)
                enableVibration(true)
                notificationManager.createNotificationChannel(this)
            }

            // Debug channel
            NotificationChannel(
                "debug_channel",
                "Debug Information",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Debug notifications for app troubleshooting"
                notificationManager.createNotificationChannel(this)
            }
        }
    }
}