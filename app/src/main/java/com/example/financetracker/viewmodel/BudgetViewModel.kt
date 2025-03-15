package com.example.financetracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Budget
import com.example.financetracker.database.entity.Transaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class BudgetViewModel(
    private val database: TransactionDatabase,
    application: Application
) : AndroidViewModel(application) {

    private val db = FirebaseFirestore.getInstance()
    private val budgetDao = database.budgetDao()
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    // LiveData for all budgets
    private val _allBudgets = MutableLiveData<List<Budget>>()
    val allBudgets: LiveData<List<Budget>> = _allBudgets

    // Budget vs actual spending map
    private val _budgetVsActualMap = MutableLiveData<Map<String, BudgetVsActual>>()
    val budgetVsActualMap: LiveData<Map<String, BudgetVsActual>> = _budgetVsActualMap

    init {
        loadBudgets()
    }

    private fun loadBudgets() {
        val userId = auth.currentUser?.uid
        if (userId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val budgetsCollection = db.collection("budgets")
                        .whereEqualTo("userId", userId)
                        .get()
                        .await()

                    val budgetsList = budgetsCollection.toObjects(Budget::class.java)
                    _allBudgets.postValue(budgetsList)

                    calculateBudgetVsActual(budgetsList)
                } catch (e: Exception) {
                    // Handle error
                }
            }
        }
    }

    fun insertBudget(budget: Budget) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val doc = db.collection("budgets").document()
                val budgetWithDocId = budget.copy(documentId = doc.id)
                doc.set(budgetWithDocId).await()

                // Reload budgets
                loadBudgets()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun updateBudget(budget: Budget) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (budget.documentId.isNotEmpty()) {
                    db.collection("budgets")
                        .document(budget.documentId)
                        .set(budget)
                        .await()

                    // Reload budgets
                    loadBudgets()
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun deleteBudget(budget: Budget) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (budget.documentId.isNotEmpty()) {
                    db.collection("budgets")
                        .document(budget.documentId)
                        .delete()
                        .await()

                    // Reload budgets
                    loadBudgets()
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    private suspend fun calculateBudgetVsActual(budgets: List<Budget>) {
        try {
            val resultMap = mutableMapOf<String, BudgetVsActual>()
            val userId = auth.currentUser?.uid ?: return

            // Get all transactions
            val transactions = db.collection("transactions")
                .whereEqualTo("userId", userId)
                .get()
                .await()
                .toObjects(Transaction::class.java)

            budgets.forEach { budget ->
                val spentAmount = calculateSpentAmountForBudget(budget, transactions)
                resultMap[budget.category] = BudgetVsActual(budget.amount, spentAmount)
            }

            withContext(Dispatchers.Main) {
                _budgetVsActualMap.value = resultMap
            }
        } catch (e: Exception) {
            // Handle error
        }
    }

    private fun calculateSpentAmountForBudget(
        budget: Budget,
        transactions: List<Transaction>
    ): Double {
        val startDate = getStartDateForPeriod(budget.period)
        val endDate = Calendar.getInstance().time

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        // Filter transactions for the budget's category and within the date range
        return transactions.filter { transaction ->
            transaction.category == budget.category &&
                    isDateInRange(transaction.date.toString(), startDate, endDate, dateFormat) &&
                    !transaction.isCredit // Only count expenses, not credits/income
        }.sumOf { it.amount }
    }

    private fun isDateInRange(
        dateStr: String,
        startDate: Date,
        endDate: Date,
        dateFormat: SimpleDateFormat
    ): Boolean {
        try {
            val date = dateFormat.parse(dateStr) ?: return false
            return !date.before(startDate) && !date.after(endDate)
        } catch (e: Exception) {
            return false
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


    private fun getStartDateForPeriod(period: String): Date {
        val calendar = Calendar.getInstance()

        when (period.lowercase(Locale.getDefault())) {
            "daily" -> {
                // Today at 00:00:00
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }

            "weekly" -> {
                // Beginning of the current week
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }

            "monthly" -> {
                // Beginning of the current month
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }

            "yearly" -> {
                // Beginning of the current year
                calendar.set(Calendar.DAY_OF_YEAR, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }
        }

        return calendar.time
    }

    data class BudgetVsActual(
        val budgetAmount: Double,
        val spentAmount: Double
    )
}