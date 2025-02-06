package com.example.financetracker.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.financetracker.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.checkbox.MaterialCheckBox

class TransactionDetailsDialog : DialogFragment() {
    interface TransactionDetailsListener {
        fun onDetailsEntered(merchant: String, category: String, saveAsPattern: Boolean)
    }

    private var listener: TransactionDetailsListener? = null
    private var originalMerchant: String = ""
    private var messageBody: String = ""

    companion object {
        fun newInstance(originalMerchant: String, messageBody: String): TransactionDetailsDialog {
            return TransactionDetailsDialog().apply {
                arguments = Bundle().apply {
                    putString("originalMerchant", originalMerchant)
                    putString("messageBody", messageBody)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        originalMerchant = arguments?.getString("originalMerchant") ?: ""
        messageBody = arguments?.getString("messageBody") ?: ""

        val view = layoutInflater.inflate(R.layout.dialog_transaction_details, null)
        val merchantInput = view.findViewById<EditText>(R.id.merchantInput)
        val categorySpinner = view.findViewById<Spinner>(R.id.categorySpinner)
        val savePatternCheckbox = view.findViewById<CheckBox>(R.id.savePatternCheckbox)

        merchantInput.setText(originalMerchant)

        // Get categories from strings.xml
        val categories = resources.getStringArray(R.array.transaction_categories)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_transaction)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val merchant = merchantInput.text.toString()
                val category = categorySpinner.selectedItem.toString()
                val savePattern = savePatternCheckbox.isChecked
                listener?.onDetailsEntered(merchant, category, savePattern)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is TransactionDetailsListener) {
            listener = context
        }
    }
}