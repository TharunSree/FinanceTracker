package com.example.financetracker.utils

import android.content.Context
import android.util.Log
import com.example.financetracker.model.TransactionDetails // Import the canonical TransactionDetails
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.nl.entityextraction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// Class responsible for extracting transaction details from SMS.
// Attempts extraction using Gemini first, then falls back to ML Kit + Regex.
class MessageExtractor(private val context: Context) {
    private val TAG = "MessageExtractor"

    // ML Kit Entity Extractor for fallback method
    private val entityExtractor = EntityExtraction.getClient(
        EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()
    )

    // Initialize Gemini extractor - reads API key internally
    // Assumes GeminiMessageExtractor is in the same package or imported correctly
    private val geminiExtractor = GeminiMessageExtractor(context)

    // Regex patterns for fallback extraction
    private val currencyPatterns = mapOf(
        "INR" to listOf(
            Regex("""(?:Rs\.?|INR|₹)\s*([\d,]+\.?\d*)"""), // Made decimal optional for amounts like Rs 5,000
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
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
        SimpleDateFormat("dd MMM yy", Locale.ENGLISH), // Added space format
        SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH) // Added full year format
    )

    // Main function that orchestrates extraction - tries Gemini first
    // Returns TransactionDetails or null if extraction fails.
    // Note: Loses the 'type' ("Income"/"Expense") information if Gemini succeeds.
    /*suspend fun extractTransactionDetails(message: String): TransactionDetails? {
        Log.d(TAG, "Starting extraction (NO SMS date used internally): $message")

        try {
            // Try Gemini AI extraction first
            // Note: Gemini extractor itself doesn't need the timestamp passed if its prompt
            // correctly handles defaulting internally (e.g., "use today's date").
            // However, we will use the smsTimestamp if Gemini *fails* to return a usable date (returns 0L).
            val result = geminiExtractor.extractTransactionDetails(message, sender) // <<< PASS 'sender' HERE
            val details: TransactionDetails? = result.first
            val type: String? = result.second // Gemini call

            if (details != null) {
                // We know date will be 0L here, caller will use SMS timestamp
                Log.i(TAG, "Using Gemini result (date ignored): $details")
                return details
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during Gemini extraction, falling back to regex/MLKit", e)
        }

        // --- Fallback to Regex/MLKit ---
        Log.d(TAG, "Attempting fallback extraction with Regex/MLKit.")
        // *** Pass smsTimestamp to the internal fallback method ***
        return extractWithRegexAndMlKitInternal(message)
    }*/

    // Renamed internal function for fallback using ML Kit and Regex
    private suspend fun extractWithRegexAndMlKitInternal(message: String): TransactionDetails? =
        suspendCoroutine { continuation ->
            // ... (ML Kit model download/annotation logic remains the same) ...
            entityExtractor.downloadModelIfNeeded()
                .addOnSuccessListener {
                    entityExtractor.annotate(message)
                        .addOnSuccessListener { entityAnnotations ->
                            // *** Pass smsTimestamp to processAnnotations ***
                            val details = processAnnotations(message, entityAnnotations)
                            continuation.resume(details)
                        }
                        .addOnFailureListener { /* ... handle failure ... */ continuation.resume(null) }
                }
                .addOnFailureListener { /* ... handle failure ... */ continuation.resume(null) }
        }

