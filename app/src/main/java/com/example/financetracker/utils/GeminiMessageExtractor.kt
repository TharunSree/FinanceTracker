package com.example.financetracker.utils

import android.content.Context
import android.util.Log
import com.example.financetracker.R // For API Key resource
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.dao.CategoryDao
import com.example.financetracker.model.TransactionDetails // Assuming this is the correct import path for your data class
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone // Import TimeZone

// Class responsible for extracting transaction details from SMS messages using the Gemini API.
class GeminiMessageExtractor(
    context: Context,
    // DAO for accessing category data. Needed to provide category context to the AI.
    // Default initialization allows creating instance with just context if needed elsewhere.
    private val categoryDao: CategoryDao = TransactionDatabase.getDatabase(context).categoryDao()
) {
    // Retrieve the API key from resources. Ensure you have this string resource defined.
    private val apiKey = try {
        context.getString(R.string.gemini_api_key)
    } catch (e: Exception) {
        Log.e(TAG, "FATAL ERROR: 'gemini_api_key' string resource not found!", e)
        "" // Provide a default empty key or handle error appropriately
    }

    // Configure safety settings for the generative model.
    private val safetySettings = listOf(
        SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.ONLY_HIGH),
        SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.ONLY_HIGH),
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.ONLY_HIGH),
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.ONLY_HIGH),
    )

    // Initialize the GenerativeModel.
    // Handle potential initialization errors (e.g., invalid API key)
    private val generativeModel: GenerativeModel? = if (apiKey.isNotBlank()) {
        try {
            GenerativeModel(
                modelName = "gemini-1.5-flash", // Or your preferred model
                apiKey = apiKey,
                safetySettings = safetySettings
                // Add generationConfig if needed (temperature, topK, etc.)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing GenerativeModel", e)
            null
        }
    } else {
        Log.e(TAG, "Gemini API Key is blank. GeminiMessageExtractor will not function.")
        null
    }


    // Companion object for logging tag.
    companion object {
        private const val TAG = "GeminiMessageExtractor"
    }

    // Suspended function to extract transaction details and type from an SMS message body.
    // Returns a Pair containing the TransactionDetails (nullable) and the Type string (nullable).
    suspend fun extractTransactionDetails(messageBody: String): Pair<TransactionDetails?, String?> {
        // Check if model initialization failed
        if (generativeModel == null) {
            Log.e(TAG, "GenerativeModel is not initialized. Cannot extract details.")
            return Pair(null, null)
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting Gemini extraction for message: $messageBody")

                // Fetch categories for the prompt.
                val defaultCategories = CategoryUtils.getDefaultCategories().map { it.name }
                val userCategories = categoryDao.getAllCategoriesList().map { it.name }
                // Ensure "Uncategorized" is always an option.
                val allCategories = (defaultCategories + userCategories + listOf("Uncategorized")).distinct()
                val categoriesString = allCategories.joinToString(", ")
                Log.d(TAG, "Using categories for Gemini prompt: $categoriesString")

                // Construct the prompt for the Gemini API based on the user's TransactionDetails class.
                val prompt = """
                Analyze the following SMS message and extract transaction details.
                SMS Message: "$messageBody"

                Available Categories: [$categoriesString]

                Extract the following information and format it strictly as a single JSON object with these exact keys: "amount", "date", "merchant", "category", "currency", "referenceNumber", "description", "type".
                - "amount": Transaction amount as a number (e.g., 150.75). Must be positive.
                - "date": Transaction date in YYYY-MM-DD format. Use today's date if missing. Assume current year if year is missing.
                - "merchant": Merchant name or sender (e.g., "Amazon", "Salary", "Transfer"). Use "Unknown Merchant" if unclear.
                - "category": Choose the most appropriate category ONLY from the provided list: [$categoriesString]. Use "Uncategorized" if unsure or no category fits.
                - "currency": Currency code (e.g., "USD", "INR", "EUR"). Infer if possible, otherwise use "XXX".
                - "referenceNumber": Any reference number or transaction ID (string or null if none).
                - "description": Concise transaction description (max 15 words). Use the merchant name if no other description is available.
                - "type": Determine if it's "Income" or "Expense". Use "Expense" if unsure.

                Example Output Format (MUST be only this JSON object):
                {
                  "amount": 125.50,
                  "date": "2024-07-28",
                  "merchant": "Starbucks",
                  "category": "Food & Dining",
                  "currency": "USD",
                  "referenceNumber": "REF12345",
                  "description": "Coffee purchase at Starbucks",
                  "type": "Expense"
                }
                """.trimIndent()

                // Log the prompt for debugging (optional)
                // Log.v(TAG, "Generated Prompt: $prompt")

                // Generate content using the model.
                val response = generativeModel.generateContent(prompt) // Use the initialized model

                // Log the raw response text for debugging.
                val rawResponseText = response.text
                Log.d(TAG, "Raw Gemini Response: $rawResponseText")

                // Attempt to parse the JSON response.
                rawResponseText?.let { jsonString ->
                    // Clean the response: Remove potential markdown backticks and trim whitespace.
                    val cleanedJsonString = jsonString.trim().removeSurrounding("```json", "```").trim()
                    Log.d(TAG, "Cleaned JSON String: $cleanedJsonString")
                    try {
                        val jsonObject = JSONObject(cleanedJsonString)

                        // Extract data, providing defaults or handling potential errors.
                        val amount = jsonObject.optDouble("amount", 0.0)
                        // Ensure amount is positive, type determines income/expense
                        val finalAmount = kotlin.math.abs(amount)

                        val dateString = jsonObject.optString("date", "")
                        val merchant = jsonObject.optString("merchant", "Unknown Merchant").ifBlank { "Unknown Merchant" }
                        val category = jsonObject.optString("category", "Uncategorized").ifBlank { "Uncategorized" }
                        val currency = jsonObject.optString("currency", "XXX").ifBlank { "XXX" }
                        // Handle optional reference number (null if not present or empty/blank)
                        val referenceNumber = jsonObject.optString("referenceNumber", null).takeIf { !it.isNullOrBlank() }
                        val description = jsonObject.optString("description", "").ifBlank { merchant } // Default description to merchant if blank
                        val type = jsonObject.optString("type", "Expense").ifBlank { "Expense" } // Extract type separately, default Expense

                        // Parse the date string and convert to Long timestamp (milliseconds since epoch).
                        // Use UTC for parsing to avoid timezone issues before conversion.
                        var dateLong: Long
                        var wasDateExtracted = false
                        if (dateString.isNotEmpty()) {
                            try {
                                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
                                sdf.timeZone = TimeZone.getTimeZone("UTC")
                                val parsedDate = sdf.parse(dateString)
                                if (parsedDate != null) {
                                    dateLong = parsedDate.time
                                    wasDateExtracted = true
                                } else {
                                    // *** Use 0L as indicator that Gemini failed to parse/provide date ***
                                    dateLong = 0L
                                }
                            } catch (e: Exception) {
                                dateLong = 0L // Use 0L on error
                            }
                        } else {
                            dateLong = 0L // Use 0L if date string empty
                        }
                        if (!wasDateExtracted) {
                            Log.w(TAG, "Gemini: Date string empty or parsing failed. Date defaulted to 0L (will use SMS timestamp).")
                        }

                        // Create the TransactionDetails object based on the user's definition.
                        val transactionDetails = TransactionDetails(
                            amount = finalAmount,
                            merchant = merchant,
                            date = dateLong, // Use the Long timestamp
                            category = category,
                            currency = currency,
                            referenceNumber = referenceNumber,
                            description = description
                        )

                        Log.i(TAG, "Gemini successfully parsed: Details=$transactionDetails, Type=$type")

                        // Return the TransactionDetails object and the extracted type string as a Pair.
                        Pair(transactionDetails, type)

                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Gemini JSON response: $cleanedJsonString", e)
                        Pair(null, null) // Return nulls if JSON parsing fails
                    }
                } ?: run {
                    Log.w(TAG, "Gemini response text was null.")
                    Pair(null, null) // Return nulls if the response text is null
                }

            } catch (e: Exception) {
                // Log any exceptions during the API call or processing.
                Log.e(TAG, "Error during Gemini API call or processing", e)
                Pair(null, null) // Return nulls in case of any other error
            }
        }
    }
}
