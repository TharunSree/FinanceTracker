package com.example.financetracker

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.viewmodel.TransactionViewModel
import java.text.SimpleDateFormat
import java.util.Locale

class TransactionsActivity : AppCompatActivity() {

    private lateinit var transactionTableLayout: TableLayout

    private val transactionViewModel: TransactionViewModel by viewModels {
        val database = TransactionDatabase.getDatabase(this)
        TransactionViewModel.Factory(database)
    }

    private val addTransactionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.let { data ->
                    try {
                        val name = data.getStringExtra("name")
                            ?: throw IllegalArgumentException("Name is required")
                        val amount = data.getDoubleExtra("amount", 0.0)
                        val date = data.getStringExtra("date")
                            ?: throw IllegalArgumentException("Date is required")
                        val category = data.getStringExtra("category") ?: "Uncategorized"

                        val transaction = Transaction(
                            id = 0,
                            name = name,
                            amount = amount,
                            date = parseDateToLong(date),
                            category = category
                        )

                        transactionViewModel.addTransaction(transaction)
                    } catch (e: Exception) {
                        // Handle error
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transactions)

        transactionTableLayout = findViewById(R.id.transactionTableLayout)
        setupAddTransactionButton()
        observeTransactions()
    }

    private fun setupAddTransactionButton() {
        findViewById<Button>(R.id.addTransactionButton).setOnClickListener {
            val intent = Intent(this, AddTransactionActivity::class.java)
            addTransactionLauncher.launch(intent)
        }
    }

    private fun observeTransactions() {
        transactionViewModel.transactions.observe(this) { transactions ->
            populateTable(transactions)
        }
    }

    private fun populateTable(transactions: List<Transaction>) {
        transactionTableLayout.removeViews(1, transactionTableLayout.childCount - 1) // Clear existing rows except the header

        for (transaction in transactions) {
            val tableRow = TableRow(this)

            val nameTextView = TextView(this).apply {
                text = transaction.name
                setPadding(8, 8, 8, 8)
            }

            val amountTextView = TextView(this).apply {
                text = "₹${transaction.amount}"
                setPadding(8, 8, 8, 8)
            }

            val dateTextView = TextView(this).apply {
                text = formatDate(transaction.date)
                setPadding(8, 8, 8, 8)
            }

            val categoryTextView = TextView(this).apply {
                text = transaction.category
                setPadding(8, 8, 8, 8)
            }

            val actionsTextView = TextView(this).apply {
                text = "Edit Delete"
                setPadding(8, 8, 8, 8)
                setOnClickListener {
                    // Handle edit and delete actions
                    showPopupMenu(transaction, this)
                }
            }

            tableRow.addView(nameTextView)
            tableRow.addView(amountTextView)
            tableRow.addView(dateTextView)
            tableRow.addView(categoryTextView)
            tableRow.addView(actionsTextView)

            transactionTableLayout.addView(tableRow)
        }
    }

    private fun showPopupMenu(transaction: Transaction, view: TextView) {
        PopupMenu(this, view).apply {
            menuInflater.inflate(R.menu.transaction_options_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit -> {
                        onEditTransaction(transaction)
                        true
                    }
                    R.id.action_delete -> {
                        onDeleteTransaction(transaction)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun parseDateToLong(date: String): Long {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            dateFormat.parse(date)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun formatDate(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return dateFormat.format(timestamp)
    }

    private fun onEditTransaction(transaction: Transaction) {
        // Handle edit transaction
    }

    private fun onDeleteTransaction(transaction: Transaction) {
        transactionViewModel.deleteTransaction(transaction)
    }
}