package com.example.financetracker

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.viewmodel.TransactionViewModel
import java.text.SimpleDateFormat
import java.util.Locale

class TransactionsActivity : BaseActivity() {

    override fun getLayoutResourceId(): Int = R.layout.activity_transactions

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

        transactionTableLayout = findViewById(R.id.transactionTableLayout)
        setupAddTransactionButton()
        observeTransactions()

        // Set title in toolbar
        supportActionBar?.title = "Transactions"
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

        transactions.forEach { transaction ->
            val tableRow = TableRow(this).apply {
                layoutParams = TableRow.LayoutParams(
                    TableRow.LayoutParams.MATCH_PARENT,
                    TableRow.LayoutParams.WRAP_CONTENT
                )
                setBackgroundResource(R.drawable.table_row_background)
                setOnLongClickListener {
                    showPopupMenu(transaction, this)
                    true
                }
            }

            // Create cells with equal width
            val cells = listOf(
                createTableCell(transaction.name),
                createTableCell("₹${String.format("%.2f", transaction.amount)}"),
                createTableCell(formatDate(transaction.date)),
                createTableCell(transaction.category)
            )

            // Add cells to row
            cells.forEach { cell ->
                tableRow.addView(cell)
            }

            // Add alternating row background
            if (transactionTableLayout.childCount % 2 == 0) {
                tableRow.setBackgroundColor(ContextCompat.getColor(this, R.color.row_even))
            } else {
                tableRow.setBackgroundColor(ContextCompat.getColor(this, R.color.row_odd))
            }

            transactionTableLayout.addView(tableRow)
        }
    }

    private fun createTableCell(text: String): TextView {
        return TextView(this).apply {
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 16, 16, 16)
            this.text = text
            setTextAppearance(R.style.TableCellStyle)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    private fun showPopupMenu(transaction: Transaction, view: TableRow) {
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

    private fun onEditTransaction(transaction: Transaction) {
        val intent = Intent(this, AddTransactionActivity::class.java).apply {
            putExtra("EDIT_MODE", true)
            putExtra("TRANSACTION_ID", transaction.id)
            putExtra("TRANSACTION_NAME", transaction.name)
            putExtra("TRANSACTION_AMOUNT", transaction.amount)
            putExtra("TRANSACTION_DATE", formatDate(transaction.date))
            putExtra("TRANSACTION_CATEGORY", transaction.category)
        }
        addTransactionLauncher.launch(intent)
    }

    private fun onDeleteTransaction(transaction: Transaction) {
        transactionViewModel.deleteTransaction(transaction)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item)
    }
}