package com.example.financetracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Budget
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.utils.GuestUserManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

class BudgetViewModel(
    private val database: TransactionDatabase,
    application: Application
) : AndroidViewModel(application) {

    private val budgetDao = database.budgetDao()
    private val transactionDao = database.transactionDao()
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // LiveData for budgets
    private val _budgets = MutableLiveData<List<Budget>>()
    val budgets: LiveData<List<Budget>> = _budgets

    // LiveData for budget vs actual spending
    private val _budgetVsActual = MutableLiveData<Map<String, BudgetVsActual>>()
    val budgetVsActual: LiveData<Map<String, BudgetVsActual>> = _budgetVsActual

    // Data class for budget vs actual comparison
    data class BudgetVsActual(
        val category: String,
        val budgetAmount: Double,
        val spentAmount: Double,
        val percentUsed: Double
    )

    init {
        loadBudgets()
    }

    private fun loadBudgets() {
        val userId = auth.currentUser?.uid
            ?: GuestUserManager.getGuestUserId(getApplication())

        viewModelScope.launch {
            budgetDao.getAllBudgets(userId).collect { budgetList ->
                _budgets.value = budgetList
                calculateBudgetVsActual(budgetList)
            }
        }
    }

    private suspend fun calculateBudgetVsActual(budgets: List<Budget>) {
        val userId = auth.currentUser?.uid
            ?: GuestUserManager.getGuestUserId(getApplication())

        // Get the current month's start and end dates
        val calendar = Calendar.getInstance()
        val endDate = calendar.timeInMillis

        // Set to beginning of month
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startDate = calendar.timeInMillis

        // Get all transactions within the date range
        val transactions = withContext(Dispatchers.IO) {
            transactionDao.getTransactionsByDateRange(startDate, endDate, userId)
        }

        // Group transactions by category and calculate spent amounts
        val spentByCategory = transactions
            .filter { !it.isCredit } // Only consider expenses
            .groupBy { it.category }
            .mapValues { (_, txList) -> txList.sumOf { it.amount } }

        // Create budget vs actual map
        val budgetVsActualMap = budgets.associate { budget ->
            val spent = spentByCategory[budget.category] ?: 0.0
            val percentUsed = if (budget.amount > 0) (spent / budget.amount) * 100 else 0.0

            budget.category to BudgetVsActual(
                category = budget.category,
                budgetAmount = budget.amount,
                spentAmount = spent,
                percentUsed = percentUsed
            )
        }

        _budgetVsActual.value = budgetVsActualMap
    }

    /**
     * Add a new budget or update existing one
     */
    fun saveBudget(budget: Budget) {
        viewModelScope.launch {
            // Insert into Room database
            val id = budgetDao.insertBudget(budget)

            // Sync to Firestore if user is authenticated
            if (auth.currentUser != null) {
                syncBudgetToFirestore(budget.copy(id = id.toInt()))
            }
        }
    }

    /**
     * Auto-generate budgets based on past spending
     */
    fun generateAutoBudgets() {
        val userId = auth.currentUser?.uid
            ?: GuestUserManager.getGuestUserId(getApplication())

        viewModelScope.launch {
            // Get transactions from the last 3 months
            val calendar = Calendar.getInstance()
            val endDate = calendar.timeInMillis

            // Go back 3 months
            calendar.add(Calendar.MONTH, -3)
            val startDate = calendar.timeInMillis

            // Get all transactions within the date range
            val transactions = withContext(Dispatchers.IO) {
                transactionDao.getTransactionsByDateRange(startDate, endDate, userId)
            }

            // Group expenses by category and calculate average monthly spending
            val expensesByCategory = transactions
                .filter { !it.isCredit } // Only consider expenses
                .groupBy { it.category }

            // Calculate average monthly spending for each category
            val monthlyAverages = expensesByCategory.mapValues { (_, txList) ->
                val totalAmount = txList.sumOf { it.amount }
                // Round up to nearest 500
                Math.ceil(totalAmount / 3 / 500) * 500
            }

            // Get existing budgets to avoid duplication
            val existingBudgets = budgetDao.getAllBudgetsOneTime(userId)
            val existingCategories = existingBudgets.map { it.category }

            // Create budget entities for categories without existing budgets
            val newBudgets = monthlyAverages
                .filter { it.key !in existingCategories }
                .map { (category, amount) ->
                    Budget(
                        category = category,
                        amount = amount,
                        period = "Monthly",
                        autoGenerated = true,
                        userId = userId
                    )
                }

            // Insert all new budgets
            newBudgets.forEach { budget ->
                budgetDao.insertBudget(budget)

                // Sync to Firestore if user is authenticated
                if (auth.currentUser != null) {
                    syncBudgetToFirestore(budget)
                }
            }
        }
    }

    /**
     * Delete a budget
     */
    fun deleteBudget(budget: Budget) {
        viewModelScope.launch {
            budgetDao.deleteBudget(budget)

            // Delete from Firestore if user is authenticated
            if (auth.currentUser != null && budget.documentId.isNotEmpty()) {
                firestore.collection("users")
                    .document(budget.userId)
                    .collection("budgets")
                    .document(budget.documentId)
                    .delete()
            }
        }
    }

    private fun syncBudgetToFirestore(budget: Budget) {
        // Skip for guest users
        if (auth.currentUser == null) return

        // Create a document reference first to get an ID
        val docRef = firestore.collection("users")
            .document(budget.userId)
            .collection("budgets")
            .document()

        // Get the document ID
        val docId = docRef.id

        // Create a map with all budget data
        val budgetMap = hashMapOf(
            "id" to budget.id,
            "category" to budget.category,
            "amount" to budget.amount,
            "period" to budget.period,
            "autoGenerated" to budget.autoGenerated,
            "userId" to budget.userId,
            "documentId" to docId
        )

        // Save to Firestore
        docRef.set(budgetMap)
            .addOnSuccessListener {
                // Update local database with document ID
                viewModelScope.launch {
                    budgetDao.updateBudget(budget.copy(documentId = docId))
                }
            }
    }

    /**
     * Factory for creating BudgetViewModel with proper dependencies
     */
    class Factory(
        private val database: TransactionDatabase,
        private val application: Application
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BudgetViewModel::class.java)) {
                return BudgetViewModel(database, application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}