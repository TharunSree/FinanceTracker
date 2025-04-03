package com.example.financetracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil.setContentView
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.AddTransactionActivity
import com.example.financetracker.BaseActivity
import com.example.financetracker.R
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.databinding.ActivityTransactionsBinding
import com.example.financetracker.databinding.NavHeaderBinding
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import java.text.SimpleDateFormat
import java.util.Locale

class TransactionActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    override fun getLayoutResourceId(): Int = R.layout.activity_transactions

    private lateinit var binding: ActivityTransactionsBinding
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
            // Refresh transactions when a new transaction is added or updated
            refreshTransactions()
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transactionTableLayout = findViewById(R.id.transactionTableLayout)
        filterChipGroup = findViewById(R.id.filterChipGroup)
        categoryFilterAutoComplete = findViewById(R.id.categoryFilterAutoComplete)

        setupFilters()
        setupCategoryFilter()
        setupFab()
        observeTransactions()
        observeLoading()
        observeError()
        updateNavHeader()

        // Set title in toolbar
        supportActionBar?.title = "Transactions"

        // Initialize the navigation drawer
        setupNavigationDrawer()
    }

    private fun setupFilters() {
        filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
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
                refreshTransactions()
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

    private fun observeTransactions() {
        transactionViewModel.filteredTransaction.observe(this) { transactions ->
            populateTable(transactions)
        }
    }

    private fun observeLoading() {
        transactionViewModel.loading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun observeError() {
        transactionViewModel.errorMessage.observe(this) { errorMessage ->
            if (!errorMessage.isNullOrBlank()) {
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun populateTable(transactions: List<Transaction>) {
        // Clear all rows (no need to keep header as it's separate now)
        transactionTableLayout.removeAllViews()

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

            cells.forEach { cell ->
                tableRow.addView(cell)
            }

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
        val editTransactionDialog = EditTransactionDialogFragment(transaction) { updatedTransaction ->
            transactionViewModel.updateTransaction(updatedTransaction)
            refreshTransactions()
        }
        editTransactionDialog.show(supportFragmentManager, "EditTransactionDialog")
    }

    private fun formatDate(timestamp: Long): String {
        // Use a format that includes time
        val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) // Example format
        return displayFormat.format(timestamp)
    }

    private fun refreshTransactions() {
        when (currentFilter) {
            TransactionFilter.ALL -> transactionViewModel.loadAllTransactions()
            TransactionFilter.TODAY -> transactionViewModel.loadTodayTransactions()
            TransactionFilter.WEEK -> transactionViewModel.loadWeekTransactions()
            TransactionFilter.MONTH -> transactionViewModel.loadMonthTransactions()
        }
    }
}