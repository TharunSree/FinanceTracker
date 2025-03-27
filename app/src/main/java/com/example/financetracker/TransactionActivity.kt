package com.example.financetracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.MainActivity
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.databinding.NavHeaderBinding
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import java.text.SimpleDateFormat
import java.util.Locale

class TransactionActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    override fun getLayoutResourceId(): Int = R.layout.activity_transactions

    private lateinit var transactionTableLayout: TableLayout
    private lateinit var filterChipGroup: ChipGroup
    private lateinit var categoryFilterAutoComplete: AutoCompleteTextView
    private var currentFilter: TransactionFilter = TransactionFilter.ALL

    private val transactionViewModel: TransactionViewModel by viewModels {
        val database = TransactionDatabase.getDatabase(this)
        TransactionViewModel.Factory(database, application)
    }

    enum class TransactionFilter {
        ALL, TODAY, WEEK, MONTH
    }

    private val addTransactionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                val id = data.getIntExtra("id", 0)
                val name = data.getStringExtra("name") ?: ""
                val amount = data.getDoubleExtra("amount", 0.0)
                val date = data.getLongExtra("date", System.currentTimeMillis())
                val category = data.getStringExtra("category") ?: ""
                val merchant = data.getStringExtra("merchant") ?: ""
                val description = data.getStringExtra("description") ?: ""

                val transaction = Transaction(
                    id = id,
                    name = name,
                    amount = amount,
                    date = date,
                    category = category,
                    merchant = merchant,
                    description = description
                )

                if (id != 0) {
                    transactionViewModel.updateTransaction(transaction)
                } else {
                    transactionViewModel.addTransaction(transaction)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transactions)

        transactionTableLayout = findViewById(R.id.transactionTableLayout)
        filterChipGroup = findViewById(R.id.filterChipGroup)
        categoryFilterAutoComplete = findViewById(R.id.categoryFilterAutoComplete)

        setupFilters()
        setupCategoryFilter()
        setupFab()
        observeTransactions()
        updateNavHeader()

        // Set title in toolbar
        supportActionBar?.title = "Transactions"

        // Initialize the navigation drawer
        setupNavigationDrawer()
    }

    private fun setupFilters() {
        filterChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipAll -> {
                    currentFilter = TransactionFilter.ALL
                    transactionViewModel.loadAllTransactions()
                }
                R.id.chipToday -> {
                    currentFilter = TransactionFilter.TODAY
                    transactionViewModel.loadTodayTransactions()
                }
                R.id.chipWeek -> {
                    currentFilter = TransactionFilter.WEEK
                    transactionViewModel.loadWeekTransactions()
                }
                R.id.chipMonth -> {
                    currentFilter = TransactionFilter.MONTH
                    transactionViewModel.loadMonthTransactions()
                }
            }
        }
    }

    private fun setupCategoryFilter() {
        // Observe categories from ViewModel
        transactionViewModel.categories.observe(this) { categories ->
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                listOf("All Categories") + categories
            )
            categoryFilterAutoComplete.setAdapter(adapter)
        }

        categoryFilterAutoComplete.setOnItemClickListener { _, _, position, _ ->
            val selectedCategory = categoryFilterAutoComplete.adapter.getItem(position) as String
            if (selectedCategory == "All Categories") {
                // Reset category filter but maintain date filter
                when (currentFilter) {
                    TransactionFilter.ALL -> transactionViewModel.loadAllTransactions()
                    TransactionFilter.TODAY -> transactionViewModel.loadTodayTransactions()
                    TransactionFilter.WEEK -> transactionViewModel.loadWeekTransactions()
                    TransactionFilter.MONTH -> transactionViewModel.loadMonthTransactions()
                }
            } else {
                transactionViewModel.filterByCategory(selectedCategory)
            }
        }
    }

    private fun setupFab() {
        findViewById<FloatingActionButton>(R.id.addTransactionButton).setOnClickListener {
            val intent = Intent(this, AddTransactionActivity::class.java)
            addTransactionLauncher.launch(intent)
        }
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
            putExtra("TRANSACTION_DATE", transaction.date) // Pass as Long timestamp
            putExtra("TRANSACTION_CATEGORY", transaction.category)
            putExtra("TRANSACTION_MERCHANT", transaction.merchant)
            putExtra("TRANSACTION_DESCRIPTION", transaction.description)
        }
        addTransactionLauncher.launch(intent)
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