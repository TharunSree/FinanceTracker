package com.example.financetracker.utils

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
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

            // Call Gemini API - using the string-based prompt
            val response = withContext(Dispatchers.IO) {
                generativeModel.generateContent(prompt).text
            }

            // Parse JSON response
            if (!response.isNullOrEmpty()) {
                // Extract the JSON part from the response (handling potential text around the JSON)
                val jsonStart = response.indexOf("{")
                val jsonEnd = response.lastIndexOf("}") + 1

                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    try {
                        val jsonStr = response.substring(jsonStart, jsonEnd)
                        Log.d(TAG, "Parsed JSON: $jsonStr")

                        // Use Android's JSONObject to parse the JSON
                        val jsonObject = JSONObject(jsonStr)
                        val extractedResponse = GeminiResponse(
                            amount = if (jsonObject.has("amount") && !jsonObject.isNull("amount"))
                                jsonObject.getDouble("amount") else null,
                            merchant = if (jsonObject.has("merchant") && !jsonObject.isNull("merchant"))
                                jsonObject.getString("merchant") else null,
                            date = if (jsonObject.has("date") && !jsonObject.isNull("date"))
                                jsonObject.getString("date") else null,
                            currency = if (jsonObject.has("currency") && !jsonObject.isNull("currency"))
                                jsonObject.getString("currency") else null,
                            referenceNumber = if (jsonObject.has("referenceNumber") && !jsonObject.isNull("referenceNumber"))
                                jsonObject.getString("referenceNumber") else null,
                            description = if (jsonObject.has("description") && !jsonObject.isNull("description"))
                                jsonObject.getString("description") else null
                        )

                        // Convert to TransactionDetails
                        convertToTransactionDetails(extractedResponse)
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
            Log.e(TAG, "Error calling Gemini API", e)
            null
        }
    }

    private fun convertToTransactionDetails(response: GeminiResponse): TransactionDetails? {
        // Only create TransactionDetails if we at least have an amount
        return if (response.amount != null) {
            val dateInMillis = parseDate(response.date)

            TransactionDetails(
                amount = response.amount,
                merchant = response.merchant ?: "Unknown Merchant",
                date = dateInMillis ?: System.currentTimeMillis(),
                category = "Uncategorized",
                currency = response.currency ?: "INR",
                referenceNumber = response.referenceNumber,
                description = response.description ?: ""
            )
        } else {
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