package com.example.financetracker

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

class AddTransactionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.add_item_transaction)

        val nameInput = findViewById<EditText>(R.id.transactionNameInput)
        val amountInput = findViewById<EditText>(R.id.transactionAmountInput)
        val dateInput = findViewById<EditText>(R.id.transactionDateInput)
        val categorySpinner = findViewById<Spinner>(R.id.transactionCategorySpinner)
        val saveButton = findViewById<Button>(R.id.saveTransactionButton)

        // Set up the Spinner for categories
        ArrayAdapter.createFromResource(
            this,
            R.array.transaction_categories,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            categorySpinner.adapter = adapter
        }

        // Set up Date Picker for date input
        val calendar = Calendar.getInstance()
        val datePickerDialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                dateInput.setText("$year-${month + 1}-$dayOfMonth")
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        dateInput.setOnClickListener {
            datePickerDialog.show()
        }

        saveButton.setOnClickListener {
            val data = Intent().apply {
                putExtra("name", nameInput.text.toString())
                putExtra("amount", amountInput.text.toString().toDouble())
                putExtra("date", dateInput.text.toString())
                putExtra("category", categorySpinner.selectedItem.toString())
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }
}