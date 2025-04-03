package com.example.financetracker // Or your preferred package

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.R
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.databinding.ListItemTransactionBinding // Import generated binding class
import java.text.SimpleDateFormat
import java.util.Locale

class TransactionAdapter(
    private val onTransactionLongClicked: (Transaction, View) -> Unit
    // Add onItemClicked lambda if you need short click action
    // private val onTransactionClicked: (Transaction) -> Unit
) : ListAdapter<Transaction, TransactionAdapter.TransactionViewHolder>(TransactionDiffCallback()) {

    // ViewHolder holds the views for a single item
    inner class TransactionViewHolder(private val binding: ListItemTransactionBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(transaction: Transaction) {
            binding.textViewName.text = transaction.name
            binding.textViewAmount.text = String.format(Locale.getDefault(), "₹%.2f", transaction.amount) // Format amount
            binding.textViewCategory.text = transaction.category ?: "Uncategorized" // Handle null category
            binding.textViewDate.text = formatDate(transaction.date)

            // Set long click listener on the card view itself
            binding.root.setOnLongClickListener {
                onTransactionLongClicked(transaction, it) // Pass transaction and the view (CardView)
                true // Indicate the click was handled
            }

            // Set short click listener if needed
            /*
            binding.root.setOnClickListener {
                onTransactionClicked(transaction)
            }
            */
        }

        private fun formatDate(timestamp: Long): String {
            // Consistent date formatting
            val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return displayFormat.format(timestamp)
        }
    }

    // Creates new ViewHolders (invoked by the layout manager)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val binding = ListItemTransactionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TransactionViewHolder(binding)
    }

    // Replaces the contents of a ViewHolder (invoked by the layout manager)
    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val currentTransaction = getItem(position)
        holder.bind(currentTransaction)
    }

    // DiffUtil helps efficiently update the list
    class TransactionDiffCallback : DiffUtil.ItemCallback<Transaction>() {
        override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
            // Check if items represent the same object (e.g., by ID)
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
            // Check if the content/data of the items are the same
            return oldItem == newItem // Assumes Transaction is a data class
        }
    }
}