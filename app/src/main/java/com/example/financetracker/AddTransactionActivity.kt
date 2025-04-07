package com.example.financetracker

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context // Added for CategoryUtils placeholder
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView // **** IMPORT AutoCompleteTextView ****
import android.widget.Button
import android.widget.EditText
// import android.widget.Spinner // **** REMOVE Spinner ****
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.utils.CategoryUtils
// Removed direct CategoryUtils import, using placeholder below
// import com.example.financetracker.utils.CategoryUtils
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// --- Placeholder for CategoryUtils ---
// TODO: Replace this with your actual CategoryUtils implementation
object CategoryUtilsPlaceholder {
    // Simulate fetching categories (replace with DB/Firestore call)
    suspend fun getCategoryList(userId: String): List<String> {
        Log.d("CategoryUtilsPlaceholder", "Fetching categories for user: $userId")
        // Simulate network/DB delay
        kotlinx.coroutines.delay(300)
        // Return a hardcoded list for example purposes
        return listOf("Food", "Transport", "Shopping", "Bills", "Salary", "Entertainment", "Other")
    }

    // Simulate initialization if needed
    fun initializeCategories(context: Context) {
        Log.d("CategoryUtilsPlaceholder", "Initializing categories (if needed)... Context: $context")
        // Add any one-time setup logic here if required
    }
}
// --- End Placeholder ---


class AddTransactionActivity : AppCompatActivity() {

    companion object {
        // Keys for Intent Extras (Keep as is)
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
        const val EXTRA_TRANSACTION_NAME = "TRANSACTION_NAME"
        const val EXTRA_TRANSACTION_AMOUNT = "TRANSACTION_AMOUNT"
        const val EXTRA_TRANSACTION_DATE = "TRANSACTION_DATE"
        const val EXTRA_TRANSACTION_CATEGORY = "TRANSACTION_CATEGORY"
        const val EXTRA_TRANSACTION_MERCHANT = "TRANSACTION_MERCHANT"
        const val EXTRA_TRANSACTION_DESCRIPTION = "TRANSACTION_DESCRIPTION"
    }

    // --- Member Variables ---
    private lateinit var calendar: Calendar
    private lateinit var auth: FirebaseAuth
    private lateinit var viewModel: TransactionViewModel // Ensure proper initialization
    private val displayDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val TAG = "AddTransactionActivity"

    // --- View References (Matching corrected ConstraintLayout XML IDs) ---
    private lateinit var transactionNameInputLayout: TextInputLayout
    private lateinit var transactionAmountInputLayout: TextInputLayout
    private lateinit var transactionDateInputLayout: TextInputLayout
    private lateinit var transactionDateEditText: EditText // Inner EditText for Date

    // **** UPDATED CATEGORY VIEWS ****
    private lateinit var transactionCategoryInputLayout: TextInputLayout
    private lateinit var transactionCategoryAutoCompleteTextView: AutoCompleteTextView
    // **** END UPDATED CATEGORY VIEWS ****

    private lateinit var transactionMerchantInputLayout: TextInputLayout
    private lateinit var transactionDescriptionInputLayout: TextInputLayout
    private lateinit var saveTransactionButton: Button // Or MaterialButton

    // --- References to INNER EditTexts (via Layouts) ---
    // Relies on TextInputEditText existing inside the Layouts in XML
    private val transactionNameEditText: EditText? get() = transactionNameInputLayout.editText
    private val transactionAmountEditText: EditText? get() = transactionAmountInputLayout.editText
    // transactionDateEditText is accessed directly as we found its specific ID
    private val transactionMerchantEditText: EditText? get() = transactionMerchantInputLayout.editText
    private val transactionDescriptionEditText: EditText? get() = transactionDescriptionInputLayout.editText

    // --- State Variables ---
    private var editingTransactionId: Long = 0L
    private var isEditMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure this layout matches your corrected ConstraintLayout XML file
        setContentView(R.layout.add_item_transaction)

