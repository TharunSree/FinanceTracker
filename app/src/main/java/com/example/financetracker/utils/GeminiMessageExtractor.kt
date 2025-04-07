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
import kotlin.math.abs

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
        if (generativeModel == null) { Log.e(TAG, "Gemini model not initialized."); return Pair(null, null) }

        return withContext(Dispatchers.IO) {
            try {
                val defaultCategories = CategoryUtils.getDefaultCategories().map { it.name }
                val userCategories = categoryDao.getAllCategoriesList().map { it.name }
                val allCategories = (defaultCategories + userCategories + listOf("Uncategorized")).distinct()
                val categoriesString = allCategories.joinToString(", ")
                Log.d(TAG, "[Gemini Prompt] Using categories: $categoriesString")

                // *** === MODIFIED PROMPT === ***
                val prompt = """
                Analyze the following SMS message.
                SMS Message: "$messageBody"

                Available Categories: [$categoriesString]

                **Instruction:** First, determine if this message describes an actual financial transaction (a debit, credit, payment, transfer, or withdrawal). IGNORE messages that are only OTPs, login alerts, security warnings, balance inquiries, promotional offers, or general notifications, even if they mention an account number or financial institution.

                **Output Format:**
                1.  **If it IS a financial transaction:** Extract the following information and format it strictly as a single JSON object with these exact keys: "amount", "merchant", "category", "currency", "referenceNumber", "description", "type".
                    - "name": Give a suitable name for the transaction (e.g., "Amazon Purchase", "Salary Deposit") after analyzing "category", "merchant" and "description". A name must be present imagine some name.
                    - "amount": Transaction amount as a positive number (e.g., 150.75, 2000000.00). Handle commas correctly. Output ONLY the number. Must be present if it's a transaction.
                    - "merchant": Merchant name, recipient, or source (e.g., "Amazon", "Salary", "Transfer to John"). Use "Unknown Merchant" if truly unclear.
                    - "category": Choose the **single most appropriate category** ONLY from this exact list: [$categoriesString]. Do NOT create new categories. If no category from the list fits well or context is minimal, use "Uncategorized".
                    - "currency": Currency code (e.g., "INR", "USD"). Infer if possible, default "XXX".
                    - "referenceNumber": Transaction reference number or ID if present (string or null).
                    - "description": Concise transaction purpose (max 15 words). Default to merchant name if blank.
                    - "type": Determine if it's "Income" or "Expense". Default to "Expense".
                2.  **If it is NOT a financial transaction (e.g., OTP, alert, promo):** Output ONLY the empty JSON object: `{}`

                Example Transaction Output: {"name":Ordering Burger, "amount": 500.00, "merchant": "Swiggy", "category": "Food & Dining", "currency": "INR", "referenceNumber": null, "description": "Food order", "type": "Expense"}
                Example Non-Transaction Output: {}

                Your output must be ONLY the JSON object based on the conditions above.
                """.trimIndent()
                // *** === END MODIFIED PROMPT === ***

                Log.v(TAG, "Sending prompt to Gemini...") // Verbose log for prompt
                val response = generativeModel.generateContent(prompt)
                val rawResponseText = response.text
                Log.d(TAG, "[Gemini Raw Response]: $rawResponseText")

                rawResponseText?.let { jsonString ->
                    val cleanedJsonString = jsonString.trim().removeSurrounding("```json", "```").trim()
                    Log.d(TAG, "[Gemini Cleaned JSON]: $cleanedJsonString")

                    // *** ADD CHECK FOR EMPTY JSON OBJECT ***
                    if (cleanedJsonString == "{}") {
                        Log.i(TAG, "[Gemini] Identified message as non-transactional (returned {}).")
                        return@withContext Pair(null, null) // Return nulls for non-transactions
                    }
                    // *** END CHECK ***

                    try {
                        val jsonObject = JSONObject(cleanedJsonString)
                        // ... (Extract amount, merchant, category etc. as before - LOG amount) ...
                        val name = jsonObject.optString("name","Unknown").ifBlank { "Unknown" }
                        val rawAmount = jsonObject.opt("amount"); Log.d(TAG, "[Gemini] Raw amount: $rawAmount"); val amount = jsonObject.optDouble("amount", 0.0); val finalAmount = abs(amount); Log.d(TAG, "[Gemini] Parsed amount: $amount, Final: $finalAmount")
                        val merchant = jsonObject.optString("merchant", "Unknown").ifBlank { "Unknown" }
                        val category = jsonObject.optString("category", "Uncategorized").ifBlank { "Uncategorized" }
                        val currency = jsonObject.optString("currency", "XXX").ifBlank { "XXX" }
                        val referenceNumber = jsonObject.optString("referenceNumber", null).takeIf { !it.isNullOrBlank() }
                        val description = jsonObject.optString("description", "").ifBlank { merchant }
                        val type = jsonObject.optString("type", "Expense").ifBlank { "Expense" }

                        // Date is placeholder 0L, handled by caller using SMS timestamp
                        val dateLong = 0L

                        // Check if amount extraction was successful for transactions
                        if (finalAmount <= 0 && type == "Expense") { // Basic check: Expense needs amount > 0
                            Log.w(TAG, "[Gemini] Extracted type is Expense, but amount is <= 0 ($finalAmount). Treating as non-transactional.")
                            return@withContext Pair(null, null)
                        }


                        val transactionDetails = TransactionDetails(
                            name = name,amount = finalAmount, merchant = merchant, date = dateLong,
                            category = category, currency = currency,
                            referenceNumber = referenceNumber, description = description
                        )
                        Log.i(TAG, "[Gemini] Extracted Transaction: $transactionDetails, Type: $type")
                        Pair(transactionDetails, type)

                    } catch (e: Exception) { Log.e(TAG, "Error parsing Gemini JSON: $cleanedJsonString", e); Pair(null, null) }
                } ?: run { Log.w(TAG, "Gemini response text was null."); Pair(null, null) }
            } catch (e: Exception) { Log.e(TAG, "Error calling Gemini API", e); Pair(null, null) }
        }
    }
}
