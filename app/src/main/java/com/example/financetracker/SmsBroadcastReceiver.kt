package com.example.financetracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
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

                if (isFinancialMessage(sender, messageBody)) {
                    Log.d(TAG, "Financial message detected from sender: $sender")

                    // Start the processing service
                    val serviceIntent = Intent(context, SmsProcessingService::class.java).apply {
                        putExtra("sender", sender)
                        putExtra("message", messageBody)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }

    // In SmsBroadcastReceiver.kt
    private fun processMessage(context: Context, messageBody: String) {
        coroutineScope.launch {
            try {
                Log.d(TAG, "Starting message processing with extractor")
                val messageExtractor = MessageExtractor(context)
                val details = messageExtractor.extractTransactionDetails(messageBody)

                if (details != null) {
                    Log.d(TAG, "Extracted details: $details")

                    val transaction = Transaction(
                        id = 0,
                        name = details.merchant.ifBlank { "Unknown Merchant" },
                        amount = details.amount,
                        date = details.date,
                        category = if (details.category == "Uncategorized") "" else details.category,
                        merchant = details.merchant,
                        description = details.description
                    )

                    // Save to Room database
                    val database = TransactionDatabase.getDatabase(context)
                    database.transactionDao().insertTransaction(transaction)
                    Log.d(TAG, "Transaction successfully added to the Room database")

                    // Save to Firestore
                    addTransactionToFirestore(transaction)

                    // Show appropriate notification
                    if (transaction.name == "Unknown Merchant" || transaction.category.isBlank()) {
                        showDetailsNeededNotification(context, transaction, messageBody)
                    } else {
                        showTransactionNotification(context, transaction)
                    }
                } else {
                    Log.e(TAG, "Could not extract transaction details from message: $messageBody")
                    showFailureNotification(context, messageBody)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
                e.printStackTrace()
                showFailureNotification(context, messageBody)
            }
        }
    }

    private fun addTransactionToFirestore(transaction: Transaction) {
        firestore.collection("transactions")
            .add(transaction)
            .addOnSuccessListener {
                Log.d(TAG, "Transaction successfully added to Firestore")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error adding transaction to Firestore: ${e.message}")
            }
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

    // Create a debug notification to help troubleshoot background processing
    private fun debugNotification(context: Context, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            NotificationChannel(
                "debug_channel",
                "Debug Information",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Debug notifications for app troubleshooting"
                notificationManager.createNotificationChannel(this)
            }
        }

        val notification = NotificationCompat.Builder(context, "debug_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS Debug")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun showDetailsNeededNotification(
        context: Context,
        transaction: Transaction,
        message: String
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_TRANSACTION_DIALOG", true)
            putExtra("TRANSACTION_MESSAGE", message)
            putExtra("TRANSACTION_AMOUNT", transaction.amount)
            putExtra("TRANSACTION_DATE", transaction.date)
            putExtra("TRANSACTION_MERCHANT", transaction.merchant)
            putExtra("TRANSACTION_DESCRIPTION", transaction.description)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, DETAILS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transaction Details Needed")
            .setContentText("₹${transaction.amount} - Tap to add details")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun showTransactionNotification(context: Context, transaction: Transaction) {
        val notification = NotificationCompat.Builder(context, TRANSACTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transaction Recorded")
            .setContentText("${transaction.name}: ₹${transaction.amount}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun showFailureNotification(context: Context, message: String) {
        val notification = NotificationCompat.Builder(context, DETAILS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transaction Processing Failed")
            .setContentText("Unable to process transaction automatically")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun isFinancialMessage(sender: String, message: String): Boolean {
        val bankKeywords = listOf("HDFCBK", "ICICI", "SBIINB", "AXISBK", "KOTAKB",
            "HDFCBANK", "ICICIBK", "SBIBANK", "AXISBANK", "KOTAK")

        return bankKeywords.any { sender.contains(it, ignoreCase = true) } &&
                message.contains(Regex("(?i)(debit|credit|spent|payment|transaction|transferred|paid)"))
    }

    private fun senderMatches(sender: String): Boolean {
        val financialSenders = listOf(
            "SBIUPI", "SBI", "SBIPSG", "HDFCBK", "ICICI", "AXISBK", "PAYTM",
            "GPAY", "PHONEPE", "-SBIINB", "-HDFCBK", "-ICICI", "-AXISBK",
            "CENTBK", "BOIIND", "PNBSMS", "CANBNK", "UNIONB",
            "KOTAKB", "INDUSB", "YESBNK"
        )

        return financialSenders.any { it.equals(sender, ignoreCase = true) }
    }
}