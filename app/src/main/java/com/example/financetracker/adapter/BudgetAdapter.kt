package com.example.financetracker.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.R
import com.example.financetracker.database.entity.Budget
import com.example.financetracker.viewmodel.BudgetViewModel
import java.text.NumberFormat
import java.util.Locale

class BudgetAdapter(
    private val onEdit: (Budget) -> Unit,
    private val onDelete: (Budget) -> Unit
) : ListAdapter<Budget, BudgetAdapter.BudgetViewHolder>(BudgetDiffCallback()) {

    private var budgetVsActualMap: Map<String, BudgetViewModel.BudgetVsActual> = emptyMap()
    private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
        currency = java.util.Currency.getInstance("INR")
    }

    fun setBudgetVsActualData(data: Map<String, BudgetViewModel.BudgetVsActual>) {
        budgetVsActualMap = data
        notifyDataSetChanged() // Consider using more specific notification methods in production
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BudgetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_budget, parent, false)
        return BudgetViewHolder(view)
    }

    override fun onBindViewHolder(holder: BudgetViewHolder, position: Int) {
        val budget = getItem(position)
        holder.bind(budget, budgetVsActualMap[budget.category])
    }

    inner class BudgetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val categoryTextView: TextView = itemView.findViewById(R.id.categoryTextView)
        private val amountTextView: TextView = itemView.findViewById(R.id.amountTextView)
        private val spentTextView: TextView = itemView.findViewById(R.id.spentTextView)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val progressTextView: TextView = itemView.findViewById(R.id.progressTextView)
        private val editButton: ImageButton = itemView.findViewById(R.id.editBudgetButton)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteBudgetButton)

        fun bind(budget: Budget, budgetVsActual: BudgetViewModel.BudgetVsActual?) {
            categoryTextView.text = budget.category
            amountTextView.text = currencyFormatter.format(budget.amount)

            val spent = budgetVsActual?.spentAmount ?: 0.0
            val percentUsed = if (budget.amount > 0) {
                ((spent / budget.amount) * 100).coerceIn(0.0, 100.0)
            } else {
                0.0
            }

            spentTextView.text = "Spent: ${currencyFormatter.format(spent)}"

            // Set progress and color based on percentage used
            val progress = percentUsed.toInt().coerceIn(0, 100)
            progressBar.progress = progress
            progressTextView.text = "${progress}%"

            // Change progress bar color based on usage
            val context = itemView.context
            when {
                percentUsed > 90 -> {
                    context.getDrawable(R.drawable.progress_bar_red)?.let {
                        progressBar.progressDrawable = it
                    }
                    progressTextView.setTextColor(Color.RED)
                }
                percentUsed > 75 -> {
                    context.getDrawable(R.drawable.progress_bar_orange)?.let {
                        progressBar.progressDrawable = it
                    }
                    progressTextView.setTextColor(Color.parseColor("#FF9800")) // Orange
                }
                else -> {
                    context.getDrawable(R.drawable.progress_bar_green)?.let {
                        progressBar.progressDrawable = it
                    }
                    progressTextView.setTextColor(Color.parseColor("#4CAF50")) // Green
                }
            }

            // Set click listeners for edit and delete
            editButton.setOnClickListener { onEdit(budget) }
            deleteButton.setOnClickListener { onDelete(budget) }
        }
    }

    class BudgetDiffCallback : DiffUtil.ItemCallback<Budget>() {
        override fun areItemsTheSame(oldItem: Budget, newItem: Budget): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Budget, newItem: Budget): Boolean {
            return oldItem == newItem
        }
    }
}