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
import kotlinx.coroutines.launch

class SmsBroadcastReceiver : BroadcastReceiver() {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "SmsBroadcastReceiver"
        private const val TRANSACTION_CHANNEL_ID = "transaction_channel"
        private const val DETAILS_CHANNEL_ID = "details_channel"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.e(TAG, "Null context or intent")
            return
        }

        Log.d(TAG, "Received intent with action: ${intent.action}")

        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            messages?.forEach { smsMessage ->
                val sender = smsMessage.displayOriginatingAddress
                val messageBody = smsMessage.messageBody

                Log.d(TAG, "SMS from: $sender")
                Log.d(TAG, "Message: $messageBody")

                if (isFinancialMessage(sender)) {
                    Log.d(TAG, "Financial message detected")
                    createNotificationChannels(context)
                    processMessage(context, messageBody)
                }
            }
        }
    }

    private fun processMessage(context: Context, messageBody: String) {
        coroutineScope.launch {
            try {
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
                    Log.e(TAG, "Could not extract transaction details")
                    showFailureNotification(context, messageBody)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
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
        }
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

    private fun isFinancialMessage(sender: String): Boolean {
        val financialSenders = listOf(
            "SBIUPI", "SBI", "SBIPSG", "HDFCBK", "ICICI", "AXISBK", "PAYTM",
            "GPAY", "PHONEPE", "-SBIINB", "-HDFCBK", "-ICICI", "-AXISBK",
            "CENTBK", "BOIIND", "PNBSMS", "CANBNK", "UNIONB", "8301967659",
            "JUSPAY", "APAY"
        )
        return financialSenders.any { sender.contains(it, ignoreCase = true) }
    }
}