package com.example.financetracker

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.utils.CategoryUtils
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.firebase.auth.FirebaseAuth
// Removed Firestore import as saving is via ViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
// Removed Date import as not strictly needed here
import java.util.Locale

class AddTransactionActivity : AppCompatActivity() {

    // *** Define Constant for the Extra Key ***
    companion object {
        // Use this exact key when calling putExtra in TransactionActivity
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
    }

    private lateinit var calendar: Calendar
    private lateinit var auth: FirebaseAuth
    private lateinit var viewModel: TransactionViewModel // Assuming you initialize this correctly
    private val displayDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private lateinit var dateInput: EditText

    // Find views (ensure these IDs match your R.layout.add_item_transaction)
    private lateinit var nameInput: EditText
    private lateinit var amountInput: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var merchantInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var saveButton: Button

    private val TAG = "AddTransactionActivity"

    // Variable to hold the ID if editing, 0L otherwise
    private var editingTransactionId: Long = 0L
    private var isEditMode: Boolean = false // Derived from ID check

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.add_item_transaction) // Verify this is your correct layout

        // --- Initialize ---
        auth = FirebaseAuth.getInstance()
        // Ensure ViewModel is initialized correctly (e.g., using ViewModelProvider)
        viewModel = TransactionViewModel(
            TransactionDatabase.getDatabase(applicationContext),
            application
        )
        calendar = Calendar.getInstance()
        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)

        // --- Find Views ---
        nameInput = findViewById(R.id.transactionNameInput)
        amountInput = findViewById(R.id.transactionAmountInput)
        dateInput = findViewById(R.id.transactionDateInput)
        categorySpinner = findViewById(R.id.transactionCategorySpinner)
        merchantInput = findViewById(R.id.transactionMerchantInput)
        descriptionInput = findViewById(R.id.transactionDescriptionInput)
        saveButton = findViewById(R.id.saveTransactionButton)
        // Find cancel button if you have one

        // --- Check for Edit Mode using Long ID Extra ---
        editingTransactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, 0L) // Default to 0L
        isEditMode = editingTransactionId != 0L // Determine edit mode based on ID presence

        // --- Load Categories ---
        var initialCategory: String? = null
        if (isEditMode) {
            // If passing category via intent (less ideal than fetching)
            initialCategory = intent.getStringExtra("TRANSACTION_CATEGORY")
        }
        loadCategories(userId, initialCategory) // Encapsulate category loading

        // --- Populate Fields / Set Title ---
        if (isEditMode) {
            title = getString(R.string.title_edit_transaction) // Use string resource
            populateFieldsForEdit()
            // Disable fields if needed (as per previous requirement)
            nameInput.isEnabled = false
            amountInput.isEnabled = false
            descriptionInput.isEnabled = false
            dateInput.isEnabled = false
        } else {
            title = getString(R.string.title_add_transaction) // Use string resource
            updateDateEditText() // Set current date display only for new transactions
            // Ensure fields are enabled for add mode
            nameInput.isEnabled = true
            amountInput.isEnabled = true
            descriptionInput.isEnabled = true
            dateInput.isEnabled = true
        }

        // --- Setup UI Listeners ---
        setupDatePicker()
        setupSaveButton(userId)
        // setupCancelButton() if applicable

    } // End onCreate

    // --- Helper Functions ---

    private fun loadCategories(userId: String, categoryToSelect: String?) {
        lifecycleScope.launch {
            try {
                CategoryUtils.initializeCategories(this@AddTransactionActivity)
                CategoryUtils.loadCategoriesToSpinner(
                    this@AddTransactionActivity,
                    categorySpinner,
                    userId,
                    categoryToSelect // Pass the category to pre-select
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading categories", e)
                Toast.makeText(this@AddTransactionActivity, "Error loading categories: ${e.message}", Toast.LENGTH_SHORT).show()
                // Fallback...
                val fallbackCategories = arrayOf("Food", "Shopping", "Others")
                val adapter = ArrayAdapter(this@AddTransactionActivity, android.R.layout.simple_spinner_item, fallbackCategories)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                categorySpinner.adapter = adapter
                // Try selecting in fallback
                if (categoryToSelect != null) {
                    val pos = fallbackCategories.indexOf(categoryToSelect)
                    if (pos >= 0) categorySpinner.setSelection(pos)
                }
            }
        }
    }

    private fun populateFieldsForEdit() {
        // Fetching the full Transaction object by editingTransactionId using the ViewModel
        // is the recommended way. For now, continuing with using extras passed via Intent:
        nameInput.setText(intent.getStringExtra("TRANSACTION_NAME") ?: "")
        amountInput.setText(intent.getDoubleExtra("TRANSACTION_AMOUNT", 0.0).toString())
        val timestamp = intent.getLongExtra("TRANSACTION_DATE", System.currentTimeMillis())
        calendar.timeInMillis = timestamp
        updateDateEditText() // Display the existing date
        merchantInput.setText(intent.getStringExtra("TRANSACTION_MERCHANT") ?: "")
        descriptionInput.setText(intent.getStringExtra("TRANSACTION_DESCRIPTION") ?: "")
        // Category selection is handled within loadCategories
    }

    private fun setupDatePicker() {
        dateInput.isFocusable = false
        dateInput.isFocusableInTouchMode = false

        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            updateDateEditText()
        }

        dateInput.setOnClickListener {
            // Only show picker if the field is enabled (i.e., not in edit mode based on previous logic)
            if (dateInput.isEnabled) {
                val dialog = DatePickerDialog(
                    this, dateSetListener,
                    calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
                )
                dialog.datePicker.maxDate = System.currentTimeMillis()
                dialog.show()
            }
        }
    }

    private fun setupSaveButton(userId: String) {
        saveButton.setOnClickListener {
            // Read values from currently ENABLED fields (or all fields if validation needed)
            val name = nameInput.text.toString().trim()
            val amount = amountInput.text.toString().toDoubleOrNull()
            val merchant = merchantInput.text.toString().trim()
            val description = descriptionInput.text.toString().trim()
            val selectedCategoryItem = categorySpinner.selectedItem

            // --- Validation ---
            // Only validate fields that are meant to be edited or are required for add mode
            if (!isEditMode && name.isEmpty()) {
                nameInput.error = getString(R.string.purpose_cannot_be_empty)
                return@setOnClickListener
            }
            if (!isEditMode && (amount == null || amount <= 0)) {
                amountInput.error = getString(R.string.enter_valid_positive_amount)
                return@setOnClickListener
            }
            if (selectedCategoryItem == null || categorySpinner.selectedItemPosition == Spinner.INVALID_POSITION) {
                Toast.makeText(this, R.string.select_category, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val category = selectedCategoryItem.toString()
            // --- End Validation ---


            // Combine date and time only if adding a new transaction
            // For edits, the original timestamp is preserved in 'calendar'
            val dateTimestamp: Long
            if (!isEditMode) {
                val currentTime = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, currentTime.get(Calendar.HOUR_OF_DAY))
                calendar.set(Calendar.MINUTE, currentTime.get(Calendar.MINUTE))
                calendar.set(Calendar.SECOND, currentTime.get(Calendar.SECOND))
                calendar.set(Calendar.MILLISECOND, currentTime.get(Calendar.MILLISECOND))
                dateTimestamp = calendar.timeInMillis
            } else {
                dateTimestamp = calendar.timeInMillis // Use the timestamp loaded during populateFieldsForEdit
            }


            // Create Transaction object using the Long ID
            val transaction = Transaction(
                id = editingTransactionId, // Use the Long ID (0L for new, actual ID for edit)
                // Use original values for non-editable fields if editing
                name = if (isEditMode) (intent.getStringExtra("TRANSACTION_NAME") ?: "") else name,
                amount = if (isEditMode) intent.getDoubleExtra("TRANSACTION_AMOUNT", 0.0) else amount!!, // Non-null asserted for add mode due to validation
                date = dateTimestamp,
                category = category, // Always use currently selected category
                merchant = merchant, // Always use currently entered merchant
                description = if (isEditMode) (intent.getStringExtra("TRANSACTION_DESCRIPTION") ?: "") else description,
                userId = userId
                // Handle documentId if needed for Firestore updates
            )

            // Save using ViewModel
            if (isEditMode) {
                Log.d(TAG, "Updating transaction ID: ${transaction.id}")
                // If only category/merchant were editable, call the specific update method
                // viewModel.updateTransactionCategoryAndMerchant(transaction.id, transaction.category, transaction.merchant)
                // If using the full update (preserving other fields):
                viewModel.updateTransaction(transaction)
            } else {
                Log.d(TAG, "Adding new transaction")
                viewModel.addTransaction(transaction)
            }

            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun updateDateEditText() {
        dateInput.setText(displayDateFormat.format(calendar.time))
    }

} // End Activity