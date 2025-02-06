package com.example.financetracker

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class AddTransactionActivity : AppCompatActivity() {

    private lateinit var nameEditText: EditText
    private lateinit var amountEditText: EditText
    private lateinit var dateEditText: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_transaction)

        // Initialize views
        nameEditText = findViewById(R.id.editTextName)
        amountEditText = findViewById(R.id.editTextAmount)
        dateEditText = findViewById(R.id.editTextDate)
        categorySpinner = findViewById(R.id.spinnerCategory)
        saveButton = findViewById(R.id.buttonSave)
        cancelButton = findViewById(R.id.cancel_transaction_button)


        val categories = resources.getStringArray(R.array.transaction_categories)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter

        // Handle date picker
        dateEditText.setOnClickListener {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)

            val datePicker = DatePickerDialog(this, { _, selectedYear, selectedMonth, selectedDay ->
                val selectedDate = "$selectedYear-${selectedMonth + 1}-$selectedDay"
                dateEditText.setText(selectedDate)
            }, year, month, day)

            datePicker.show()
        }

        // Handle save button
        saveButton.setOnClickListener {
            val name = nameEditText.text.toString()
            val amount = amountEditText.text.toString().toDoubleOrNull() ?: 0.0
            val date = dateEditText.text.toString()
            val category = categorySpinner.selectedItem.toString()

            val intent = Intent()
            intent.putExtra("name", name)
            intent.putExtra("amount", amount)
            intent.putExtra("date", date)
            intent.putExtra("category", category)

            setResult(RESULT_OK, intent)
            finish() // Close the activity and return the result
        }

        // Handle cancel button
        cancelButton.setOnClickListener {
            finish() // Close the activity without saving
        }
    }
}
