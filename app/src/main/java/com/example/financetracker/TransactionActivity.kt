package com.example.financetracker

import android.app.ProgressDialog.show
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.PopupMenu
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.viewmodel.TransactionViewModel
import java.text.SimpleDateFormat
import java.util.Locale

class TransactionsActivity : AppCompatActivity(),
    TransactionAdapter.OnTransactionInteractionListener {

    private lateinit var transactionAdapter: TransactionAdapter

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

        setupRecyclerView()
        setupAddTransactionButton()
        observeTransactions()
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.transactionRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        transactionAdapter = TransactionAdapter(emptyList(), this)
        recyclerView.adapter = transactionAdapter
    }

    private fun setupAddTransactionButton() {
        findViewById<Button>(R.id.addTransactionButton).setOnClickListener {
            val intent = Intent(this, AddTransactionActivity::class.java)
            addTransactionLauncher.launch(intent)
        }
    }

    private fun observeTransactions() {
        transactionViewModel.transactions.observe(this) { transactions ->
            transactionAdapter.updateData(transactions)
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

    override fun onEditTransaction(transaction: Transaction) {
        val editDialog = EditTransactionDialogFragment(transaction) { updatedTransaction ->
            transactionViewModel.updateTransaction(updatedTransaction)
        }
        editDialog.show(supportFragmentManager, "EditTransactionDialog")
    }

    override fun onDeleteTransaction(transaction: Transaction) {
        transactionViewModel.deleteTransaction(transaction)
    }

    override fun onLongPressTransaction(transaction: Transaction) {
        val view = findViewById<RecyclerView>(R.id.transactionRecyclerView)
            .findViewHolderForAdapterPosition(
                transactionAdapter.getTransactions().indexOf(transaction)
            )?.itemView ?: return

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
}