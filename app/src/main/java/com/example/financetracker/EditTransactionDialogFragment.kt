package com.example.financetracker

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.financetracker.database.entity.Transaction

class EditTransactionDialogFragment(
    private val transaction: Transaction,
    private val onUpdate: (Transaction) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_transaction, null)

        val nameEditText: EditText = view.findViewById(R.id.editTransactionName)
        val amountEditText: EditText = view.findViewById(R.id.editTransactionAmount)
        val categorySpinner: Spinner = view.findViewById(R.id.editTransactionCategory)

        // Set up the category spinner
        val categories = resources.getStringArray(R.array.transaction_categories)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter

        // Set initial values
        nameEditText.setText(transaction.name)
        amountEditText.setText(transaction.amount.toString())

        // Set the spinner selection to match the current category
        val categoryPosition = categories.indexOf(transaction.category)
        if (categoryPosition != -1) {
            categorySpinner.setSelection(categoryPosition)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_transaction)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val updatedTransaction = transaction.copy(
                    name = nameEditText.text.toString(),
                    amount = amountEditText.text.toString().toDoubleOrNull() ?: transaction.amount,
                    category = categorySpinner.selectedItem.toString()
                )
                onUpdate(updatedTransaction)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}