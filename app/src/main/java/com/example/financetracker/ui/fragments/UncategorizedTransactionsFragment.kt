package com.example.financetracker.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import com.example.financetracker.TransactionAdapter
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.databinding.FragmentUncategorizedTransactionsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UncategorizedTransactionsFragment : Fragment() {

    private var _binding: FragmentUncategorizedTransactionsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUncategorizedTransactionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val transactionDao = TransactionDatabase.getDatabase(requireContext()).transactionDao()

        binding.recyclerViewTransactions.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val uncategorizedTransactions = transactionDao.getUncategorizedTransactions()
            withContext(Dispatchers.Main) {
                if (uncategorizedTransactions.isEmpty()) {
                    binding.textViewEmptyMessage.visibility = View.VISIBLE
                    binding.recyclerViewTransactions.visibility = View.GONE
                } else {
                    binding.textViewEmptyMessage.visibility = View.GONE
                    binding.recyclerViewTransactions.visibility = View.VISIBLE
                    binding.recyclerViewTransactions.adapter =
                        TransactionAdapter(uncategorizedTransactions, object : TransactionAdapter.OnTransactionInteractionListener {
                            override fun onEditTransaction(transaction: com.example.financetracker.database.entity.Transaction) {
                                // Handle edit transaction logic
                            }

                            override fun onLongPressTransaction(transaction: com.example.financetracker.database.entity.Transaction) {
                                // Handle long press transaction logic
                            }

                            override fun onDeleteTransaction(transaction: com.example.financetracker.database.entity.Transaction) {
                                // Handle delete transaction logic
                            }
                        })
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}