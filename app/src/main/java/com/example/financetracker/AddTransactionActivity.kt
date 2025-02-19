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
    private lateinit var dateInput: EditText
    private lateinit var calendar: Calendar
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.add_item_transaction)

        val nameInput = findViewById<EditText>(R.id.transactionNameInput)
        val amountInput = findViewById<EditText>(R.id.transactionAmountInput)
        dateInput = findViewById(R.id.transactionDateInput)
        val categorySpinner = findViewById<Spinner>(R.id.transactionCategorySpinner)
        val saveButton = findViewById<Button>(R.id.saveTransactionButton)

        // Initialize calendar
        calendar = Calendar.getInstance()

        // Set up the Spinner for categories
        ArrayAdapter.createFromResource(
            this,
            R.array.transaction_categories,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            categorySpinner.adapter = adapter
        }

        // Set initial date
        updateDateInView()

        // Set up Date Picker
        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, monthOfYear, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, monthOfYear)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            updateDateInView()
        }

        // Show DatePickerDialog when clicking the date field
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
                putExtra("name", nameInput.text.toString())
                putExtra("amount", amount)
                putExtra("date", dateInput.text.toString())
                putExtra("category", categorySpinner.selectedItem.toString())
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    private fun updateDateInView() {
        dateInput.setText(dateFormat.format(calendar.time))
    }
}