    // Processes ML Kit annotations and applies Regex patterns.
    // This is part of the fallback mechanism.
    private fun processAnnotations(
        message: String,
        entityAnnotations: List<EntityAnnotation>, // ML Kit annotations (currently unused in this logic, relies on Regex)
    ): TransactionDetails? {
        Log.d(TAG, "Processing annotations with Regex fallback.")
        // The existing implementation relies primarily on Regex patterns.
        // ML Kit annotations could be integrated here for better accuracy if needed.
        var amount: Double? = null
        var currency = "INR" // Default currency
        var merchant: String? = null
        var date: Long? = null
        var refNumber: String? = null
        var description: String? = null

        // Extract reference number
        val refPattern = Regex("""(?:Ref(?:no|erence)?\.?\s*(?:Number)?\s*:?\s*)(\w+)""", RegexOption.IGNORE_CASE)
        refPattern.find(message)?.let {
            refNumber = it.groupValues[1]
            Log.d(TAG, "Regex found Reference Number: $refNumber")
        }

        // --- Refactored Amount/Currency Extraction Loop ---
        // Use standard loops to avoid experimental lambda break/continue
        outerLoop@ for ((curr, patterns) in currencyPatterns) {
            for (pattern in patterns) {
                val matchResult = pattern.find(message)
                if (matchResult != null) {
                    val potentialAmount = matchResult.groupValues[1].replace(",", "").toDoubleOrNull()
                    if (potentialAmount != null) {
                        amount = potentialAmount
                        currency = curr
                        Log.d(TAG, "Regex found Amount: $amount, Currency: $currency")
                        break@outerLoop // Exit both loops once amount is found
                    }
                }
            }
        }
        // --- End Refactored Loop ---

        if (amount == null) Log.w(TAG, "Could not extract amount using Regex.")

        // Extract merchant
        for (pattern in merchantPatterns) {
            pattern.find(message)?.let {
                // Clean up merchant name: remove extra spaces, potential @ symbols etc.
                merchant = it.groupValues[1].trim()
                    .replace(Regex("""\s+@\s+|\s+"""), " ") // Replace space-@-space or multiple spaces with single space
                    .replace(Regex("""\.+$"""), "") // Remove trailing dots
                    .trim()
                if (merchant?.isNotEmpty() == true) {
                    Log.d(TAG, "Regex found Merchant: $merchant")
                    // Don't break here, let subsequent patterns potentially find a better match?
                    // Or break if you want the first match: break
                } else {
                    merchant = null // Reset if cleaning resulted in empty string
                }
            }
            // If a merchant is found by a pattern, stop searching
            if (merchant != null) break
        }
        if (merchant == null) Log.w(TAG, "Could not extract merchant using primary Regex patterns.")


        // Special handling for specific keywords if merchant is still null
        if (merchant == null) {
            when {
                message.contains("Apay", ignoreCase = true) -> merchant = "Apay Transaction"
                message.contains("salary", ignoreCase = true) -> merchant = "Salary Credit"
                // Add more specific keyword checks if needed
            }
            if (merchant != null) Log.d(TAG, "Found merchant via keyword: $merchant")
        }

        // Extract description from the message if available
        // Look for common description markers like 'desc:', 'for', 'payment towards'
        val descPattern = Regex("""(?:desc:|purpose:|for|payment towards)\s*([A-Za-z0-9\s\-&@._]+?)(?:\s+Ref|\.|$)""", RegexOption.IGNORE_CASE)
        description = descPattern.find(message)?.groupValues?.get(1)?.trim()
        if (description != null) {
            Log.d(TAG, "Regex found Description: $description")
        } else {
            Log.d(TAG, "Could not extract description using Regex.")
        }


        // If we have at least an amount, create the transaction details
        return if (amount != null) {
            val finalMerchant = merchant ?: "Unknown Merchant" // Use "Unknown" if no merchant found
            val finalDescription = description ?: "" // Use empty string if description is null

            Log.i(TAG, "Fallback Regex/MLKit result: Amount=$amount, Merchant='$finalMerchant', Currency='$currency', Ref='$refNumber', Desc='$finalDescription'")

            TransactionDetails(
                name = "Unknown",
                amount = amount, // Amount is non-null here due to the check
                merchant = finalMerchant,
                date = 0L,
                category = "Uncategorized", // Fallback category is always Uncategorized
                currency = currency,
                referenceNumber = refNumber,
                description = finalDescription
            )
        } else {
            Log.e(TAG, "Fallback Regex/MLKit failed to extract minimum required info (amount).")
            null // Return null if amount couldn't be extracted
        }
    }

    // Function to get category from Firestore (as provided by user, seems unused in this class's main flow)
    // This function appears redundant if the service uses MerchantDao.getCategoryForMerchant
    private suspend fun getCategoryForMerchant(merchant: String, userId: String?): String {
        userId ?: return "Uncategorized" // Return default if no user ID

        return try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Fetching category for merchant '$merchant' for user '$userId' (from MessageExtractor Firestore lookup)")
                val merchantDoc = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(userId)
                    .collection("merchants")
                    .document(merchant) // Assuming merchant name is the document ID
                    .get()
                    .await()

                val category = merchantDoc.getString("category")
                if (category != null) {
                    Log.d(TAG, "Found category '$category' for merchant '$merchant' via Firestore")
                    category
                } else {
                    Log.d(TAG, "No category found for merchant '$merchant' via Firestore. Defaulting to Uncategorized.")
                    "Uncategorized"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting category for merchant '$merchant' via Firestore", e)
            "Uncategorized" // Default on error
        }
    }
}
