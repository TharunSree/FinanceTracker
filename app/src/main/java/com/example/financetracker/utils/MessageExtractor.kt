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
    suspend fun extractTransactionDetails(message: String): TransactionDetails? {
        Log.d(TAG, "Starting extraction for message: $message")
        try {
            // Try Gemini AI extraction first
            // Gemini extractor now returns Pair<TransactionDetails?, String?>
            val (geminiDetails, geminiType) = geminiExtractor.extractTransactionDetails(message)

            // Check if the TransactionDetails object from Gemini is not null
            if (geminiDetails != null) {
                Log.i(TAG, "Successfully extracted with Gemini: $geminiDetails (Type: $geminiType)")
                // Return only the details part, type info is lost here
                return geminiDetails
            } else {
                Log.w(TAG, "Gemini extraction returned null details, falling back to regex/MLKit")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during Gemini extraction, falling back to regex/MLKit", e)
        }

        // Fall back to original ML Kit + regex extraction
        Log.d(TAG, "Attempting fallback extraction with Regex/MLKit.")
        // Call the internal fallback method directly
        return extractWithRegexAndMlKitInternal(message)
    }

    // Renamed internal function for fallback using ML Kit and Regex
    private suspend fun extractWithRegexAndMlKitInternal(message: String): TransactionDetails? =
        suspendCoroutine { continuation ->
            entityExtractor.downloadModelIfNeeded()
                .addOnSuccessListener {
                    Log.d(TAG, "ML Kit model ready. Annotating message.")
                    entityExtractor.annotate(message)
                        .addOnSuccessListener { entityAnnotations ->
                            Log.d(TAG, "ML Kit annotation successful. Processing annotations.")
                            val details = processAnnotations(message, entityAnnotations)
                            continuation.resume(details)
                        }
                        .addOnFailureListener {
                            Log.e(TAG, "ML Kit entity annotation failed", it)
                            continuation.resume(null) // Resume with null on failure
                        }
                }
                .addOnFailureListener {
                    Log.e(TAG, "ML Kit model download failed", it)
                    continuation.resume(null) // Resume with null on failure
                }
        }

    // Processes ML Kit annotations and applies Regex patterns.
    // This is part of the fallback mechanism.
    private fun processAnnotations(
        message: String,
        entityAnnotations: List<EntityAnnotation> // ML Kit annotations (currently unused in this logic, relies on Regex)
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


        // Extract date
        // First try to find explicit date markers
        val dateMarkerPattern = Regex("""(?:on\sdate|dated|on)\s+(\d{1,2}[A-Za-z]{3}\d{2,4}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})""", RegexOption.IGNORE_CASE)
        var dateStr = dateMarkerPattern.find(message)?.groupValues?.get(1)
        Log.d(TAG, "Regex found Date String (marked): $dateStr")


        // If no explicit date marker, try to find any date pattern in the message
        if (dateStr == null) {
            // Improved pattern to catch various date formats more reliably
            val generalDatePattern = Regex("""(\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}|\d{1,2}\s?[A-Za-z]{3}\s?\d{2,4})""")
            dateStr = generalDatePattern.find(message)?.groupValues?.get(1)
            Log.d(TAG, "Regex found Date String (general): $dateStr")
        }

        // Try to parse the date string
        if (dateStr != null) {
            for (dateFormat in datePatterns) {
                try {
                    // Ensure year is handled correctly (e.g., yy vs yyyy) - SimpleDateFormat handles this
                    // Important: Consider TimeZone for consistency if parsing dates without explicit timezone info
                    // dateFormat.timeZone = TimeZone.getDefault() // Or specify UTC: TimeZone.getTimeZone("UTC")
                    date = dateFormat.parse(dateStr)?.time
                    if (date != null) {
                        Log.d(TAG, "Parsed Date to Long: $date using format ${dateFormat.toPattern()}")
                        break // Exit date format loop once parsed
                    }
                } catch (e: Exception) {
                    // Log.v(TAG, "Date parsing failed for format ${dateFormat.toPattern()}: ${e.message}")
                    continue // Try next format
                }
            }
            if (date == null) Log.w(TAG, "Failed to parse extracted date string: $dateStr")
        } else {
            Log.w(TAG, "Could not extract date string using Regex.")
        }


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
            val finalDate = date ?: System.currentTimeMillis() // Use current time if date extraction failed
            val finalDescription = description ?: "" // Use empty string if description is null

            Log.i(TAG, "Fallback Regex/MLKit result: Amount=$amount, Merchant='$finalMerchant', Date=$finalDate, Currency='$currency', Ref='$refNumber', Desc='$finalDescription'")

            TransactionDetails(
                amount = amount, // Amount is non-null here due to the check
                merchant = finalMerchant,
                date = finalDate,
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
