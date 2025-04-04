package com.example.financetracker

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
// Ensure R is imported correctly if not already
// import com.example.financetracker.R
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// No need for SimpleDateFormat/Date unless you plan to edit the date in this dialog
// import java.text.SimpleDateFormat
// import java.util.*

class EditTransactionDialogFragment : DialogFragment() {

    private val transactionViewModel: TransactionViewModel by activityViewModels {
        TransactionViewModel.Factory(
            TransactionDatabase.getDatabase(requireActivity().applicationContext),
            requireActivity().application
        )
    }

    // View variables
    private var categoryInputLayout: TextInputLayout? = null
    private var categoryAutoComplete: AutoCompleteTextView? = null
    private var nameInputLayout: TextInputLayout? = null
    private var nameInputEditText: TextInputEditText? = null
    private var merchantInputLayout: TextInputLayout? = null
    private var merchantInputEditText: TextInputEditText? = null
    private var categoriesAdapter: ArrayAdapter<String>? = null

    // Argument variables
    private var transactionId: Long = 0L
    private var currentName: String? = null
    private var originalCategory: String? = null
    private var currentMerchant: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            transactionId = it.getLong(ARG_TRANSACTION_ID)
            currentName = it.getString(ARG_CURRENT_NAME)
            originalCategory = it.getString(ARG_CURRENT_CATEGORY)
            currentMerchant = it.getString(ARG_CURRENT_MERCHANT)
            Log.d(TAG, "onCreate: Args received - ID: $transactionId,Name: '$currentName', Category: '$originalCategory', Merchant: '$currentMerchant'")
        }
        if (transactionId == 0L) {
            Log.e(TAG, "Transaction ID is missing! Cannot proceed with edit.")
            Toast.makeText(requireContext(), "Error: Transaction data missing.", Toast.LENGTH_SHORT).show()
            dismissAllowingStateLoss()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_edit_transaction, null)

        // Initialize views
        nameInputLayout = view.findViewById(R.id.nameInputLayout)
        nameInputEditText = view.findViewById(R.id.nameInputEditText)
        categoryInputLayout = view.findViewById(R.id.categoryInputLayout)
        categoryAutoComplete = view.findViewById(R.id.categoryAutoComplete)
        merchantInputLayout = view.findViewById(R.id.merchantInputLayout)
        merchantInputEditText = view.findViewById(R.id.merchantInputEditText)

        // Setup adapter
        categoriesAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line)
        categoryAutoComplete?.setAdapter(categoriesAdapter)

        // Set initial merchant text
        merchantInputEditText?.setText(currentMerchant ?: "")
        nameInputEditText?.setText(currentName ?: "")

        // Start observing categories right away
        observeCategories()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_transaction)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
    }

    private fun observeCategories() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                transactionViewModel.categoryNames.collectLatest { categoryNames ->
                    if (!isAdded) return@collectLatest

                    requireActivity().runOnUiThread {
                        categoriesAdapter?.clear()
                        categoriesAdapter?.addAll(categoryNames)

                        if (!originalCategory.isNullOrBlank() && categoryNames.contains(originalCategory)) {
                            categoryAutoComplete?.setText(originalCategory, false)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? AlertDialog

        Log.d(TAG, "onStart: Dialog showing=${dialog?.isShowing}")

        // Override the positive button behavior for validation
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            Log.d(TAG, "Save button clicked")
            if (validateInputs()) {
                Log.d(TAG, "Validation passed, saving changes")
                saveChangesDirectly()  // Use direct save method
            } else {
                Log.d(TAG, "Validation failed, not saving")
            }
        }
    }

    private fun validateInputs(): Boolean {
        val categoryText = categoryAutoComplete?.text?.toString() ?: ""
        Log.d(TAG, "validateInputs: Validating category='$categoryText'")

        var isValid = true

        // Validate category
        if (categoryText.isBlank()) {
            categoryInputLayout?.error = getString(R.string.error_category_required)
            isValid = false
            Log.d(TAG, "validateInputs: Category validation failed - blank")
        } else {
            categoryInputLayout?.error = null
        }

        Log.d(TAG, "validateInputs: Validation result: $isValid")
        return isValid
    }

    // Direct update method that doesn't rely on fragment results
    private fun saveChangesDirectly() {
        val newName = nameInputEditText?.text?.toString() ?: currentName ?: ""
        val newCategory = categoryAutoComplete?.text?.toString() ?: originalCategory ?: ""
        val newMerchant = merchantInputEditText?.text?.toString()?.trim() ?: currentMerchant ?: ""

        Log.d(TAG, "saveChangesDirectly: Updating - ID: $transactionId,Name: '$newName', Category: '$newCategory', Merchant: '$newMerchant'")

        lifecycleScope.launch {
            try {
                // Call view model to update the transaction
                transactionViewModel.updateTransactionCategoryAndMerchant(
                    transactionId,
                    newName,
                    newCategory,
                    newMerchant
                )
                Log.d(TAG, "saveChangesDirectly: Update successful!")
                // Show success message
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Transaction updated successfully", Toast.LENGTH_SHORT).show()
                    dismiss()  // Dismiss after successful update
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveChangesDirectly: Update failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        categoryInputLayout = null
        categoryAutoComplete = null
        merchantInputLayout = null
        merchantInputEditText = null
        categoriesAdapter = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "EditTransactionDialog"
        const val REQUEST_KEY = "editTransactionRequest"
        const val RESULT_TRANSACTION_ID = "resultTransactionId"
        const val RESULT_NEW_CATEGORY = "resultNewCategory"
        const val RESULT_NEW_MERCHANT = "resultNewMerchant"
        const val RESULT_NEW_NAME = "resultNewName"

        // Argument keys
        private const val ARG_TRANSACTION_ID = "argTransactionId"
        private const val ARG_CURRENT_CATEGORY = "argCurrentCategory"
        private const val ARG_CURRENT_MERCHANT = "argCurrentMerchant"
        private const val ARG_CURRENT_NAME = "argCurrentName"
        private const val ARG_CURRENT_AMOUNT = "argCurrentAmount"
        private const val ARG_CURRENT_DATE = "argCurrentDate"

        fun newInstance(transaction: Transaction): EditTransactionDialogFragment {
            val args = Bundle().apply {
                putLong(ARG_TRANSACTION_ID, transaction.id)
                putString(ARG_CURRENT_CATEGORY, transaction.category)
                putString(ARG_CURRENT_MERCHANT, transaction.merchant)
                putString(ARG_CURRENT_NAME, transaction.name)
                putDouble(ARG_CURRENT_AMOUNT, transaction.amount)
                putLong(ARG_CURRENT_DATE, transaction.date)
            }
            val fragment = EditTransactionDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}