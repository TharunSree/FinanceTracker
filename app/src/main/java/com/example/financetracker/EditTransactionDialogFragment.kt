package com.example.financetracker

import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.viewmodel.TransactionViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class EditTransactionDialogFragment(
    private val transaction: Transaction,
    private val onUpdate: (Transaction) -> Unit
) : DialogFragment() {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private lateinit var calendar: Calendar

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_edit_transaction, null) // Use your dialog layout

        val merchantEditText: EditText = view.findViewById(R.id.editTransactionMerchant)
        val categorySpinner: Spinner = view.findViewById(R.id.editTransactionCategory)
        // Add other fields if they exist in your dialog_edit_transaction.xml

        // Set initial values
        merchantEditText.setText(transaction.merchant)
        val transactionViewModel: TransactionViewModel by activityViewModels {
            TransactionViewModel.Factory(
                TransactionDatabase.getDatabase(requireActivity().applicationContext),
                requireActivity().application
            )
        }
        // Other fields...

        // --- Setup Category Spinner ---
        val categoriesAdapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item)
        categoriesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = categoriesAdapter

        // Observe the category names from the ViewModel
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                transactionViewModel.categoryNames.collectLatest { categoryNames ->
                    Log.d("EditDialog", "Updating categories: $categoryNames")
                    categoriesAdapter.clear()
                    categoriesAdapter.addAll(categoryNames)
                    categoriesAdapter.notifyDataSetChanged()

                    // Set the spinner selection AFTER the adapter is populated
                    val categoryPosition = categoryNames.indexOf(transaction.category)
                    if (categoryPosition != -1) {
                        categorySpinner.setSelection(categoryPosition)
                    } else if (categoryNames.isNotEmpty()) {
                        // Select first item if current category not found (or handle differently)
                        categorySpinner.setSelection(0)
                        Log.w("EditDialog", "Original category '${transaction.category}' not found in list.")
                    }
                }
            }
        }
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_transaction) // Use string resource
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val newMerchant = merchantEditText.text.toString().trim()
                // Handle potential empty selection or adapter not populated yet
                val newCategory = if (categorySpinner.selectedItemPosition != Spinner.INVALID_POSITION) {
                    categorySpinner.selectedItem.toString()
                } else {
                    transaction.category // Keep original if spinner empty/invalid
                }

                // Validate inputs
                if (newMerchant.isBlank()) {
                    Toast.makeText(context, "Merchant cannot be empty", Toast.LENGTH_SHORT).show()
                } else {
                    // Create updated transaction
                    val updatedTransaction = transaction.copy(
                        // Only update fields edited in this dialog
                        merchant = newMerchant,
                        category = newCategory
                        // date = calendar.timeInMillis // Uncomment if date is editable here
                    )
                    Log.d("EditDialog", "Calling onUpdate with: $updatedTransaction")
                    onUpdate(updatedTransaction) // Pass updated transaction back to caller
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}