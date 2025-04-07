package com.example.financetracker.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater // Import LayoutInflater explicitly
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView // Import AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope // Import lifecycleScope
import com.example.financetracker.R
import com.example.financetracker.utils.CategoryUtils // Import your actual CategoryUtils
import com.example.financetracker.utils.GuestUserManager // Import GuestUserManager
import com.google.android.material.checkbox.MaterialCheckBox // Import MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText // Import TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// Removed Spinner import

class TransactionDetailsDialog : DialogFragment() {

    // Keep the listener interface
    interface TransactionDetailsListener {
        fun onDetailsEntered(merchant: String, category: String, saveAsPattern: Boolean)
    }

    private var listener: TransactionDetailsListener? = null
    private var originalMerchant: String = ""
    // Removed messageBody if not used in this specific dialog logic
    // private var messageBody: String = ""

    private val TAG = "TransactionDetailsDialog"

    // --- Views --- (Declare lateinit vars for views needed across methods)
    private lateinit var merchantInputLayout: TextInputLayout
    private lateinit var merchantEditText: TextInputEditText
    private lateinit var categoryInputLayout: TextInputLayout
    private lateinit var categoryAutoCompleteTextView: AutoCompleteTextView
    private lateinit var savePatternCheckbox: MaterialCheckBox

    companion object {
        // Keep newInstance method, ensure keys match arguments?.getString
        private const val ARG_ORIGINAL_MERCHANT = "originalMerchant"
        // private const val ARG_MESSAGE_BODY = "messageBody" // If needed

        fun newInstance(originalMerchant: String /*, messageBody: String if needed */): TransactionDetailsDialog {
            return TransactionDetailsDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_ORIGINAL_MERCHANT, originalMerchant)
                    // putString(ARG_MESSAGE_BODY, messageBody)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        originalMerchant = arguments?.getString(ARG_ORIGINAL_MERCHANT) ?: ""
        // messageBody = arguments?.getString(ARG_MESSAGE_BODY) ?: "" // If needed

        // Inflate the custom layout using requireActivity().layoutInflater
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_transaction_details, null)

        // --- Find Material Views ---
        merchantInputLayout = view.findViewById(R.id.merchantInputLayout)
        merchantEditText = view.findViewById(R.id.merchantEditText) // Find inner EditText
        categoryInputLayout = view.findViewById(R.id.categoryInputLayout)
        categoryAutoCompleteTextView = view.findViewById(R.id.categoryAutoCompleteTextView)
        savePatternCheckbox = view.findViewById(R.id.savePatternCheckbox)

        // Set initial merchant text
        merchantEditText.setText(originalMerchant)

        // --- Load Categories Asynchronously ---
        loadCategoriesIntoDropdown() // Call function to load categories

        // --- Build the Dialog ---
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_transaction_details) // Use a more specific title string
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                // Read values from Material components
                val merchant = merchantEditText.text.toString().trim()
                val category = categoryAutoCompleteTextView.text.toString().trim()
                val savePattern = savePatternCheckbox.isChecked

                // --- Basic Validation (Example) ---
                var isValid = true
                if (merchant.isEmpty()) {
                    // Show error on the layout
                    merchantInputLayout.error = getString(R.string.merchant_cannot_be_empty) // Add string resource
                    isValid = false
                } else {
                    merchantInputLayout.error = null // Clear error
                }

                if (category.isEmpty()) {
                    // Check if category is required. If yes:
                    categoryInputLayout.error = getString(R.string.select_category_error) // Add string resource
                    Toast.makeText(context, R.string.select_category, Toast.LENGTH_SHORT).show()
                    isValid = false
                } else {
                    // Optional: More robust validation - check if category is in the adapter list
                    val adapter = categoryAutoCompleteTextView.adapter
                    var categoryInList = false
                    if (adapter != null) {
                        for (i in 0 until adapter.count) {
                            if (adapter.getItem(i)?.toString() == category) {
                                categoryInList = true
                                break
                            }
                        }
                    }
                    if (!categoryInList && adapter?.count ?: 0 > 0) { // If not in list and list has items
                        categoryInputLayout.error = getString(R.string.select_valid_category)
                        Toast.makeText(context, R.string.select_valid_category_toast, Toast.LENGTH_SHORT).show()
                        isValid = false
                    } else {
                        categoryInputLayout.error = null // Clear error
                    }
                }


                // Only proceed if valid
                if (isValid) {
                    listener?.onDetailsEntered(merchant, category, savePattern)
                } else {
                    // Prevent dialog dismissal manually if validation fails? This is complex with standard AlertDialog.
                    // Usually, we let it dismiss and the user has to reopen if they made errors.
                    // For non-dismissal, you'd typically override the button listener after the dialog is shown.
                    Log.w(TAG, "Validation failed. Merchant: '$merchant', Category: '$category'")
                }
            }
            .setNegativeButton(R.string.cancel, null) // Dismisses dialog

        return builder.create()
    }


    // Function to load categories asynchronously
    private fun loadCategoriesIntoDropdown() {
        // Use viewLifecycleOwner.lifecycleScope for safety in DialogFragments if interacting heavily with views after creation
        // Using lifecycleScope here as we set adapter on view found in onCreateDialog
        lifecycleScope.launch {
            val userId = FirebaseAuth.getInstance().currentUser?.uid
                ?: GuestUserManager.getGuestUserId(requireContext())

            // Use your actual CategoryUtils object and function
            val categoryNames = try {
                // Ensure context is applicationContext if CategoryUtils needs it longer term
                CategoryUtils.getCategoryNamesForUser(requireContext().applicationContext, userId)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading categories", e)
                listOf<String>() // Return empty list on error
            }

            // Update UI on the Main thread
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext // Check if fragment is still added

                if (categoryNames.isNotEmpty()) {
                    val adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_dropdown_item_1line, // Dropdown item layout
                        categoryNames
                    )
                    categoryAutoCompleteTextView.setAdapter(adapter)
                    Log.d(TAG, "Category adapter set with ${categoryNames.size} items.")
                    categoryInputLayout.isEnabled = true
                    categoryInputLayout.error = null // Clear any loading error
                } else {
                    // Handle case where no categories were loaded
                    Log.w(TAG, "No categories loaded or error occurred.")
                    categoryInputLayout.error = getString(R.string.category_loading_failed) // Inform user
                    categoryInputLayout.isEnabled = false // Disable dropdown if no categories
                    // Optionally show a Toast
                    Toast.makeText(context, R.string.no_categories_found, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Assign listener from the hosting Activity/Fragment
        listener = try {
            context as TransactionDetailsListener // Try casting context directly
        } catch (e: ClassCastException) {
            // If direct context cast fails, try getting from parentFragment or targetFragment
            parentFragment as? TransactionDetailsListener ?: targetFragment as? TransactionDetailsListener
        }

        if (listener == null) {
            // Log error or throw exception if listener is mandatory
            Log.e(TAG, "$context or its parent/target must implement TransactionDetailsListener")
            // Or uncomment below to enforce:
            // throw ClassCastException("$context must implement TransactionDetailsListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null // Clean up listener
    }
}