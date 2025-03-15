package com.example.financetracker

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.adapter.BudgetAdapter
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Budget
import com.example.financetracker.utils.CategoryUtils
import com.example.financetracker.utils.GuestUserManager
import com.example.financetracker.viewmodel.BudgetViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class BudgetActivity : BaseActivity() {

    override fun getLayoutResourceId(): Int = R.layout.activity_budget

    private lateinit var recyclerView: RecyclerView
    private lateinit var budgetAdapter: BudgetAdapter
    private lateinit var generateButton: Button
    private lateinit var addBudgetFab: FloatingActionButton
    override var auth = FirebaseAuth.getInstance()

    private val viewModel: BudgetViewModel by viewModels {
        BudgetViewModel.Factory(
            TransactionDatabase.getDatabase(application),
            application
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up the UI components
        setupUI()

        // Set up observers
        setupObservers()
    }

    private fun setupUI() {
        // Find views
        recyclerView = findViewById(R.id.budgetRecyclerView)
        generateButton = findViewById(R.id.generateBudgetsButton)
        addBudgetFab = findViewById(R.id.addBudgetFab)

        // Configure RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        budgetAdapter = BudgetAdapter(
            onEdit = { budget -> showEditBudgetDialog(budget) },
            onDelete = { budget -> deleteBudget(budget) }
        )
        recyclerView.adapter = budgetAdapter

        // Set click listeners
        generateButton.setOnClickListener {
            generateAutoBudgets()
        }

        addBudgetFab.setOnClickListener {
            showAddBudgetDialog()
        }

        // Set title
        supportActionBar?.title = "Budget Management"
    }

    private fun setupObservers() {
        // Observe budgets and budget vs actual data
        viewModel.budgets.observe(this) { budgets ->
            budgetAdapter.submitList(budgets)
        }

        viewModel.budgetVsActual.observe(this) { budgetVsActualMap ->
            budgetAdapter.setBudgetVsActualData(budgetVsActualMap)
        }
    }

    private fun showAddBudgetDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_budget, null)
        val categorySpinner = dialogView.findViewById<androidx.appcompat.widget.AppCompatSpinner>(R.id.categorySpinner)
        val amountEditText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.amountEditText)
        val periodSpinner = dialogView.findViewById<androidx.appcompat.widget.AppCompatSpinner>(R.id.periodSpinner)

        // Load categories into spinner
        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)

        // Use the same category loading functionality as transactions
        lifecycleScope.launch {
            CategoryUtils.loadCategoriesToSpinner(
                this@BudgetActivity,
                categorySpinner,
                userId
            )
        }

        // Show dialog
        AlertDialog.Builder(this)
            .setTitle("Add Budget")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val category = categorySpinner.selectedItem.toString()
                val amountText = amountEditText.text.toString()
                val period = periodSpinner.selectedItem.toString()

                if (amountText.isNotBlank()) {
                    try {
                        val amount = amountText.toDouble()
                        saveBudget(category, amount, period)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Amount cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditBudgetDialog(budget: Budget) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_budget, null)
        val categorySpinner = dialogView.findViewById<androidx.appcompat.widget.AppCompatSpinner>(R.id.categorySpinner)
        val amountEditText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.amountEditText)
        val periodSpinner = dialogView.findViewById<androidx.appcompat.widget.AppCompatSpinner>(R.id.periodSpinner)

        // Set initial values
        amountEditText.setText(budget.amount.toString())

        // Load categories into spinner
        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)

        lifecycleScope.launch {
            CategoryUtils.loadCategoriesToSpinner(
                this@BudgetActivity,
                categorySpinner,
                userId,
                budget.category
            )

            // Set period selection
            val periodAdapter = periodSpinner.adapter as android.widget.ArrayAdapter<*>
            val periodPosition = (0 until periodAdapter.count)
                .firstOrNull { periodAdapter.getItem(it) == budget.period } ?: 0
            periodSpinner.setSelection(periodPosition)
        }

        // Show dialog
        AlertDialog.Builder(this)
            .setTitle("Edit Budget")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val category = categorySpinner.selectedItem.toString()
                val amountText = amountEditText.text.toString()
                val period = periodSpinner.selectedItem.toString()

                if (amountText.isNotBlank()) {
                    try {
                        val amount = amountText.toDouble()

                        // Update the budget
                        val updatedBudget = budget.copy(
                            category = category,
                            amount = amount,
                            period = period,
                            autoGenerated = false  // No longer auto-generated after edit
                        )

                        viewModel.saveBudget(updatedBudget)
                        Toast.makeText(this, "Budget updated", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Amount cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveBudget(category: String, amount: Double, period: String) {
        val userId = auth.currentUser?.uid ?: GuestUserManager.getGuestUserId(applicationContext)

        val budget = Budget(
            category = category,
            amount = amount,
            period = period,
            autoGenerated = false,
            userId = userId
        )

        viewModel.saveBudget(budget)
        Toast.makeText(this, "Budget created", Toast.LENGTH_SHORT).show()
    }

    private fun deleteBudget(budget: Budget) {
        AlertDialog.Builder(this)
            .setTitle("Delete Budget")
            .setMessage("Are you sure you want to delete this budget?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteBudget(budget)
                Toast.makeText(this, "Budget deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateAutoBudgets() {
        AlertDialog.Builder(this)
            .setTitle("Generate Budgets")
            .setMessage("This will create budget suggestions based on your past 3 months of spending. Continue?")
            .setPositiveButton("Generate") { _, _ ->
                viewModel.generateAutoBudgets()
                Toast.makeText(this, "Budgets generated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}