package com.example.financetracker

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.utils.CategoryUtils
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AddTransactionActivity : AppCompatActivity() {
    private lateinit var calendar: Calendar
    private lateinit var auth: FirebaseAuth
    private lateinit var viewModel: TransactionViewModel // Add this
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.add_item_transaction)

        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()

        // Initialize ViewModel
        viewModel = TransactionViewModel(
            TransactionDatabase.getDatabase(applicationContext),
            application
        )

        val nameInput = findViewById<EditText>(R.id.transactionNameInput)
        val amountInput = findViewById<EditText>(R.id.transactionAmountInput)
        val dateInput = findViewById<EditText>(R.id.transactionDateInput)
        val categorySpinner = findViewById<Spinner>(R.id.transactionCategorySpinner)
        val merchantInput = findViewById<EditText>(R.id.transactionMerchantInput)
        val descriptionInput = findViewById<EditText>(R.id.transactionDescriptionInput)
        val saveButton = findViewById<Button>(R.id.saveTransactionButton)
        loadCategories()

        // Initialize calendar
        calendar = Calendar.getInstance()

        // Set up the Spinner for categories
        val categories = resources.getStringArray(R.array.transaction_categories)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter

        // Handle edit mode
        val editMode = intent.getBooleanExtra("EDIT_MODE", false)
        var transactionId = 0

        if (editMode) {
            // Set title for edit mode
            title = "Edit Transaction"

            // Get transaction details from intent
            transactionId = intent.getIntExtra("TRANSACTION_ID", 0)
            nameInput.setText(intent.getStringExtra("TRANSACTION_NAME"))
            amountInput.setText(intent.getDoubleExtra("TRANSACTION_AMOUNT", 0.0).toString())

            // Handle date
            val timestamp = intent.getLongExtra("TRANSACTION_DATE", System.currentTimeMillis())
            calendar.timeInMillis = timestamp
            dateInput.setText(dateFormat.format(calendar.time))

            // Set category
            val category = intent.getStringExtra("TRANSACTION_CATEGORY")
            val categoryPosition = categories.indexOf(category)
            if (categoryPosition != -1) {
                categorySpinner.setSelection(categoryPosition)
            }

            merchantInput.setText(intent.getStringExtra("TRANSACTION_MERCHANT"))
            descriptionInput.setText(intent.getStringExtra("TRANSACTION_DESCRIPTION"))
        } else {
            title = "Add Transaction"
            // Set current date for new transactions
            dateInput.setText(dateFormat.format(calendar.time))
        }

        // Make date field read-only
        dateInput.isFocusable = false
        dateInput.isFocusableInTouchMode = false

        // Set up Date Picker
        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            dateInput.setText(dateFormat.format(calendar.time))
        }

        dateInput.setOnClickListener {
            DatePickerDialog(
                this,
                dateSetListener,
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        saveButton.setOnClickListener {
            // Validate inputs
            if (nameInput.text.isBlank() || amountInput.text.isBlank() || dateInput.text.isBlank()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val amount = try {
                amountInput.text.toString().toDouble()
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val transaction = Transaction(
                id = 0L, // Use 0L for new transactions
                name = nameInput.text.toString(),
                amount = amount,
                date = calendar.timeInMillis,
                category = categorySpinner.selectedItem.toString(),
                merchant = merchantInput.text.toString(),
                description = descriptionInput.text.toString(),
                isCredit = false, // Add this field
                documentId = "", // Add this field
                userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)
            )

            // Save transaction to Firestore
            addTransactionToFirestore(transaction)

            val data = Intent().apply {
                putExtra("id", transaction.id)
                putExtra("name", transaction.name)
                putExtra("amount", transaction.amount)
                putExtra("date", transaction.date)
                putExtra("category", transaction.category)
                putExtra("merchant", transaction.merchant)
                putExtra("description", transaction.description)
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    private fun addTransactionToFirestore(transaction: Transaction) {
        val userId = auth.currentUser?.uid ?: return

        // Create a document reference first to get an ID
        val docRef = firestore.collection("users")
            .document(userId)
            .collection("transactions")
            .document()

        // Get the document ID
        val docId = docRef.id

        // Create updated transaction with document ID
        val updatedTransaction = transaction.copy(documentId = docId)

        // Create a map with all transaction data
        val transactionMap = hashMapOf(
            "id" to updatedTransaction.id,
            "name" to updatedTransaction.name,
            "amount" to updatedTransaction.amount,
            "date" to updatedTransaction.date,
            "category" to updatedTransaction.category,
            "merchant" to updatedTransaction.merchant,
            "description" to updatedTransaction.description,
            "isCredit" to updatedTransaction.isCredit,
            "documentId" to docId,
            "userId" to userId
        )

        // Save to Firestore
        docRef.set(transactionMap)
            .addOnSuccessListener {
                Toast.makeText(this, "Transaction added successfully", Toast.LENGTH_SHORT).show()

                // Update the result intent with the document ID
                val data = Intent().apply {
                    putExtra("id", updatedTransaction.id)
                    putExtra("name", updatedTransaction.name)
                    putExtra("amount", updatedTransaction.amount)
                    putExtra("date", updatedTransaction.date)
                    putExtra("category", updatedTransaction.category)
                    putExtra("merchant", updatedTransaction.merchant)
                    putExtra("description", updatedTransaction.description)
                    putExtra("documentId", updatedTransaction.documentId)
                    putExtra("isCredit", updatedTransaction.isCredit)
                }
                setResult(Activity.RESULT_OK, data)
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error adding transaction: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

// Update AddTransactionActivity to use the utility

    // Replace your existing category loading code with:
    private fun loadCategories() {
        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)
        val categorySpinner = findViewById<Spinner>(R.id.transactionCategorySpinner)

        // Pre-selected category from intent (if any)
        val selectedCategory = intent.getStringExtra("TRANSACTION_CATEGORY")

        lifecycleScope.launch {
            CategoryUtils.loadCategoriesToSpinner(
                this@AddTransactionActivity,
                categorySpinner,
                userId,
                selectedCategory
            )
        }
    }
}