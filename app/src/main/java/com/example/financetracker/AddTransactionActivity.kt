package com.example.financetracker

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
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
import java.util.Locale

class AddTransactionActivity : AppCompatActivity() {
    private lateinit var calendar: Calendar
    private lateinit var auth: FirebaseAuth
    private lateinit var viewModel: TransactionViewModel // Add this
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.add_item_transaction)

        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()

        // Initialize ViewModel
        viewModel = TransactionViewModel(
            TransactionDatabase.getDatabase(applicationContext),
            application
        )

        val nameInput = findViewById<EditText>(R.id.transactionNameInput)
        val amountInput = findViewById<EditText>(R.id.transactionAmountInput)
        val dateInput = findViewById<EditText>(R.id.transactionDateInput)
        val categorySpinner = findViewById<Spinner>(R.id.transactionCategorySpinner)
        val merchantInput = findViewById<EditText>(R.id.transactionMerchantInput)
        val descriptionInput = findViewById<EditText>(R.id.transactionDescriptionInput)
        val saveButton = findViewById<Button>(R.id.saveTransactionButton)

        // Initialize calendar
        calendar = Calendar.getInstance()

        // Load categories into spinner
        loadCategories(categorySpinner)

        // Handle edit mode
        val editMode = intent.getBooleanExtra("EDIT_MODE", false)
        var transactionId = 0L

        if (editMode) {
            // Set title for edit mode
            title = "Edit Transaction"

            // Get transaction details from intent
            transactionId = intent.getLongExtra("TRANSACTION_ID", 0L)
            nameInput.setText(intent.getStringExtra("TRANSACTION_NAME"))
            amountInput.setText(intent.getDoubleExtra("TRANSACTION_AMOUNT", 0.0).toString())

            // Handle date
            val timestamp = intent.getLongExtra("TRANSACTION_DATE", System.currentTimeMillis())
            calendar.timeInMillis = timestamp
            dateInput.setText(dateFormat.format(calendar.time))

            // Set category
            val category = intent.getStringExtra("TRANSACTION_CATEGORY")
            lifecycleScope.launch {
                CategoryUtils.loadCategoriesToSpinner(
                    this@AddTransactionActivity,
                    categorySpinner,
                    auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext),
                    category
                )
            }

            merchantInput.setText(intent.getStringExtra("TRANSACTION_MERCHANT"))
            descriptionInput.setText(intent.getStringExtra("TRANSACTION_DESCRIPTION"))
        } else {
            title = "Add Transaction"
            // Set current date for new transactions
            dateInput.setText(dateFormat.format(calendar.time))
        }

        // Make date field read-only
        dateInput.isFocusable = false
        dateInput.isFocusableInTouchMode = false

        // Set up Date Picker
        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            calendar.set(year, month, dayOfMonth)
            dateInput.setText(dateFormat.format(calendar.time))
        }

        dateInput.setOnClickListener {
            DatePickerDialog(
                this,
                dateSetListener,
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        saveButton.setOnClickListener {
            val name = nameInput.text.toString()
            val amount = amountInput.text.toString().toDoubleOrNull() ?: 0.0
            val date = calendar.timeInMillis
            val category = categorySpinner.selectedItem.toString()
            val merchant = merchantInput.text.toString()
            val description = descriptionInput.text.toString()

            val transaction = Transaction(
                id = transactionId,
                name = name,
                amount = amount,
                date = date,
                category = category,
                merchant = merchant,
                description = description
            )

            if (editMode) {
                viewModel.updateTransaction(transaction)
            } else {
                viewModel.addTransaction(transaction)
            }

            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun loadCategories(categorySpinner: Spinner) {
        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)
        lifecycleScope.launch {
            CategoryUtils.loadCategoriesToSpinner(
                this@AddTransactionActivity,
                categorySpinner,
                userId
            )
        }
    }
}