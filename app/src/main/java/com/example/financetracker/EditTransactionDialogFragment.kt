package com.example.financetracker

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle // Import repeatOnLifecycle
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.viewmodel.TransactionViewModel
import kotlinx.coroutines.flow.collectLatest // Import collectLatest
// Remove kotlinx.coroutines.flow.first import if no longer needed
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class EditTransactionDialogFragment(
    private val transaction: Transaction,
    private val onUpdate: (Transaction) -> Unit
) : DialogFragment() {

    private val transactionViewModel: TransactionViewModel by activityViewModels {
        TransactionViewModel.Factory(
            TransactionDatabase.getDatabase(requireActivity().applicationContext),
            requireActivity().application
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_edit_transaction, null)

        val merchantEditText: EditText = view.findViewById(R.id.editTransactionMerchant)
        val categorySpinner: Spinner = view.findViewById(R.id.editTransactionCategory)

        // --- Setup Category Spinner Adapter ---
        val categoriesAdapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item)
        categoriesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = categoriesAdapter

        // Set initial merchant value
        merchantEditText.setText(transaction.merchant)

        // --- Observe Category Flow ---
        // Use lifecycleScope tied to the DialogFragment's lifecycle
        // Use repeatOnLifecycle to ensure collection only happens when STARTED
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { // Collect when STARTED
                transactionViewModel.categoryNames.collectLatest { categoryNames ->
                    Log.d("EditDialog", "Updating spinner with categories: $categoryNames")
                    // Update adapter on the main thread (which lifecycleScope typically uses)
                    categoriesAdapter.clear()
                    categoriesAdapter.addAll(categoryNames)
                    categoriesAdapter.notifyDataSetChanged()

                    // Set the spinner selection AFTER the adapter is populated
                    val categoryPosition = categoryNames.indexOf(transaction.category)
                    if (categoryPosition != -1) {
                        // Check if the current selection is different before setting
                        if (categorySpinner.selectedItemPosition != categoryPosition) {
                            categorySpinner.setSelection(categoryPosition)
                        }
                    } else if (categoryNames.isNotEmpty() && categorySpinner.selectedItemPosition == Spinner.INVALID_POSITION) {
                        // Default to first item only if nothing is selected yet and list isn't empty
                        categorySpinner.setSelection(0)
                        Log.w("EditDialog", "Original category '${transaction.category}' not found, defaulting to first.")
                    } else if (categoryNames.isEmpty()) {
                        Log.w("EditDialog", "Category list is empty.")
                    }
                }
            }
        }
        // --- End Observe Category Flow ---

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_transaction)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val newMerchant = merchantEditText.text.toString().trim()
                val newCategory = if (categorySpinner.adapter.count > 0 && categorySpinner.selectedItemPosition != Spinner.INVALID_POSITION) {
                    categorySpinner.selectedItem.toString()
                } else {
                    Log.w("EditDialog", "Spinner empty or no selection on save, using original category.")
                    transaction.category // Fallback to original if spinner is empty or nothing selected
                }


                if (newMerchant.isBlank()) {
                    Toast.makeText(context, "Merchant cannot be empty", Toast.LENGTH_SHORT).show()
                } else {
                    val updatedTransaction = transaction.copy(
                        merchant = newMerchant,
                        category = newCategory
                    )
                    Log.d("EditDialog", "Calling onUpdate with: $updatedTransaction")
                    onUpdate(updatedTransaction)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}