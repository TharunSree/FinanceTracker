package com.example.financetracker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.database.entity.Transaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionAdapter(
    private var transactions: List<Transaction>,
    private val listener: OnTransactionInteractionListener
) : RecyclerView.Adapter<TransactionAdapter.TransactionViewHolder>() {

    fun getTransactions(): List<Transaction> = transactions

    interface OnTransactionInteractionListener {
        fun onEditTransaction(transaction: Transaction)
        fun onLongPressTransaction(transaction: Transaction)
        fun onDeleteTransaction(transaction: Transaction) // New interaction for delete
    }

    inner class TransactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.transactionName)
        val amountTextView: TextView = itemView.findViewById(R.id.transactionAmount)
        val dateTextView: TextView = itemView.findViewById(R.id.transactionDate)
        val categoryTextView: TextView = itemView.findViewById(R.id.transactionCategory)
        private val trashIcon: ImageView = itemView.findViewById(R.id.delete_icon) // Trash can icon

        fun bind(transaction: Transaction) {
            nameTextView.text = transaction.name
            amountTextView.text = "₹${transaction.amount}"
            // Convert Long to Date and then to String
            val date = Date(transaction.date)
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            dateTextView.text = dateFormat.format(date)
            categoryTextView.text = transaction.category

            // Handle long click for context menu
            itemView.setOnLongClickListener {
                listener.onLongPressTransaction(transaction)
                true
            }

            // Handle trash icon click (delete)
            trashIcon.setOnClickListener {
                listener.onDeleteTransaction(transaction)
            }
        }

        // To make trash icon visible when item is swiped enough
        fun showTrashIcon(visible: Boolean) {
            trashIcon.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        holder.bind(transactions[position])
    }

    override fun getItemCount(): Int = transactions.size

    fun updateData(newTransactions: List<Transaction>) {
        transactions = newTransactions
        notifyDataSetChanged()
    }

    fun getTransactionAt(position: Int): Transaction = transactions[position]
}