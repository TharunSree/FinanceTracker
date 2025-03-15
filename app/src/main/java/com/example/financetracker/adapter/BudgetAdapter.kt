package com.example.financetracker.adapter

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

class BudgetAdapter(
    private val onEdit: (Budget) -> Unit,
    private val onDelete: (Budget) -> Unit
) : ListAdapter<Budget, BudgetAdapter.BudgetViewHolder>(DIFF_CALLBACK) {

    private var budgetVsActualMap: Map<String, BudgetViewModel.BudgetVsActual> = emptyMap()

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Budget>() {
            override fun areItemsTheSame(oldItem: Budget, newItem: Budget): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: Budget, newItem: Budget): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BudgetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_budget, parent, false)
        return BudgetViewHolder(view)
    }

    override fun onBindViewHolder(holder: BudgetViewHolder, position: Int) {
        val budget = getItem(position)
        val budgetVsActual = budgetVsActualMap[budget.category]
        holder.bind(budget, budgetVsActual, onEdit, onDelete)
    }

    fun setBudgetVsActualData(data: Map<String, BudgetViewModel.BudgetVsActual>) {
        budgetVsActualMap = data
        notifyDataSetChanged()
    }

    class BudgetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val categoryTextView: TextView = itemView.findViewById(R.id.categoryTextView)
        private val amountTextView: TextView = itemView.findViewById(R.id.amountTextView)
        private val spentTextView: TextView = itemView.findViewById(R.id.spentTextView)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val progressTextView: TextView = itemView.findViewById(R.id.progressTextView)
        private val editButton: ImageButton = itemView.findViewById(R.id.editBudgetButton)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteBudgetButton)

        fun bind(
            budget: Budget,
            budgetVsActual: BudgetViewModel.BudgetVsActual?,
            onEdit: (Budget) -> Unit,
            onDelete: (Budget) -> Unit
        ) {
            // Set basic budget info
            categoryTextView.text = budget.category
            amountTextView.text = "₹${String.format("%.2f", budget.amount)}"

            // Set budget vs. actual info
            if (budgetVsActual != null) {
                spentTextView.text = "Spent: ₹${String.format("%.2f", budgetVsActual.spentAmount)}"

                // Keep progress between 0 and 100%
                val progress = when {
                    budgetVsActual.percentUsed < 0 -> 0
                    budgetVsActual.percentUsed > 100 -> 100
                    else -> budgetVsActual.percentUsed.toInt()
                }

                progressBar.progress = progress
                progressTextView.text = "${progress}%"

                // Highlight if over budget
                if (budgetVsActual.percentUsed > 100) {
                    progressBar.progressDrawable = itemView.context.getDrawable(R.drawable.progress_bar_red)
                    progressTextView.setTextColor(itemView.context.getColor(R.color.red))
                } else if (budgetVsActual.percentUsed > 80) {
                    progressBar.progressDrawable = itemView.context.getDrawable(R.drawable.progress_bar_orange)
                    progressTextView.setTextColor(itemView.context.getColor(R.color.orange))
                } else {
                    progressBar.progressDrawable = itemView.context.getDrawable(R.drawable.progress_bar_green)
                    progressTextView.setTextColor(itemView.context.getColor(R.color.green))
                }
            } else {
                spentTextView.text = "Spent: ₹0.00"
                progressBar.progress = 0
                progressTextView.text = "0%"
                progressBar.progressDrawable = itemView.context.getDrawable(R.drawable.progress_bar_green)
                progressTextView.setTextColor(itemView.context.getColor(R.color.green))
            }

            // Set click listeners
            editButton.setOnClickListener { onEdit(budget) }
            deleteButton.setOnClickListener { onDelete(budget) }
        }
    }
}