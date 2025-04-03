package com.example.financetracker

import android.app.Activity
import android.app.DatePickerDialog
// ** TimePickerDialog import is NOT needed **
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
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AddTransactionActivity : AppCompatActivity() {
    // Calendar to hold the DATE selected by the user
    private lateinit var calendar: Calendar
    private lateinit var auth: FirebaseAuth
    private lateinit var viewModel: TransactionViewModel
    // Only need date format for display in this activity
    private val displayDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private lateinit var dateInput: EditText
    // Remove timeInput reference

    private val firestore = FirebaseFirestore.getInstance()
    private val TAG = "AddTransactionActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure you are using the correct layout resource ID
        // Assuming add_item_transaction.xml is the correct one based on previous context
        setContentView(R.layout.add_item_transaction)

        auth = FirebaseAuth.getInstance()
        viewModel = TransactionViewModel(
            TransactionDatabase.getDatabase(applicationContext),
            application
        )

        // --- Find Views (Use IDs from your specific layout) ---
        val nameInput = findViewById<EditText>(R.id.transactionNameInput) // Example ID
        val amountInput = findViewById<EditText>(R.id.transactionAmountInput) // Example ID
        dateInput = findViewById(R.id.transactionDateInput) // Example ID
        // REMOVE: val timeInput = findViewById<EditText>(R.id.editTextTime)
        val categorySpinner = findViewById<Spinner>(R.id.transactionCategorySpinner) // Example ID
        val merchantInput = findViewById<EditText>(R.id.transactionMerchantInput) // Example ID
        val descriptionInput = findViewById<EditText>(R.id.transactionDescriptionInput) // Example ID
        val saveButton = findViewById<Button>(R.id.saveTransactionButton) // Example ID
        // Find Cancel button if it exists
        // val cancelButton = findViewById<Button>(R.id.cancel_transaction_button)

        // --- End Find Views ---

        calendar = Calendar.getInstance() // Initialize calendar

        // Load categories... (existing logic remains the same)
        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)
        lifecycleScope.launch {
            try {
                CategoryUtils.initializeCategories(this@AddTransactionActivity)
                CategoryUtils.loadCategoriesToSpinner(
                    this@AddTransactionActivity,
                    categorySpinner,
                    userId,
                    if (intent.getBooleanExtra("EDIT_MODE", false))
                        intent.getStringExtra("TRANSACTION_CATEGORY")
                    else null
                )
            } catch (e: Exception) { /*... error handling ...*/
                Log.e(TAG, "Error loading categories", e)
                Toast.makeText(this@AddTransactionActivity, "Error loading categories: ${e.message}", Toast.LENGTH_SHORT).show()
                val fallbackCategories = arrayOf("Food", "Shopping", "Others")
                val adapter = ArrayAdapter(this@AddTransactionActivity, android.R.layout.simple_spinner_item, fallbackCategories)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                categorySpinner.adapter = adapter
            }
        }

        // Handle edit mode
        val editMode = intent.getBooleanExtra("EDIT_MODE", false)
        var transactionId = 0

        if (editMode) {
            title = "Edit Transaction"
            transactionId = intent.getIntExtra("TRANSACTION_ID", 0) // Use getIntExtra directly if ID is Int
            nameInput.setText(intent.getStringExtra("TRANSACTION_NAME"))
            amountInput.setText(intent.getDoubleExtra("TRANSACTION_AMOUNT", 0.0).toString())

            // Set calendar from existing full timestamp
            val timestamp = intent.getLongExtra("TRANSACTION_DATE", System.currentTimeMillis())
            calendar.timeInMillis = timestamp
            updateDateEditText() // Update date display only

            val category = intent.getStringExtra("TRANSACTION_CATEGORY")
            lifecycleScope.launch {
                CategoryUtils.loadCategoriesToSpinner(this@AddTransactionActivity, categorySpinner, userId, category)
            }

            merchantInput.setText(intent.getStringExtra("TRANSACTION_MERCHANT"))
            descriptionInput.setText(intent.getStringExtra("TRANSACTION_DESCRIPTION"))
        } else {
            title = "Add Transaction"
            updateDateEditText() // Set current date display
        }

        // Make date input non-focusable
        dateInput.isFocusable = false
        dateInput.isFocusableInTouchMode = false

        // --- Date Picker Setup (Prevent Future Dates) ---
        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            // Keep time components as they were, they'll be overridden on save
            updateDateEditText()
        }

        dateInput.setOnClickListener {
            val dialog = DatePickerDialog(
                this,
                dateSetListener,
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            dialog.datePicker.maxDate = System.currentTimeMillis() // Prevent future dates
            dialog.show()
        }
        // --- End Date Picker Setup ---

        // --- Remove Time Picker Setup ---

        saveButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val amount = amountInput.text.toString().toDoubleOrNull()

            // Validation...
            if (name.isEmpty()) { nameInput.error = "Purpose cannot be empty"; return@setOnClickListener }
            if (amount == null || amount <= 0) { amountInput.error = "Please enter a valid positive amount"; return@setOnClickListener }
            if (categorySpinner.selectedItemPosition == Spinner.INVALID_POSITION || categorySpinner.selectedItem == null) {
                Toast.makeText(this, "Please select a category", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }


            // *** Combine Selected Date with Current System Time ***
            val currentTime = Calendar.getInstance() // Get current time NOW
            // Set ONLY the time components from currentTime onto the calendar holding the selected DATE
            calendar.set(Calendar.HOUR_OF_DAY, currentTime.get(Calendar.HOUR_OF_DAY))
            calendar.set(Calendar.MINUTE, currentTime.get(Calendar.MINUTE))
            calendar.set(Calendar.SECOND, currentTime.get(Calendar.SECOND))
            calendar.set(Calendar.MILLISECOND, currentTime.get(Calendar.MILLISECOND))
            // Now 'calendar.timeInMillis' has the selected date + current system time
            val dateTimestamp = calendar.timeInMillis
            // ***---------------------------------------------***

            val category = categorySpinner.selectedItem.toString()
            val merchant = merchantInput.text.toString()
            val description = descriptionInput.text.toString()

            val transaction = Transaction(
                // Use existing ID if editing, otherwise Room handles 0 as autoGenerate
                id = if (editMode) transactionId else 0,
                name = name,
                amount = amount,
                date = dateTimestamp, // Save the combined timestamp
                category = category,
                merchant = merchant,
                description = description,
                userId = userId
            )

            // Save using ViewModel
            if (editMode) {
                Log.d(TAG, "Updating transaction: $transaction")
                viewModel.updateTransaction(transaction)
            } else {
                Log.d(TAG, "Adding transaction: $transaction")
                viewModel.addTransaction(transaction)
            }

            // Set result and finish (No need to pass data back if list updates via ViewModel)
            setResult(Activity.RESULT_OK)
            finish()
        }

        // Setup Cancel Button if it exists
        // cancelButton?.setOnClickListener {
        //     setResult(Activity.RESULT_CANCELED)
        //     finish()
        // }
    } // End onCreate

    // Helper to update date EditText
    private fun updateDateEditText() {
        dateInput.setText(displayDateFormat.format(calendar.time))
    }

    // Remove updateTimeEditText helper
} // End Activity