package com.example.financetracker.utils

import android.content.Context
import android.util.Log
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Category
import com.example.financetracker.database.entity.Transaction
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

class MessageExtractor(private val context: Context) {
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    private val TAG = "MessageExtractor"

    // Regular expressions for common financial message patterns
    private val amountPattern = Pattern.compile("(?:Rs|INR|₹)\\s*([\\d,]+\\.?\\d*)")
    private val debitPattern = Pattern.compile("(?:debited|paid|spent|payment|purchase|txn|transaction of|debit)", Pattern.CASE_INSENSITIVE)
    private val creditPattern = Pattern.compile("(?:credited|received|added|credit|refund)", Pattern.CASE_INSENSITIVE)
    private val merchantPattern = Pattern.compile("(?:at|to|for)\\s+([A-Za-z0-9\\s&.\\-/]+)", Pattern.CASE_INSENSITIVE)
    private val merchantBlacklistWords = arrayOf("info", "details", "account", "balance", "UPI", "IMPS", "ref", "a/c", "upi", "imps", "ref", "no")

    data class TransactionDetails(
        val amount: Double,
        val isCredit: Boolean,
        val merchant: String?,
        val date: Long?,
        val category: String?,
        val description: String?,
        val needsUserInput: Boolean = false
    )

    suspend fun extractTransactionDetails(messageBody: String): TransactionDetails? {
        // Check if the message is likely to be a financial transaction
        if (!isFinancialMessage(messageBody)) {
            Log.d(TAG, "Not a financial message: $messageBody")
            return null
        }

        // Try pattern-based extraction first
        val basicDetails = extractBasicDetails(messageBody)

        // If patterns couldn't extract enough information, use Gemini
        return if (basicDetails?.merchant.isNullOrBlank() || basicDetails?.category.isNullOrBlank()) {
            enhanceWithGemini(messageBody, basicDetails)
        } else {
            basicDetails
        }
    }

    private fun isFinancialMessage(messageBody: String): Boolean {
        val financialKeywords = arrayOf("account", "debit", "credit", "transaction", "balance", "payment", "spent", "received", "transfer", "Rs.", "INR", "₹")
        val senderPatterns = arrayOf("hdfc", "icici", "sbi", "axis", "bank", "yono", "paytm", "googlepay", "upi")

        // Check if message contains financial keywords
        val containsKeywords = financialKeywords.any { keyword ->
            messageBody.lowercase().contains(keyword.lowercase())
        }

        // Check if the message might be from a financial institution
        val mightBeFromBank = senderPatterns.any { pattern ->
            messageBody.lowercase().contains(pattern.lowercase())
        }

        // Check for amount pattern
        val containsAmount = amountPattern.matcher(messageBody).find()

        return (containsKeywords || mightBeFromBank) && containsAmount
    }

    private fun extractBasicDetails(messageBody: String): TransactionDetails? {
        try {
            // Extract amount
            val amountMatcher = amountPattern.matcher(messageBody)
            if (!amountMatcher.find()) {
                return null
            }

            val amountStr = amountMatcher.group(1).replace(",", "")
            val amount = amountStr.toDoubleOrNull() ?: return null

            // Determine if credit or debit
            val debitMatcher = debitPattern.matcher(messageBody)
            val creditMatcher = creditPattern.matcher(messageBody)
            val isCredit = !debitMatcher.find() && creditMatcher.find()

            // Extract merchant
            var merchant: String? = null
            val merchantMatcher = merchantPattern.matcher(messageBody)
            if (merchantMatcher.find()) {
                merchant = merchantMatcher.group(1)?.trim()
                    ?.split("\\s+".toRegex())
                    ?.filter { word -> !merchantBlacklistWords.contains(word.lowercase()) }
                    ?.joinToString(" ")
                    ?.trim()
            }

            // Final cleaning of merchant name
            merchant = merchant?.trim()?.replace(Regex("[^A-Za-z0-9\\s&.\\-/]"), "")

            return TransactionDetails(
                amount = amount,
                isCredit = isCredit,
                merchant = merchant,
                date = System.currentTimeMillis(),
                category = null,  // Will be enhanced with Gemini
                description = null,  // Will be enhanced with Gemini
                needsUserInput = merchant.isNullOrBlank()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting basic details", e)
            return null
        }
    }

    private suspend fun enhanceWithGemini(
        messageBody: String,
        basicDetails: TransactionDetails?
    ): TransactionDetails? {
        if (basicDetails == null) return null

        try {
            // Get available categories for the current user
            val auth = FirebaseAuth.getInstance()
            val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(context)

            val database = TransactionDatabase.getDatabase(context)
            val categories = withContext(Dispatchers.IO) {
                database.categoryDao().getAllCategoriesOneTime(userId)
            }

            val categoryNames = categories.map { it.name }

            // Safety settings
            val safetySettings = listOf(
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
            )

            // Create the model
            val model = GenerativeModel(
                modelName = "gemini-pro",
                apiKey = "YOUR_API_KEY",
                safetySettings = safetySettings
            )

            // Create prompt with all necessary information
            val prompt = """
                You are a financial transaction analyzer. Analyze this bank SMS message and extract detailed information.
                
                SMS Message: $messageBody
                
                Available categories: ${categoryNames.joinToString(", ")}
                
                I need the following information in a JSON format:
                1. merchant: The name of the merchant or business (extract from message if possible)
                2. category: The most appropriate category from the available categories
                3. description: A brief description of what this transaction might be for
                4. isCredit: Boolean indicating if this is income (true) or expense (false)
                
                Return ONLY the JSON object with these fields, nothing else:
            """.trimIndent()

            // Generate content
            val response = model.generateContent(prompt).text

            // Parse JSON response
            val jsonResponse = JSONObject(response!!.trim())

            // Extract information
            val enhancedMerchant = jsonResponse.optString("merchant")
                ?.takeIf { it.isNotBlank() } ?: basicDetails.merchant
            val enhancedCategory = jsonResponse.optString("category")
                ?.takeIf { it.isNotBlank() } ?: "Uncategorized"
            val enhancedDescription = jsonResponse.optString("description")
                ?.takeIf { it.isNotBlank() }
            val enhancedIsCredit = jsonResponse.optBoolean("isCredit", basicDetails.isCredit)

            return basicDetails.copy(
                merchant = enhancedMerchant,
                category = enhancedCategory,
                description = enhancedDescription,
                isCredit = enhancedIsCredit,
                needsUserInput = enhancedMerchant.isNullOrBlank() || enhancedCategory == "Uncategorized"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error using Gemini to enhance transaction", e)
            // Return original details if Gemini enhancement fails
            return basicDetails
        }
    }
}