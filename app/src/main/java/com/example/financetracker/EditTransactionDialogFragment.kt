package com.example.financetracker

import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.financetracker.database.entity.Transaction
import java.text.SimpleDateFormat
import java.util.*

class EditTransactionDialogFragment(
    private val transaction: Transaction,
    private val onUpdate: (Transaction) -> Unit
) : DialogFragment() {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private lateinit var calendar: Calendar

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_transaction, null)

        val nameEditText: EditText = view.findViewById(R.id.editTransactionName)
        val amountEditText: EditText = view.findViewById(R.id.editTransactionAmount)
        val dateEditText: EditText = view.findViewById(R.id.editTransactionDate)
        val categorySpinner: Spinner = view.findViewById(R.id.editTransactionCategory)

        // Initialize calendar with transaction date
        calendar = Calendar.getInstance()
        calendar.timeInMillis = transaction.date

        // Set up the category spinner
        val categories = resources.getStringArray(R.array.transaction_categories)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter

        // Set initial values
        nameEditText.setText(transaction.name)
        amountEditText.setText(transaction.amount.toString())
        dateEditText.setText(dateFormat.format(Date(transaction.date)))

        // Make date field read-only
        dateEditText.isFocusable = false
        dateEditText.isFocusableInTouchMode = false

        // Set up date picker dialog
        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            dateEditText.setText(dateFormat.format(calendar.time))
        }

        // Show date picker when clicking the date field
        dateEditText.setOnClickListener {
            DatePickerDialog(
                requireContext(),
                dateSetListener,
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Set the spinner selection to match the current category
        val categoryPosition = categories.indexOf(transaction.category)
        if (categoryPosition != -1) {
            categorySpinner.setSelection(categoryPosition)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_transaction)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                // Validate inputs
                if (nameEditText.text.isBlank() || amountEditText.text.isBlank()) {
                    Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val amount = try {
                    amountEditText.text.toString().toDouble()
                } catch (e: NumberFormatException) {
                    Toast.makeText(context, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val updatedTransaction = transaction.copy(
                    name = nameEditText.text.toString(),
                    amount = amount,
                    date = calendar.timeInMillis,
                    category = categorySpinner.selectedItem.toString()
                )
                onUpdate(updatedTransaction)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}