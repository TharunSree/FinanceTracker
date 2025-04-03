package com.example.financetracker

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

    // --- View variables ---
    // Make them nullable and initialize in onViewCreated to avoid lateinit issues if view is destroyed
    private var categoryInputLayout: TextInputLayout? = null
    private var categoryAutoComplete: AutoCompleteTextView? = null
    private var merchantInputLayout: TextInputLayout? = null
    private var merchantInputEditText: TextInputEditText? = null
    private var categoriesAdapter: ArrayAdapter<String>? = null

    // --- Argument variables ---
    private var transactionId: Long = 0L
    private var originalCategory: String? = null
    private var currentMerchant: String? = null
    // Keep other args if needed for saving
    // private var currentName: String? = null
    // private var currentAmount: Double = 0.0
    // private var currentDate: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            transactionId = it.getLong(ARG_TRANSACTION_ID)
            originalCategory = it.getString(ARG_CURRENT_CATEGORY)
            currentMerchant = it.getString(ARG_CURRENT_MERCHANT)
            // Retrieve others if needed
            // currentName = it.getString(ARG_CURRENT_NAME)
            // currentAmount = it.getDouble(ARG_CURRENT_AMOUNT)
            // currentDate = it.getLong(ARG_CURRENT_DATE)
            Log.d(TAG, "onCreate: Args received - ID: $transactionId, Category: '$originalCategory', Merchant: '$currentMerchant'")
        }
        // Early exit if critical data is missing
        if (transactionId == 0L) {
            Log.e(TAG, "Transaction ID is missing! Cannot proceed with edit.")
            Toast.makeText(requireContext(), "Error: Transaction data missing.", Toast.LENGTH_SHORT).show()
            // Use dismissAllowingStateLoss if there's a chance this happens during state save/restore
            dismissAllowingStateLoss()
        }
    }

    // --- Step 1: Inflate layout in onCreateView ---
    // This view becomes the fragment's content.
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView: Inflating R.layout.dialog_edit_transaction")
        val view = inflater.inflate(R.layout.dialog_edit_transaction, container, false)
        // ---> ADD THIS LOG <---
        Log.d(TAG, "onCreateView: Inflation result view = $view")
        if (view == null) {
            Log.e(TAG, "onCreateView: *** INFLATION FAILED, VIEW IS NULL! ***")
        }
        return view // Return the inflated view (or null if inflation failed)
    }

    // --- Step 2: Configure the view in onViewCreated ---
    // This is called after onCreateView returns and the view hierarchy is created.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: Initializing views and setting initial data.")

        // Initialize views using the 'view' parameter provided here
        categoryInputLayout = view.findViewById(R.id.categoryInputLayout)
        categoryAutoComplete = view.findViewById(R.id.categoryAutoComplete)
        merchantInputLayout = view.findViewById(R.id.merchantInputLayout)
        merchantInputEditText = view.findViewById(R.id.merchantInputEditText)

        // Setup category adapter
        categoriesAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line)
        categoryAutoComplete?.setAdapter(categoriesAdapter)

        // --- Set initial values ---
        // Set merchant text immediately
        Log.d(TAG, "onViewCreated: Setting merchant text to: '$currentMerchant'")
        merchantInputEditText?.setText(currentMerchant ?: "")

        // Observe categories using viewLifecycleOwner to populate dropdown and set initial category
        observeAndSetCategories()
    }

    private fun observeAndSetCategories() {
        Log.d(TAG, "observeAndSetCategories: Starting observer. Target category: '$originalCategory'")
        // Use viewLifecycleOwner.lifecycleScope for coroutines tied to the fragment's view lifecycle
        viewLifecycleOwner.lifecycleScope.launch {
            // repeatOnLifecycle ensures collection stops when the view is stopped/destroyed and restarts when started
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                transactionViewModel.categoryNames.collectLatest { categoryNames ->
                    // Ensure fragment is still attached and view is accessible
                    if (!isAdded || view == null || categoriesAdapter == null || categoryAutoComplete == null) {
                        Log.w(TAG, "observeAndSetCategories: Fragment not ready, skipping category update.")
                        return@collectLatest
                    }

                    Log.d(TAG, "observeAndSetCategories: Received categories (size ${categoryNames.size}): $categoryNames")

                    // Update the adapter on the main thread (lifecycleScope usually runs on Main)
                    categoriesAdapter?.clear()
                    categoriesAdapter?.addAll(categoryNames)
                    // notifyDataSetChanged() is often implicitly called by ArrayAdapter's modify methods

                    // --- Set the initial category selection ---
                    val categoryToSet = originalCategory
                    if (!categoryToSet.isNullOrBlank()) {
                        // Check if the category exists in the *updated* adapter list
                        val adapterContainsCategory = categoryNames.contains(categoryToSet)

                        if (adapterContainsCategory) {
                            Log.d(TAG, "observeAndSetCategories: Found '$categoryToSet' in list. Setting text.")
                            // Use setText with 'false' to prevent filtering/dropdown showing automatically
                            categoryAutoComplete?.setText(categoryToSet, false)
                            categoryInputLayout?.error = null // Clear error if category is now valid
                        } else {
                            Log.w(TAG, "observeAndSetCategories: Original category '$categoryToSet' not found in the latest list.")
                            // Optional: Clear the field or show a warning if the category was deleted/renamed
                            // categoryAutoComplete?.setText("", false)
                            // categoryInputLayout?.error = "Original category invalid"
                        }
                    } else {
                        Log.d(TAG, "observeAndSetCategories: No original category was provided.")
                    }
                    // Log state after attempting to set category
                    Log.d(TAG, "observeAndSetCategories: Post-update state: category='${categoryAutoComplete?.text}', merchant='${merchantInputEditText?.text}'")
                }
            }
        }
    }


    // --- Step 3: Build the dialog shell in onCreateDialog ---
    // DO NOT inflate or set the view here. Let the framework use the view from onCreateView.
    // --- Step 3: Build the dialog shell in onCreateDialog ---
    // DO NOT inflate or set the view here. Let the framework use the view from onCreateView.
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        Log.d(TAG, "onCreateDialog: Creating dialog shell.")
        // Use MaterialAlertDialogBuilder for Material styling
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_transaction)
            // *** Ensure there is NO .setView() call here ***
            .setNegativeButton(R.string.cancel, null) // Default dismiss action
            .setPositiveButton(R.string.save, null) // Override in onStart for validation

        return builder.create()
    }

    // --- Step 4: Customize dialog behavior in onStart ---
    // Called after onCreateDialog and after the view is created and attached.
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Dialog shown. Overriding positive button.")
        // It's safer to cast to AlertDialog
        val dialog = dialog as? AlertDialog
        dialog?.let { d ->
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                Log.d(TAG, "onStart: Save button clicked.")
                if (validateInputs()) {
                    saveChanges()
                    dismiss() // Dismiss dialog only if validation passes and save is triggered
                }
            }
        }
        // Log the state when the dialog becomes fully visible (category might still be loading)
        Log.d(TAG, "onStart: Initial visible state: category='${categoryAutoComplete?.text}', merchant='${merchantInputEditText?.text}'")
    }

    // --- Validation ---
    private fun validateInputs(): Boolean {
        // Use safe calls ?. due to potentially nullable views if accessed after onDestroyView
        val currentCategory = categoryAutoComplete?.text?.toString() ?: ""
        val currentMerchant = merchantInputEditText?.text?.toString() ?: "" // Get merchant value too

        var isValid = true
        Log.d(TAG, "validateInputs: Checking category '$currentCategory', merchant '$currentMerchant'")

        // Validate Category
        categoryInputLayout?.error = null // Reset error
        if (currentCategory.isBlank()) {
            categoryInputLayout?.error = getString(R.string.error_category_required)
            isValid = false
            Log.w(TAG, "validateInputs: Category is blank.")
        }
        // Optional: Add more category validation (e.g., must exist in list if you don't allow creating new ones here)

        // Validate Merchant (Example: cannot be blank)
        merchantInputLayout?.error = null // Reset error
        /* // Uncomment if merchant is required
         if (currentMerchant.isBlank()) {
             merchantInputLayout?.error = getString(R.string.error_merchant_required) // Make sure you have this string resource
             isValid = false
             Log.w(TAG, "validateInputs: Merchant is blank.")
         }
        */

        Log.d(TAG, "validateInputs: Validation result: $isValid")
        return isValid
    }

    // --- Saving Changes ---
    private fun saveChanges() {
        // Use safe calls and provide defaults or handle nulls appropriately
        val newCategory = categoryAutoComplete?.text?.toString() ?: originalCategory ?: "" // Fallback if view is somehow null
        val newMerchant = merchantInputEditText?.text?.toString()?.trim() ?: currentMerchant ?: "" // Fallback

        Log.d(TAG, "saveChanges: Preparing result - ID: $transactionId, Category: '$newCategory', Merchant: '$newMerchant'")

        setFragmentResult(REQUEST_KEY, bundleOf(
            RESULT_TRANSACTION_ID to transactionId,
            RESULT_NEW_CATEGORY to newCategory,
            RESULT_NEW_MERCHANT to newMerchant
            // Add other fields here if they are part of the result
        ))
        Log.d(TAG, "saveChanges: Fragment result set.")
    }

    // --- Clean up View References ---
    // Important for DialogFragments to prevent memory leaks
    override fun onDestroyView() {
        // Nullify view references when the view hierarchy is destroyed
        categoryInputLayout = null
        categoryAutoComplete = null
        merchantInputLayout = null
        merchantInputEditText = null
        categoriesAdapter = null
        Log.d(TAG, "onDestroyView: Cleared view references.")
        // The dialog itself is dismissed separately, but the view hierarchy created in onCreateView is destroyed here.
        // Call super AFTER clearing references
        super.onDestroyView()
    }


    // --- Companion Object (remains the same) ---
    companion object {
        const val TAG = "EditTransactionDialog"
        const val REQUEST_KEY = "editTransactionRequest"
        const val RESULT_TRANSACTION_ID = "resultTransactionId"
        const val RESULT_NEW_CATEGORY = "resultNewCategory"
        const val RESULT_NEW_MERCHANT = "resultNewMerchant"

        // Argument keys
        private const val ARG_TRANSACTION_ID = "argTransactionId"
        private const val ARG_CURRENT_CATEGORY = "argCurrentCategory"
        private const val ARG_CURRENT_MERCHANT = "argCurrentMerchant"
        private const val ARG_CURRENT_NAME = "argCurrentName"
        private const val ARG_CURRENT_AMOUNT = "argCurrentAmount"
        private const val ARG_CURRENT_DATE = "argCurrentDate"

        // Factory method
        fun newInstance(transaction: Transaction): EditTransactionDialogFragment {
            Log.d(TAG, "newInstance: Creating fragment for Tx ID ${transaction.id} ('${transaction.category}', '${transaction.merchant}')")
            val args = Bundle().apply {
                putLong(ARG_TRANSACTION_ID, transaction.id)
                putString(ARG_CURRENT_CATEGORY, transaction.category)
                putString(ARG_CURRENT_MERCHANT, transaction.merchant)
                // Pass other details even if not directly editable in this dialog,
                // they might be needed if saveChanges needs the full object context.
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