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

        val merchantEditText: EditText = view.findViewById(R.id.editTransactionMerchant)
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
        merchantEditText.setText(transaction.merchant)

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
                if (merchantEditText.text.isBlank()) {
                    Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val updatedTransaction = transaction.copy(
                    merchant = merchantEditText.text.toString(),
                    category = categorySpinner.selectedItem.toString()
                )
                onUpdate(updatedTransaction)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}