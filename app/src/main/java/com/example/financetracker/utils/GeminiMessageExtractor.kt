package com.example.financetracker.utils

import android.content.Context
import android.util.Log
import com.example.financetracker.R // For API Key resource
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.dao.CategoryDao
import com.example.financetracker.database.dao.MerchantDao
import com.example.financetracker.model.TransactionDetails // Assuming this is the correct import path for your data class
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.GenerateContentResponse
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
    private val categoryDao: CategoryDao = TransactionDatabase.getDatabase(context).categoryDao(),
    private val merchantDao : MerchantDao = TransactionDatabase.getDatabase(context).merchantDao()
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
    suspend fun extractTransactionDetails(messageBody: String, sender: String): Pair<TransactionDetails?, String?> {
        if (generativeModel == null) { Log.e(TAG, "Gemini model not initialized."); return Pair(null, null) }

        return withContext(Dispatchers.IO) { // Ensure DB access is off main thread
            try {
                // --- Prepare Context Data ---
                val defaultCategories = CategoryUtils.getDefaultCategories().map { it.name }
                // Fetch user categories ONCE for the prompt
                val userCategories = try {
                    categoryDao.getAllCategoriesList().map { it.name }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching user categories from DB", e)
                    emptyList()
                }
                val allCategories = (defaultCategories + userCategories + listOf("Uncategorized")).distinct().sorted()
                val categoriesString = allCategories.joinToString(", ")

                // Fetch known merchants ONCE for the prompt (limit if list is huge)
                val knownMerchants = try {
                    merchantDao.getAllMerchantsList() // Assuming you add this suspend fun to MerchantDao
                        .map { it.name }
                        .distinct()
                        .take(50) // Limit prompt length if needed
                        .joinToString(", ")
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching merchants from DB", e)
                    "" // Empty string if error
                }

                Log.d(TAG, "[Gemini Prompt] Using categories: $categoriesString")
                if (knownMerchants.isNotEmpty()) Log.d(TAG, "[Gemini Prompt] Known merchants hint: $knownMerchants")

                // --- Refined Prompt ---
                val prompt = """
                Analyze the following SMS message considering its sender.
                Sender: "$sender"
                SMS Message: "$messageBody"

                Available Categories: [$categoriesString]
                Known Merchants (Hint only, sender name might differ): [$knownMerchants]

                **Instruction:** Your primary goal is to determine if this message definitively represents a completed financial transaction (a debit, credit, successful payment, successful transfer, or withdrawal).

                **IGNORE and output ONLY `{}` if the message is ANY of the following:**
                * OTP (One-Time Password) or verification code.
                * Login alert, security warning, or failed attempt notification.
                * Balance inquiry or available limit update.
                * Promotional offer (e.g., loan offers, discounts, service ads).
                * Bill reminder or due date notification.
                * Delivery status or general service notification.
                * Unclear message where a transaction isn't explicitly confirmed.

                **Output Format:**
                1.  **If it IS a confirmed financial transaction:** Extract the following information and format it strictly as a single JSON object with these exact keys: "name", "amount", "merchant", "category", "currency", "referenceNumber", "description", "type".
                    * "name": Create a concise, relevant transaction name (max 5 words, e.g., "Swiggy Order", "Salary Deposit", "Transfer from John", "ATM Withdrawal"). Base it on merchant/sender/description. Avoid generic names like "Transaction". **Must be present.**
                    * "amount": The numeric transaction amount (e.g., 150.75). Must be positive. **Must be present.**
                    * "merchant": The merchant name, sender, recipient, or source (e.g., "Amazon", "Zomato", "Salary", "ATM", "Transfer to Jane D"). Use the Known Merchants list as a hint but prioritize information in the SMS. If truly unclear or unavailable, return "Unknown Merchant".
                    * "category": Choose the **single most appropriate category** ONLY from this exact list: [$categoriesString]. If the merchant is known (e.g., Zomato -> Food & Dining) use that association strongly. If unsure or no category fits well, return **"Uncategorized"**. Do NOT invent categories. **Must be present.**
                    * "currency": Currency code (e.g., "INR", "USD"). Default "XXX".
                    * "referenceNumber": Transaction reference/ID if present (string), otherwise null.
                    * "description": Very brief purpose if available (max 10 words), otherwise use the generated "name".
                    * "type": Either "Income" or "Expense". Default to "Expense".
                2.  **If it is NOT a confirmed financial transaction (based on the IGNORE list):** Output ONLY the empty JSON object: `{}`

                Example Transaction Output: {"name":"Zomato Food Order","amount": 500.00, "merchant": "Zomato", "category": "Food & Dining", "currency": "INR", "referenceNumber": "3129ABCD", "description": "Zomato Food Order", "type": "Expense"}
                Example Non-Transaction Output: {}

                Your output must be ONLY the JSON object based on the conditions above.
                """.trimIndent()
                // --- End Refined Prompt ---

                Log.v(TAG, "Sending refined prompt to Gemini...")
                val response = generativeModel.generateContent(prompt)
                val rawResponseText = response.text
                Log.d(TAG, "[Gemini Raw Response]: $rawResponseText")

                rawResponseText?.let { jsonString ->
                    val cleanedJsonString = jsonString.trim().removeSurrounding("```json", "```").trim()
                    Log.d(TAG, "[Gemini Cleaned JSON]: $cleanedJsonString")

                    if (cleanedJsonString == "{}") {
                        Log.i(TAG, "[Gemini] Identified message as non-transactional.")
                        return@withContext Pair(null, null)
                    }

                    try {
                        val jsonObject = JSONObject(cleanedJsonString)
                        val name = jsonObject.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "Unknown Transaction" // Better default name
                        val amount = abs(jsonObject.optDouble("amount", 0.0)) // Ensure positive
                        // Prioritize non-"Unknown Merchant" value, handle potential null string
                        val merchant = jsonObject.optString("merchant").takeIf { it.isNotBlank() && it != "null" && it != "Unknown Merchant" } ?: "Unknown Merchant"
                        val category = jsonObject.optString("category", "Uncategorized").takeIf { it.isNotBlank() && it != "null" } ?: "Uncategorized"
                        val currency = jsonObject.optString("currency", "XXX").takeIf { it.isNotBlank() && it != "null" } ?: "XXX"
                        val referenceNumber = jsonObject.optString("referenceNumber", null).takeIf { !it.isNullOrBlank() && it != "null" }
                        val description = jsonObject.optString("description", name).takeIf { it.isNotBlank() && it != "null" } ?: name // Default description to name
                        val type = jsonObject.optString("type", "Expense").takeIf { it.isNotBlank() && it != "null" } ?: "Expense"

                        // Basic validation: If it's an expense, amount must be > 0
                        if (amount <= 0 && type.equals("Expense", ignoreCase = true)) {
                            Log.w(TAG, "[Gemini] Extracted type is Expense, but amount is <= 0 ($amount). Discarding.")
                            return@withContext Pair(null, null)
                        }
                        // If no valid name or merchant could be derived, potentially discard
                        if (name == "Unknown Transaction" && merchant == "Unknown Merchant") {
                            Log.w(TAG, "[Gemini] Could not extract meaningful name or merchant. Discarding.")
                            return@withContext Pair(null, null)
                        }


                        val transactionDetails = TransactionDetails(
                            name = name, amount = amount, merchant = merchant, date = 0L, // Date set by caller
                            category = category, currency = currency,
                            referenceNumber = referenceNumber, description = description
                        )
                        Log.i(TAG, "[Gemini] Extracted Transaction: $transactionDetails, Type: $type")
                        Pair(transactionDetails, type)

                    } catch (e: Exception) { Log.e(TAG, "Error parsing Gemini JSON: $cleanedJsonString", e); Pair(null, null) }
                } ?: run { Log.w(TAG, "Gemini response text was null."); Pair(null, null) }
            } catch (e: Exception) { Log.e(TAG, "Error calling Gemini API or preparing prompt", e); Pair(null, null) }
        }
    }

    /*suspend fun classifySenderIdAsFinancial(senderId: String): Boolean {
        if (generativeModel == null) {
            Log.e(TAG, "Gemini model not initialized for sender classification.")
            return false // Cannot classify without the model
        }
        // Basic check: If senderId is purely numeric or very short, likely not financial ID
        if (senderId.length < 4 || senderId.all { it.isDigit() || it == '+' }) {
            Log.v(TAG, "Sender '$senderId' skipped by basic pre-filter (too short/numeric).")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                // Context for the prompt (can be refined)
                val knownBankIndicators = "SBI, HDFC, ICICI, AXIS, KOTAK, PNB, CANBNK, UNIONBK, INDUSB, YESBNK, IDFCBK, CITIBK, HSBC, BANK, BNK" // More bank names/abbr
                val paymentIndicators = "UPI, PAY, PYMT, PAYTM, GPAY, PHONEPE, AMZPAY, MOBIKWIK, JUSPAY, RZRPAY, PAYU, BILLDESK, CARD, CRDT, DEBIT, WALLET" // Payment related
                val negativeIndicators = "OFFER, LOAN, SALE, ADVT, PROMO, INSUR, POLICY, REWARD, ALERT, INFO, UPDATE, TEAM, SUPPORT, VERIFY, CODE, OTP, CARE, TICKET, QUERY, ASSIST, CLICK, VISIT, DEALS, SHOP, NEWS, JOB, VERIFICATION, AUTHENTICATION, DELIVERY, TRACKING" // More negative terms

                // Refined Prompt
                val prompt = """
                Analyze ONLY the SMS sender ID provided: "$senderId"

                **Context:**
                * Common financial service identifiers in India often include terms like: [$knownBankIndicators, $paymentIndicators].
                * Common non-financial identifiers often relate to: [$negativeIndicators].
                * Sender IDs are typically short, often all-caps, sometimes with prefixes like XX- or suffixes like BK. Pure numbers are usually phone numbers, not financial IDs.

                **Question:** Based ONLY on the sender ID "$senderId", is it highly likely to be from a financial institution (bank, credit card co), a major payment service (UPI, wallet, gateway), or specifically for sending transaction confirmations/alerts (NOT promotions or general info)?

                **Instructions:**
                * Focus SOLELY on the sender ID string itself.
                * If the ID clearly matches common financial patterns/names (like AXISBK, VM-SBIUPI, GPAY, AMZPAY), answer "Yes".
                * If the ID strongly suggests promotional, informational, verification, or non-financial services (like VM-SWIGGY, AD-OFFER, BL-BSNLIN, JD-RTRNDS, VERIFY), answer "No".
                * If the ID is ambiguous or too generic to be certain (like INFO, UPDATE, short codes without clear context), answer "No".
                * Answer ONLY with the single word "Yes" or "No".
                """.trimIndent()

                Log.v(TAG, "Sending sender classification prompt for: $senderId")
                val response : GenerateContentResponse = generativeModel.generateContent(prompt) // Define type explicitly
                val responseText = response.text?.trim()
                Log.d(TAG, "Gemini classification response for '$senderId': $responseText")

                // Parse the Response
                val isFinancial = responseText.equals("Yes", ignoreCase = true)
                Log.i(TAG, "Sender '$senderId' classified as Financial: $isFinancial")

                isFinancial // Return true if response is "Yes"

            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API for sender classification: $senderId", e)
                false // Default to false (non-financial) on error
            }
        }
    }*/

    // Inside GeminiMessageExtractor.kt

    // --- NEW Function for Batch Sender ID Classification ---
    suspend fun classifySenderIdBatch(senderIds: List<String>): List<String> {
        if (generativeModel == null) {
            Log.e(TAG, "Gemini model not initialized for batch classification.")
            return emptyList()
        }
        if (senderIds.isEmpty()) {
            return emptyList()
        }

        // Basic pre-filtering (already done in SenderListManager, but can be added here too)
        val validSenderIds = senderIds.filter { it.length >= 5 && !it.all { c -> c.isDigit() || c == '+' } }
        if (validSenderIds.isEmpty()) {
            Log.d(TAG, "No valid sender IDs left after pre-filtering for batch.")
            return emptyList()
        }

        val senderListString = validSenderIds.joinToString("\n") // Send one ID per line

        return withContext(Dispatchers.IO) {
            try {
                // Context strings (same as single classification)
                val knownBankIndicators = "SBI, HDFC, ICICI, AXIS, KOTAK, PNB, CANBNK, UNIONBK, INDUSB, YESBNK, IDFCBK, CITIBK, HSBC, BANK, BNK"
                val paymentIndicators = "UPI, PAY, PYMT, PAYTM, GPAY, PHONEPE, AMZPAY, MOBIKWIK, JUSPAY, RZRPAY, PAYU, BILLDESK, CARD, CRDT, DEBIT, WALLET"
                val negativeIndicators = "OFFER, SALE, PROMO, ALERT, VERIFY, CODE, OTP, TEAM, SUPPORT, VISIT, CLICK, DEALS, SHOP, NEWS, JOB, AUTH, DELIVERY, TRACK"

                // --- Batch Prompt Design ---
                val prompt = """
                Analyze the following list of SMS sender IDs, one per line:
                --- START LIST ---
                $senderListString
                --- END LIST ---

                Context:
                * Common financial service identifiers in India often include: [$knownBankIndicators, $paymentIndicators].
                * Common non-financial identifiers often relate to: [$negativeIndicators].
                * Financial IDs are typically short, often all-caps, sometimes with prefixes like XX- or suffixes like BK. Pure numbers are usually not financial IDs.

                Task: Identify ALL sender IDs from the list above that are highly likely to be from a financial institution (bank, credit card co), a major payment service (UPI, wallet, gateway), or specifically for sending transaction confirmations/alerts.

                Instructions:
                * Focus SOLELY on the sender ID strings provided.
                * Ignore IDs that are clearly promotional, informational, verification-related, or too ambiguous.
                * Your output MUST be ONLY a JSON array string containing the sender IDs you identified as likely financial.
                * Example Output: ["VA-SBIUPI", "AX-PAYTMB", "VK-HDFCBK", "GPAY"]
                * If NO sender IDs from the list are likely financial, output an empty JSON array string: []
                * Do NOT include any explanations, introductions, or markdown formatting like ```json. Just the JSON array string.
                """.trimIndent()

                Log.v(TAG, "Sending BATCH sender classification prompt for ${validSenderIds.size} IDs...")
                val response: GenerateContentResponse = generativeModel.generateContent(prompt)
                val responseText = response.text?.trim()
                Log.d(TAG, "Gemini BATCH classification raw response: $responseText")

                // --- Parse the JSON Array Response ---
                if (responseText.isNullOrBlank()) {
                    Log.w(TAG, "Gemini batch response was null or blank.")
                    return@withContext emptyList<String>()
                }

                try {
                    // Expecting response like ["ID1", "ID2", ...]
                    val jsonArray = org.json.JSONArray(responseText)
                    val financialList = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        val sender = jsonArray.optString(i)
                        if (sender != null && sender.isNotEmpty()) {
                            financialList.add(sender) // Add the sender identified by Gemini
                        }
                    }
                    Log.i(TAG, "Parsed ${financialList.size} financial senders from Gemini batch response.")
                    financialList // Return the parsed list

                } catch (e: org.json.JSONException) {
                    Log.e(TAG, "Error parsing Gemini batch JSON response: '$responseText'", e)
                    // Attempt fallback parsing (e.g., comma-separated, newline-separated) if needed, or return empty
                    // Example fallback (simple comma split, might be unreliable):
                    // return responseText.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
                    emptyList<String>() // Return empty list on JSON parsing error
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error calling Gemini API for batch sender classification", e)
                emptyList<String>() // Return empty list on API error
            }
        } // End withContext
    } // End classifySenderIdBatch

} // End class GeminiMessageExtractor

