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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AddTransactionActivity : AppCompatActivity() {
    private lateinit var calendar: Calendar
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.add_item_transaction)

        val nameInput = findViewById<EditText>(R.id.transactionNameInput)
        val amountInput = findViewById<EditText>(R.id.transactionAmountInput)
        val dateInput = findViewById<EditText>(R.id.transactionDateInput)
        val categorySpinner = findViewById<Spinner>(R.id.transactionCategorySpinner)
        val merchantInput = findViewById<EditText>(R.id.transactionMerchantInput)
        val descriptionInput = findViewById<EditText>(R.id.transactionDescriptionInput)
        val saveButton = findViewById<Button>(R.id.saveTransactionButton)

        // Initialize calendar
        calendar = Calendar.getInstance()

        // Set up the Spinner for categories
        val categories = resources.getStringArray(R.array.transaction_categories)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter

        // Handle edit mode
        val editMode = intent.getBooleanExtra("EDIT_MODE", false)
        var transactionId = 0

        if (editMode) {
            // Set title for edit mode
            title = "Edit Transaction"

            // Get transaction details from intent
            transactionId = intent.getIntExtra("TRANSACTION_ID", 0)
            nameInput.setText(intent.getStringExtra("TRANSACTION_NAME"))
            amountInput.setText(intent.getDoubleExtra("TRANSACTION_AMOUNT", 0.0).toString())

            // Handle date
            val timestamp = intent.getLongExtra("TRANSACTION_DATE", System.currentTimeMillis())
            calendar.timeInMillis = timestamp
            dateInput.setText(dateFormat.format(calendar.time))

            // Set category
            val category = intent.getStringExtra("TRANSACTION_CATEGORY")
            val categoryPosition = categories.indexOf(category)
            if (categoryPosition != -1) {
                categorySpinner.setSelection(categoryPosition)
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
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
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
            // Validate inputs
            if (nameInput.text.isBlank() || amountInput.text.isBlank() || dateInput.text.isBlank()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val amount = try {
                amountInput.text.toString().toDouble()
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val data = Intent().apply {
                putExtra("id", transactionId)
                putExtra("name", nameInput.text.toString())
                putExtra("amount", amount)
                putExtra("date", calendar.timeInMillis)
                putExtra("category", categorySpinner.selectedItem.toString())
                putExtra("merchant", merchantInput.text.toString())
                putExtra("description", descriptionInput.text.toString())
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }
}