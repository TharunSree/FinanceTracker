package com.example.financetracker.utils

import android.content.Context
import android.util.Log
import com.example.financetracker.utils.ApiConfig
import com.example.financetracker.utils.GeminiMessageExtractor
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.nl.entityextraction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MessageExtractor(private val context: Context) {
    private val TAG = "MessageExtractor"

    private val entityExtractor = EntityExtraction.getClient(
        EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()
    )

    // Initialize Gemini extractor with API key
    private val geminiExtractor = GeminiMessageExtractor(context, ApiConfig.GEMINI_API_KEY)

    private val currencyPatterns = mapOf(
        "INR" to listOf(
            Regex("""(?:Rs\.?|INR|₹)\s*([\d,]+\.\d*)"""),
            Regex("""(?:INR|Rs\.?)\s*([\d,]+\.\d*)"""),
            Regex("""debited by\s*([\d,]+\.\d*)""", RegexOption.IGNORE_CASE),
            Regex("""Payment of Rs\s*([\d,]+\.\d*)""", RegexOption.IGNORE_CASE),
            Regex("""debited for INR\s*([\d,]+\.\d*)""", RegexOption.IGNORE_CASE)
        ),
        "USD" to listOf(Regex("""\$\s*([\d,]+\.\d*)""")),
        "EUR" to listOf(Regex("""€\s*([\d,]+\.\d*)""")),
        "GBP" to listOf(Regex("""£\s*([\d,]+\.\d*)"""))
    )

    private val merchantPatterns = listOf(
        Regex("""successful at\s+([A-Za-z0-9\s\-&@._]+?)(?:\.|$)""", RegexOption.IGNORE_CASE),
        Regex("""trf to\s+([A-Za-z0-9\s\-&@._]+?)(?:\s+Ref|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:at|to|paid to)\s+([A-Za-z0-9\s\-&@._]+?)(?:\s+(?:on|for|via|ref)|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:merchant|payee|receiver):\s*([A-Za-z0-9\s\-&@._]+?)(?:\s+|$)""", RegexOption.IGNORE_CASE)
    )

    private val datePatterns = listOf(
        SimpleDateFormat("ddMMMyy", Locale.ENGLISH),
        SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH),
        SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
    )

    // Main function that orchestrates extraction - now tries Gemini first
    suspend fun extractTransactionDetails(message: String): TransactionDetails? {
        Log.d(TAG, "Starting extraction for message: $message")
        try {
            // Try Gemini AI extraction first
            val geminiResult = geminiExtractor.extractTransactionDetails(message)
            if (geminiResult != null) {
                Log.d(TAG, "Successfully extracted with Gemini: $geminiResult")
                return geminiResult
            } else {
                Log.d(TAG, "Gemini extraction returned null, falling back to regex")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error with Gemini extraction, falling back to regex", e)
        }

        // Fall back to original ML Kit + regex extraction
        return extractWithRegexAndMlKit(message)
    }

    // Original function renamed to extract with regex and ML Kit
    private suspend fun extractWithRegexAndMlKit(message: String): TransactionDetails? =
        suspendCoroutine { continuation ->
            entityExtractor.downloadModelIfNeeded()
                .addOnSuccessListener {
                    entityExtractor.annotate(message)
                        .addOnSuccessListener { entityAnnotations ->
                            val details = processAnnotations(message, entityAnnotations)
                            continuation.resume(details)
                        }
                        .addOnFailureListener {
                            Log.e(TAG, "Entity annotation failed", it)
                            continuation.resume(null)
                        }
                }
                .addOnFailureListener {
                    Log.e(TAG, "Model download failed", it)
                    continuation.resume(null)
                }
        }

    private suspend fun getCategoryForMerchant(merchant: String, userId: String?): String {
        userId ?: return "Uncategorized"

        return try {
            withContext(Dispatchers.IO) {
                val merchantDoc = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(userId)
                    .collection("merchants")
                    .document(merchant)
                    .get()
                    .await()

                merchantDoc.getString("category") ?: "Uncategorized"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting category for merchant", e)
            "Uncategorized"
        }
    }

    private fun processAnnotations(
        message: String,
        entityAnnotations: List<EntityAnnotation>
    ): TransactionDetails? {
        // The existing implementation remains unchanged
        var amount: Double? = null
        var currency = "INR" // Default currency
        var merchant: String? = null
        var date: Long? = null
        var refNumber: String? = null
        var description: String? = null

        // Extract reference number
        val refPattern = Regex("""(?:Ref(?:no|erence)\s*(?:Number)?:?\s*)(\d+)""", RegexOption.IGNORE_CASE)
        refPattern.find(message)?.let {
            refNumber = it.groupValues[1]
        }

        // Extract amount using currency patterns
        for ((curr, patterns) in currencyPatterns) {
            for (pattern in patterns) {
                pattern.find(message)?.let {
                    amount = it.groupValues[1].replace(",", "").toDoubleOrNull()
                    currency = curr
                    return@let
                }
            }
            if (amount != null) break
        }

        // Extract date
        // First try to find explicit date markers
        val dateMarkerPattern = Regex("""(?:on date|dated)\s+(\d{2}[A-Za-z]{3}\d{2}|\d{2}[-/]\d{2}[-/]\d{2,4})""")
        var dateStr = dateMarkerPattern.find(message)?.groupValues?.get(1)

        // If no explicit date marker, try to find any date pattern
        if (dateStr == null) {
            val generalDatePattern = Regex("""(\d{2}[A-Za-z]{3}\d{2}|\d{2}[-/]\d{2}[-/]\d{2,4})""")
            dateStr = generalDatePattern.find(message)?.groupValues?.get(1)
        }

        // Try to parse the date string
        if (dateStr != null) {
            for (dateFormat in datePatterns) {
                try {
                    date = dateFormat.parse(dateStr)?.time
                    if (date != null) break
                } catch (e: Exception) {
                    continue
                }
            }
        }

        // Extract merchant
        for (pattern in merchantPatterns) {
            pattern.find(message)?.let {
                merchant = it.groupValues[1].trim()
                    .replace(Regex("""[@\s]+"""), " ")
                    .trim()
                return@let
            }
        }

        // Special handling for Apay messages
        if (message.contains("Apay", ignoreCase = true) && merchant == null) {
            merchant = "Apay Transaction"
        }

        // Extract description from the message
        if (message.contains("desc:", ignoreCase = true)) {
            val descPattern = Regex("""desc:\s*([A-Za-z0-9\s\-&@._]+)""", RegexOption.IGNORE_CASE)
            description = descPattern.find(message)?.groupValues?.get(1)?.trim()
        }

        // If we have at least an amount, create the transaction details
        return if (amount != null) {
            TransactionDetails(
                amount = amount!!,
                merchant = merchant ?: "Unknown Merchant",
                date = date ?: System.currentTimeMillis(),
                category = "Uncategorized",
                currency = currency,
                referenceNumber = refNumber,
                description = description ?: ""
            )
        } else null
    }
}

// Keep the TransactionDetails class as is
data class TransactionDetails(
    val amount: Double,
    val merchant: String,
    val date: Long,
    val category: String,
    val currency: String,
    val referenceNumber: String? = null,
    val description: String = ""
)