        // --- Initialization ---
        auth = FirebaseAuth.getInstance()
        // --- IMPORTANT: Initialize ViewModel correctly! ---
        viewModel = TransactionViewModel(
            TransactionDatabase.getDatabase(applicationContext),
            application
        ) // Replace with ViewModelProvider
        calendar = Calendar.getInstance()
        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)

        // --- Find Views (Using IDs from ConstraintLayout XML) ---
        transactionNameInputLayout = findViewById(R.id.transactionNameInputLayout)
        transactionAmountInputLayout = findViewById(R.id.transactionAmountInputLayout)
        transactionDateInputLayout = findViewById(R.id.transactionDateInputLayout)
        transactionDateEditText = findViewById(R.id.transactionDateEditText) // Find inner Date EditText

        // **** FIND UPDATED CATEGORY VIEWS ****
        transactionCategoryInputLayout = findViewById(R.id.transactionCategoryInputLayout)
        transactionCategoryAutoCompleteTextView = findViewById(R.id.transactionCategoryAutoCompleteTextView)
        // **** END FIND UPDATED CATEGORY VIEWS ****

        transactionMerchantInputLayout = findViewById(R.id.transactionMerchantInputLayout)
        transactionDescriptionInputLayout = findViewById(R.id.transactionDescriptionInputLayout)
        saveTransactionButton = findViewById(R.id.saveTransactionButton)


        // --- Determine Mode ---
        editingTransactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, 0L)
        isEditMode = editingTransactionId != 0L

        // --- Load Categories ---
        val initialCategoryToSelect = if (isEditMode) {
            intent.getStringExtra(EXTRA_TRANSACTION_CATEGORY)
        } else {
            null
        }
        // Uses the placeholder CategoryUtils below
        loadCategories(userId, initialCategoryToSelect)

        // --- Configure UI based on Mode ---
        if (isEditMode) {
            title = getString(R.string.title_edit_transaction)
            populateFieldsForEdit()
            // Disable non-editable fields
            transactionNameInputLayout.isEnabled = false
            transactionAmountInputLayout.isEnabled = false
            transactionDateInputLayout.isEnabled = false // Disables inner EditText too
            transactionDescriptionInputLayout.isEnabled = false
            // Keep category and merchant editable
            transactionCategoryInputLayout.isEnabled = true
            transactionMerchantInputLayout.isEnabled = true
        } else {
            title = getString(R.string.title_add_transaction)
            updateDateEditText() // Set current date for new transactions
            // Ensure fields are enabled
            transactionNameInputLayout.isEnabled = true
            transactionAmountInputLayout.isEnabled = true
            transactionDateInputLayout.isEnabled = true
            transactionDescriptionInputLayout.isEnabled = true
            transactionCategoryInputLayout.isEnabled = true
            transactionMerchantInputLayout.isEnabled = true
        }

        // --- Setup UI Listeners ---
        setupDatePicker()
        setupSaveButton(userId)

    } // End onCreate


    // --- Helper Functions ---

    // **** UPDATED: Loads categories into AutoCompleteTextView ****
    // Inside AddTransactionActivity.kt -> loadCategories function

    private fun loadCategories(userId: String, categoryToSelect: String?) {
        lifecycleScope.launch {
            try {
                // Call your actual CategoryUtils initialization if needed (placeholder shown)
                CategoryUtilsPlaceholder.initializeCategories(this@AddTransactionActivity) // Replace if using real CategoryUtils

                // **** USE THE NEW FUNCTION ****
                // Fetch category names using the new utility function
                val categoriesList = CategoryUtils.getCategoryNamesForUser(this@AddTransactionActivity, userId) // Use your actual CategoryUtils object
                // **** END USE NEW FUNCTION ****


                if (categoriesList.isEmpty()) {
                    // Handle empty list case (show error, disable input etc.)
                    Log.w(TAG, "No categories found for user $userId.")
                    Toast.makeText(this@AddTransactionActivity, R.string.no_categories_found, Toast.LENGTH_LONG).show()
                    transactionCategoryInputLayout.error = getString(R.string.no_categories_setup)
                    transactionCategoryInputLayout.isEnabled = false
                    return@launch
                } else {
                    transactionCategoryInputLayout.isEnabled = true
                    transactionCategoryInputLayout.error = null
                }

                // Create Adapter
                val adapter = ArrayAdapter(
                    this@AddTransactionActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    categoriesList
                )

                // Set Adapter and handle pre-selection (logic remains the same)
                transactionCategoryAutoCompleteTextView.setAdapter(adapter)
                // ... (rest of the pre-selection logic) ...

            } catch (e: Exception) {
                // ... (error handling) ...
            }
        }
    }

    // NOTE: Fallback function using Spinner is removed as we now use AutoCompleteTextView.
    // You can create a similar fallback for AutoCompleteTextView if required.

    // Populates fields when editing
    private fun populateFieldsForEdit() {
        // Uses the inner EditText references (nameEditText, amountEditText etc.)
        transactionNameEditText?.setText(intent.getStringExtra(EXTRA_TRANSACTION_NAME) ?: "")
        transactionAmountEditText?.setText(intent.getDoubleExtra(EXTRA_TRANSACTION_AMOUNT, 0.0).toString())
        transactionMerchantEditText?.setText(intent.getStringExtra(EXTRA_TRANSACTION_MERCHANT) ?: "")
        transactionDescriptionEditText?.setText(intent.getStringExtra(EXTRA_TRANSACTION_DESCRIPTION) ?: "")

        val timestamp = intent.getLongExtra(EXTRA_TRANSACTION_DATE, System.currentTimeMillis())
        calendar.timeInMillis = timestamp
        updateDateEditText()
        // Category is handled by loadCategories
    }

    // Sets up the Date Picker for the transactionDateEditText
    private fun setupDatePicker() {
        // Date EditText is not focusable/editable via keyboard (set in XML)
        // It's clickable (set in XML)

        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            updateDateEditText()
            transactionDateInputLayout.error = null // Clear error on the layout
        }

        // Set click listener on the EditText itself
        transactionDateEditText.setOnClickListener {
            if (transactionDateInputLayout.isEnabled) { // Check the layout's enabled state
                val dialog = DatePickerDialog(
                    this, dateSetListener,
                    calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
                )
                dialog.datePicker.maxDate = System.currentTimeMillis() // Prevent future dates
                dialog.show()
            }
        }
    }


    // **** UPDATED: setupSaveButton reads from AutoCompleteTextView ****
    private fun setupSaveButton(userId: String) {
        saveTransactionButton.setOnClickListener {
            // --- Clear previous errors ---
            transactionNameInputLayout.error = null
            transactionAmountInputLayout.error = null
            transactionDateInputLayout.error = null
            transactionCategoryInputLayout.error = null // Clear category error
            transactionMerchantInputLayout.error = null
            transactionDescriptionInputLayout.error = null

            // --- Read values ---
            val name = transactionNameEditText?.text.toString().trim()
            val amountString = transactionAmountEditText?.text.toString()
            val amount = amountString.toDoubleOrNull()
            val merchant = transactionMerchantEditText?.text.toString().trim()
            val description = transactionDescriptionEditText?.text.toString().trim()

            // **** READ CATEGORY FROM AUTOCOMPLETE TEXT VIEW ****
            val category = transactionCategoryAutoCompleteTextView.text.toString().trim() // Trim whitespace

            // --- Validation ---
            var isValid = true

            // Validate name (only if adding)
            if (!isEditMode && name.isEmpty()) {
                transactionNameInputLayout.error = getString(R.string.purpose_cannot_be_empty)
                isValid = false
            }

            // Validate amount (only if adding)
            if (!isEditMode) {
                if (amount == null || amount <= 0) {
                    transactionAmountInputLayout.error = getString(R.string.enter_valid_positive_amount)
                    isValid = false
                } else if (amountString.substringAfter('.', "").length > 2) {
                    transactionAmountInputLayout.error = getString(R.string.amount_max_two_decimals)
                    isValid = false
                }
            }

            // **** VALIDATE CATEGORY SELECTION ****
            val adapter = transactionCategoryAutoCompleteTextView.adapter
            var categoryIsValidInAdapter = false
            // Check if adapter exists and is not empty before iterating
            if (adapter != null && adapter.count > 0) {
                for (i in 0 until adapter.count) {
                    // Safely get item and convert to string for comparison
                    val item = adapter.getItem(i)?.toString()
                    if (item != null && item == category) {
                        categoryIsValidInAdapter = true
                        break
                    }
                }
            } else if (adapter == null || adapter.count == 0) {
                // If adapter is null or empty, maybe categories failed to load.
                // Treat non-empty input as invalid if there are no valid options.
                if (category.isNotEmpty()) {
                    categoryIsValidInAdapter = false // Cannot be valid if no options exist
                } else {
                    // If input is empty AND no categories loaded, maybe allow if category is optional?
                    // Or enforce selection:
                    categoryIsValidInAdapter = false // Enforce selection even if loading failed
                }
            }


            // Final check: category must not be empty AND must be found in the adapter (if adapter has items)
            if (category.isEmpty() || (!categoryIsValidInAdapter && adapter?.count ?: 0 > 0) ) {
                transactionCategoryInputLayout.error = getString(R.string.select_valid_category)
                if(category.isNotEmpty()){ // Give toast only if user typed something invalid
                    Toast.makeText(this, R.string.select_valid_category_toast, Toast.LENGTH_SHORT).show()
                }
                isValid = false
            }
            // **** END VALIDATE CATEGORY ****


            // Validate date (ensure field is not empty)
            if (transactionDateEditText.text.isNullOrEmpty()) {
                transactionDateInputLayout.error = getString(R.string.select_date_error)
                isValid = false
            }


            if (!isValid) {
                Log.w(TAG, "Validation failed.")
                return@setOnClickListener
            }
            // --- End Validation ---

            // --- Determine Timestamp (Logic remains the same) ---
            val dateTimestamp: Long
            if (!isEditMode) {
                // Combine selected date with current time for new transactions
                val currentTime = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, currentTime.get(Calendar.HOUR_OF_DAY))
                calendar.set(Calendar.MINUTE, currentTime.get(Calendar.MINUTE))
                calendar.set(Calendar.SECOND, 0) // Zero out seconds
                calendar.set(Calendar.MILLISECOND, 0) // Zero out milliseconds
                dateTimestamp = calendar.timeInMillis
            } else {
                // Use existing timestamp for edits (preserves original time)
                dateTimestamp = calendar.timeInMillis
            }

            // --- Create Transaction Object ---
            val transaction = Transaction(
                id = editingTransactionId, // 0L for new transaction
                name = if (isEditMode) (intent.getStringExtra(EXTRA_TRANSACTION_NAME) ?: name) else name,
                amount = if (isEditMode) intent.getDoubleExtra(EXTRA_TRANSACTION_AMOUNT, 0.0) else amount!!, // amount non-null due to validation
                date = dateTimestamp,
                category = category, // Use validated category from AutoCompleteTextView
                merchant = merchant,
                description = if (isEditMode) (intent.getStringExtra(EXTRA_TRANSACTION_DESCRIPTION) ?: description) else description,
                userId = userId
            )

            // --- Save using ViewModel ---
            lifecycleScope.launch { // Use coroutine scope
                try {
                    if (isEditMode) {
                        Log.d(TAG, "Updating transaction ID: ${transaction.id}")
                        viewModel.updateTransaction(transaction)
                        Toast.makeText(this@AddTransactionActivity, R.string.transaction_updated, Toast.LENGTH_SHORT).show()
                    } else {
                        Log.d(TAG, "Adding new transaction: ${transaction.name}")
                        viewModel.addTransaction(transaction)
                        Toast.makeText(this@AddTransactionActivity, R.string.transaction_saved, Toast.LENGTH_SHORT).show()
                    }
                    setResult(Activity.RESULT_OK)
                    finish() // Close activity on success
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving transaction", e)
                    Toast.makeText(this@AddTransactionActivity, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Updates the Date EditText field
    private fun updateDateEditText() {
        transactionDateEditText.setText(displayDateFormat.format(calendar.time))
    }

} // End Activity