package com.example.financetracker.utils

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class GeminiMessageExtractor(private val context: Context, private val apiKey: String) {

    private val TAG = "GeminiMessageExtractor"

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = ApiConfig.GEMINI_MODEL,
            apiKey = apiKey
        )
    }

    @Serializable
    data class GeminiResponse(
        val amount: Double? = null,
        val merchant: String? = null,
        val date: String? = null,
        val currency: String? = null,
        val referenceNumber: String? = null,
        val description: String? = null
    )

    suspend fun extractTransactionDetails(message: String): TransactionDetails? {
        return try {
            Log.d(TAG, "Starting Gemini extraction for message: $message")

            // Create prompt for Gemini
            val prompt = """
                You are a financial message parser. Extract transaction details from this SMS message.
                
                Message: "$message"
                
                Extract and return ONLY a JSON object with these fields:
                - amount: the numerical transaction amount (as a number, no currency symbols)
                - merchant: the merchant or recipient name
                - date: the transaction date in YYYY-MM-DD format
                - currency: the currency code (default to "INR" if not specified)
                - referenceNumber: any reference or transaction number
                - description: any additional transaction description
                
                For any field you cannot confidently extract, use null. 
                Return ONLY the JSON, nothing else.
            """.trimIndent()

            // Call Gemini API - using the string-based prompt with timeout
            val response = withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Calling Gemini API...")
                    val result = generativeModel.generateContent(prompt).text
                    Log.d(TAG, "Received response from Gemini API: $result")
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling Gemini API", e)
                    null
                }
            }

            // Parse JSON response
            if (!response.isNullOrEmpty()) {
                // Extract the JSON part from the response
                val jsonStart = response.indexOf("{")
                val jsonEnd = response.lastIndexOf("}") + 1

                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    try {
                        val jsonStr = response.substring(jsonStart, jsonEnd)
                        Log.d(TAG, "Parsed JSON: $jsonStr")

                        // Use Android's JSONObject for parsing
                        val jsonObject = JSONObject(jsonStr)

                        // Only proceed if we could extract an amount
                        if (jsonObject.has("amount") && !jsonObject.isNull("amount")) {
                            val amount = jsonObject.getDouble("amount")
                            val merchant = if (jsonObject.has("merchant") && !jsonObject.isNull("merchant"))
                                jsonObject.getString("merchant") else "Unknown Merchant"
                            val dateStr = if (jsonObject.has("date") && !jsonObject.isNull("date"))
                                jsonObject.getString("date") else null
                            val currency = if (jsonObject.has("currency") && !jsonObject.isNull("currency"))
                                jsonObject.getString("currency") else "INR"
                            val refNumber = if (jsonObject.has("referenceNumber") && !jsonObject.isNull("referenceNumber"))
                                jsonObject.getString("referenceNumber") else null
                            val description = if (jsonObject.has("description") && !jsonObject.isNull("description"))
                                jsonObject.getString("description") else ""

                            // Parse date if available
                            val dateInMillis = parseDate(dateStr) ?: System.currentTimeMillis()

                            // Create and return TransactionDetails
                            TransactionDetails(
                                amount = amount,
                                merchant = merchant,
                                date = dateInMillis,
                                category = "Uncategorized",
                                currency = currency,
                                referenceNumber = refNumber,
                                description = description
                            ).also {
                                Log.d(TAG, "Successfully created TransactionDetails: $it")
                            }
                        } else {
                            Log.d(TAG, "No amount found in Gemini response")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing JSON response", e)
                        null
                    }
                } else {
                    Log.e(TAG, "Could not find valid JSON in response: $response")
                    null
                }
            } else {
                Log.e(TAG, "Empty response from Gemini API")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in extraction process", e)
            null
        }
    }

    private fun parseDate(dateStr: String?): Long? {
        if (dateStr == null) return null

        val dateFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
            SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH),
            SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
        )

        for (format in dateFormats) {
            try {
                return format.parse(dateStr)?.time
            } catch (e: Exception) {
                continue
            }
        }

        return null
    }
}