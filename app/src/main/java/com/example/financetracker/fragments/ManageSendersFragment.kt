package com.example.financetracker.fragments // Or your preferred package

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope // *** Use this import ***
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.R
import com.example.financetracker.adapter.SenderAdapter
import com.example.financetracker.utils.SenderListManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManageSendersFragment : DialogFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var senderAdapter: SenderAdapter
    private lateinit var emptyView: TextView
    private val TAG = "ManageSendersFragment"

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireActivity())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.fragment_manage_senders, null)

        // Initialize views
        recyclerView = view.findViewById(R.id.sendersRecyclerView)
        emptyView = view.findViewById(R.id.emptySendersText)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Initialize adapter
        senderAdapter = SenderAdapter { senderInfo, isEnabled ->
            // Use requireContext() safely as listener callback happens when fragment is attached
            SenderListManager.updateSenderStatus(requireContext(), senderInfo.name, isEnabled)
        }
        recyclerView.adapter = senderAdapter

        builder.setView(view)
            .setTitle("Manage Financial Senders")
            .setNegativeButton("Close") { dialog, _ -> dialog.dismiss() }

        return builder.create()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return null // View is set in onCreateDialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        Log.d(TAG, "onStart called, loading senders.")
        // Call loadSenders from here (or onResume, should be safer now with lifecycleScope)
        loadSenders()
    }


    private fun loadSenders() {
        // *** Use lifecycleScope (Fragment's main scope) ***
        lifecycleScope.launch {
            // This coroutine is tied to the Fragment's lifecycle, not the view's

            val context = context // Capture context at the start
            if (context == null) {
                Log.w(TAG, "Context is null when launching loadSenders coroutine.")
                return@launch
            }

            Log.d(TAG,"Coroutine launched on lifecycleScope.")

            // Fetch data in background
            val senders = try {
                withContext(Dispatchers.IO) {
                    SenderListManager.getManageableSenders(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting senders", e)
                emptyList<SenderListManager.SenderInfo>()
            }

            // *** Switch back to Main thread for UI updates ***
            withContext(Dispatchers.Main) {
                // *** CHECK if fragment is still added BEFORE touching views ***
                if (!isAdded) {
                    Log.d(TAG,"Fragment detached before UI update could be applied.")
                    return@withContext
                }

                // Check if views are still valid (especially important if using onCreateView)
                // Since we init in onCreateDialog, they should be valid if isAdded is true,
                // but adding checks doesn't hurt.
                if (view == null || !::recyclerView.isInitialized || !::emptyView.isInitialized || !::senderAdapter.isInitialized) {
                    Log.e(TAG, "Views are not ready during UI update phase. Aborting UI update.")
                    return@withContext
                }


                Log.d(TAG,"Updating UI with ${senders.size} senders.")
                if (senders.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    senderAdapter.submitList(senders) // Update adapter
                }
            } // End withContext(Main)
        } // End lifecycleScope.launch
    } // End loadSenders
}