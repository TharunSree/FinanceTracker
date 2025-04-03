package com.example.financetracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem // Keep this
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
// import android.widget.TableLayout // Remove
// import android.widget.TableRow // Remove
// import android.widget.TextView // Remove
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
// import androidx.appcompat.app.AppCompatActivity // BaseActivity handles this
import androidx.appcompat.widget.PopupMenu // Keep this
import androidx.lifecycle.lifecycleScope
// import androidx.core.content.ContextCompat // Remove if not used elsewhere
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView // Add this
// import com.example.financetracker.database.TransactionDatabase // ViewModel Factory handles this
// import com.example.financetracker.AddTransactionActivity // Keep this
// import com.example.financetracker.BaseActivity // Keep this
// import com.example.financetracker.R // Keep this
import com.example.financetracker.TransactionAdapter // Import the adapter
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.databinding.ActivityTransactionsBinding
// import com.example.financetracker.databinding.NavHeaderBinding // Keep if used in updateNavHeader()
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat // Keep this
import java.util.Locale // Keep this

class TransactionActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    override fun getLayoutResourceId(): Int = R.layout.activity_transactions

    private lateinit var binding: ActivityTransactionsBinding
    // private lateinit var transactionTableLayout: TableLayout // Remove
    private lateinit var filterChipGroup: ChipGroup
    private lateinit var categoryFilterAutoComplete: AutoCompleteTextView
    private lateinit var transactionRecyclerView: RecyclerView // Add this
    private lateinit var transactionAdapter: TransactionAdapter // Add this

    private var currentFilter: TransactionViewModel.FilterState = TransactionViewModel.FilterState.ALL // Use ViewModel's enum

    private val transactionViewModel: TransactionViewModel by viewModels {
        val database = TransactionDatabase.getDatabase(this)
        TransactionViewModel.Factory(database, application)
    }

    // Removed enum TransactionFilter as it's now in ViewModel

    private val addTransactionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // No need to explicitly call refreshTransactions if ViewModel handles updates correctly
            // The observer will catch the changes.
            // If updates aren't reflected automatically, you might need:
            // transactionViewModel.refreshCurrentFilter() // Add a method in ViewModel if needed
            Log.d("TransactionActivity", "Returned from Add/Edit - ViewModel should update list.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Binding is already handled by BaseActivity if you set it up like that
        // If not, use:
        binding = ActivityTransactionsBinding.inflate(layoutInflater)
        setContentView(binding.root)


        // --- Initialize Views ---
        // transactionTableLayout = findViewById(R.id.transactionTableLayout) // Remove
        filterChipGroup = binding.filterChipGroup // Use binding
        categoryFilterAutoComplete = binding.categoryFilterAutoComplete // Use binding
        transactionRecyclerView = binding.transactionRecyclerView // Use binding (Make sure ID matches XML)

        setupRecyclerView() // Call setup for RecyclerView
        setupFilters()
        setupCategoryFilter()
        setupFab()
        observeViewModel() // Consolidated observers
        updateNavHeader() // Keep if needed

        supportActionBar?.title = "Transactions"
        setupNavigationDrawer() // Keep this

        supportFragmentManager.setFragmentResultListener(
            EditTransactionDialogFragment.REQUEST_KEY,
            this // Lifecycle Owner
        ) { _, bundle ->
            val id = bundle.getLong(EditTransactionDialogFragment.RESULT_TRANSACTION_ID)
            val category = bundle.getString(EditTransactionDialogFragment.RESULT_NEW_CATEGORY)
            val merchant = bundle.getString(EditTransactionDialogFragment.RESULT_NEW_MERCHANT)

            if (id != 0L && category != null) {
                transactionViewModel.updateTransactionCategoryAndMerchant(id, category, merchant)
            }
        }
    }

    // --- Setup Methods ---

    private fun setupRecyclerView() {
        // Assuming you are using the ListAdapter approach ('TransactionAdapter.kt')
        transactionAdapter = TransactionAdapter(
            onTransactionLongClicked = { transaction, anchorView -> // Get transaction and anchor view
                // Option 1: Show Popup Menu first (if you have one)
                showPopupMenu(transaction, anchorView)

                // Option 2: Directly initiate edit on long click (if no menu needed)
                // initiateEdit(transaction)
            }
            // Add other listeners like onItemClicked if needed
        )
        transactionRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@TransactionActivity)
            adapter = transactionAdapter
        }
    }

    private fun setupFilters() {
        // Make sure default matches ViewModel state if necessary
        binding.chipAll.isChecked = true // Set initial chip state

        filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() // Can be empty if unchecked
            if (selectedId != null) {
                when (selectedId) {
                    R.id.chipAll -> transactionViewModel.loadAllTransactions()
                    R.id.chipToday -> transactionViewModel.loadTodayTransactions()
                    R.id.chipWeek -> transactionViewModel.loadWeekTransactions()
                    R.id.chipMonth -> transactionViewModel.loadMonthTransactions()
                }
                // Update currentFilter if needed for other logic, but ViewModel now drives state
                currentFilter = when(selectedId) {
                    R.id.chipAll -> TransactionViewModel.FilterState.ALL
                    R.id.chipToday -> TransactionViewModel.FilterState.TODAY
                    R.id.chipWeek -> TransactionViewModel.FilterState.WEEK
                    R.id.chipMonth -> TransactionViewModel.FilterState.MONTH
                    else -> TransactionViewModel.FilterState.ALL // Default case
                }
            } else {
                // Optional: Handle case where no chip is selected (if singleSelection=false)
                // Or force select 'All' if needed
                // binding.chipAll.isChecked = true
                // transactionViewModel.loadAllTransactions()
            }
        }
    }

    private fun setupCategoryFilter() {
        // Observe categories flow (StateFlow) from ViewModel
        lifecycleScope.launch {
            transactionViewModel.categoryNames.collect { categoryNames ->
                Log.d("TransactionActivity", "Updating categories: $categoryNames")
                val adapter = ArrayAdapter(
                    this@TransactionActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    listOf("All Categories") + categoryNames // Use fetched names
                )
                categoryFilterAutoComplete.setAdapter(adapter)
            }
        }


        categoryFilterAutoComplete.setOnItemClickListener { adapterView, _, position, _ ->
            val selectedCategory = adapterView.adapter.getItem(position) as String
            Log.d("TransactionActivity", "Category selected: $selectedCategory")
            transactionViewModel.filterByCategory(selectedCategory) // ViewModel handles "All Categories" logic
        }
    }

    private fun setupFab() {
        // Use binding
        binding.addTransactionButton.setOnClickListener {
            val intent = Intent(this, AddTransactionActivity::class.java)
            addTransactionLauncher.launch(intent)
        }
    }

    // --- Observers ---

    private fun observeViewModel() {
        // Observe the filtered transactions StateFlow from ViewModel
        lifecycleScope.launch {
            transactionViewModel.filteredTransactions.collect { transactions ->
                Log.d("TransactionActivity", "Observer received ${transactions.size} transactions.")
                transactionAdapter.submitList(transactions)
            }
        }

        transactionViewModel.loading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            // Optional: Disable interaction while loading
            filterChipGroup.isEnabled = !isLoading
            categoryFilterAutoComplete.isEnabled = !isLoading
            binding.addTransactionButton.isEnabled = !isLoading
        }

        transactionViewModel.errorMessage.observe(this) { errorMessage ->
            if (!errorMessage.isNullOrBlank()) {
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                // Optional: Clear the error message in ViewModel after showing
                // transactionViewModel.clearErrorMessage()
            }
        }
    }

    // Remove observeTransactions, observeLoading, observeError as they are merged above

    // --- Action/Helper Methods ---

    // Remove populateTable and createTableCell methods

    // Modify showPopupMenu to accept a generic View
    private fun showPopupMenu(transaction: Transaction, anchorView: View) {
        PopupMenu(this, anchorView).apply {
            // Ensure you have R.menu.transaction_options_menu with an R.id.action_edit item
            menuInflater.inflate(R.menu.transaction_options_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit -> {
                        // *** THIS IS THE KEY CHANGE ***
                        // Instead of launching AddTransactionActivity, show the dialog
                        initiateEdit(transaction)
                        true // Indicate menu item click was handled
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun initiateEdit(transaction: Transaction) {
        // Create an instance of the dialog using the factory method, passing the transaction
        val dialog = EditTransactionDialogFragment.newInstance(transaction)
        Log.d("TransactionActivity", "Initiating edit for ID ${transaction.id}, Category being passed: '${transaction.category}'")
        // Show the dialog using the FragmentManager
        dialog.show(supportFragmentManager, EditTransactionDialogFragment.TAG)
    }

    private fun onEditTransaction(transaction: Transaction) {
        val intent = Intent(this, AddTransactionActivity::class.java).apply {
            putExtra(AddTransactionActivity.EXTRA_TRANSACTION_ID, transaction.id) // Pass ID to edit
        }
        addTransactionLauncher.launch(intent) // Use launcher to get result

        // --- OR using DialogFragment ---
        /*
        val editTransactionDialog = EditTransactionDialogFragment(transaction) { updatedTransaction ->
            transactionViewModel.updateTransaction(updatedTransaction)
            // No need to call refresh - observer will update UI
        }
        editTransactionDialog.show(supportFragmentManager, "EditTransactionDialog")
        */
    }

    // Remove formatDate - it's now inside the Adapter's ViewHolder

    // Remove refreshTransactions - ViewModel calls (loadAll, loadToday etc.) now handle reloading data
    // The observer automatically updates the adapter when the ViewModel's data changes.
}