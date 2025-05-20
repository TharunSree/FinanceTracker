package com.example.financetracker // Adjust package if needed

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button // Keep Button for type casting if needed, or use MaterialButton directly
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.activities.BaseActivityWithBack
import com.example.financetracker.adapter.SenderAdapter
import com.example.financetracker.databinding.ActivityManageSendersBinding
import com.example.financetracker.utils.SenderListManager
import com.google.android.material.button.MaterialButton // Import MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManageSendersActivity : BaseActivityWithBack() {

    // If NOT using BaseActivityWithBack, uncomment and implement:
    // override fun getLayoutResourceId(): Int = R.layout.activity_manage_senders
    // override fun getScreenTitle(): String = "Manage Senders"

    private lateinit var binding: ActivityManageSendersBinding
    private lateinit var senderAdapter: SenderAdapter
    private val TAG = "ManageSendersActivity"

    // State
    private var isSelectionMode = false
    private val selectedSenders = mutableSetOf<String>()
    private var currentSenderList = listOf<SenderListManager.SenderInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageSendersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // setupToolbar() // Handled by BaseActivityWithBack

        setupRecyclerView()
        setupAdapter()
        binding.sendersRecyclerView.adapter = senderAdapter

        setupDeleteButton()
        setupToolbar()
        setupSelectAllButton() // <<< ADDED: Setup for the new button
        setupBackButtonInterceptor()

        Log.d(TAG, "Activity created, loading senders.")
        loadSenders()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    // --- Setup Functions ---

    private fun setupRecyclerView() {
        binding.sendersRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupAdapter() {
        senderAdapter = SenderAdapter(
            onSenderToggled = { senderInfo, isEnabled ->
                SenderListManager.updateSenderStatus(this, senderInfo.name, isEnabled)
                currentSenderList = currentSenderList.map {
                    if (it.name == senderInfo.name) it.copy(isEnabled = isEnabled) else it
                }
                updateUiForSelectionMode()
            },
            onItemClicked = { senderInfo ->
                if (isSelectionMode && !senderInfo.isDefault) {
                    toggleSelection(senderInfo.name)
                } else if (isSelectionMode && senderInfo.isDefault) {
                    Toast.makeText(this, "Default senders cannot be deleted", Toast.LENGTH_SHORT).show()
                }
            },
            onItemLongClicked = { senderInfo ->
                if (!isSelectionMode) {
                    enterSelectionMode(senderInfo)
                    true
                } else {
                    false
                }
            }
        )
        senderAdapter.isSelectionMode = isSelectionMode
    }

    private fun setupDeleteButton() {
        binding.deleteSelectedButton.setOnClickListener {
            showMultiDeleteConfirmationDialog()
        }
    }

    // <<< --- ADDED: Setup for Select All Button --- >>>
    private fun setupSelectAllButton() {
        binding.selectAllButton.setOnClickListener {
            handleSelectAllClick()
        }
    }
    // <<< --- End Added --- >>>

    private fun setupBackButtonInterceptor() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // --- Selection Mode Logic ---

    private fun enterSelectionMode(initialSender: SenderListManager.SenderInfo) {
        if (isSelectionMode) return
        isSelectionMode = true
        selectedSenders.clear()
        if (!initialSender.isDefault) {
            selectedSenders.add(initialSender.name)
        } else {
            Toast.makeText(this, "Default senders cannot be deleted", Toast.LENGTH_SHORT).show()
        }
        updateUiForSelectionMode()
        supportActionBar?.title = "Select Senders" // Optional: Update title
    }

    private fun exitSelectionMode() {
        if (!isSelectionMode) return
        isSelectionMode = false
        selectedSenders.clear()
        updateUiForSelectionMode()
        supportActionBar?.title = "Manage Senders" // Optional: Reset title
    }

    private fun toggleSelection(senderName: String) {
        if (!isSelectionMode) return
        val info = currentSenderList.find { it.name == senderName }
        if (info?.isDefault == true) { return } // Should be caught by adapter, but double-check

        if (selectedSenders.contains(senderName)) {
            selectedSenders.remove(senderName)
        } else {
            selectedSenders.add(senderName)
        }
        updateUiForSelectionMode()
    }

    // <<< --- ADDED: Logic for Select All Button Click --- >>>
    private fun handleSelectAllClick() {
        if (!isSelectionMode) return

        val deletableSenderNames = currentSenderList
            .filter { !it.isDefault }
            .map { it.name }
            .toSet()

        // If not all deletable items are selected, select all deletable items.
        // Otherwise (all deletable items are already selected), deselect all.
        if (selectedSenders != deletableSenderNames) {
            selectedSenders.clear()
            selectedSenders.addAll(deletableSenderNames)
            Log.d(TAG, "Select All clicked: Selecting ${selectedSenders.size} items.")
        } else {
            selectedSenders.clear()
            Log.d(TAG, "Deselect All clicked: Clearing selection.")
        }
        updateUiForSelectionMode() // Update adapter and button states
    }
    // <<< --- End Added --- >>>


    private fun updateUiForSelectionMode() {
        if (!::senderAdapter.isInitialized) return

        senderAdapter.isSelectionMode = isSelectionMode
        senderAdapter.submitListWithSelection(currentSenderList, selectedSenders)

        // Calculate counts for button states
        val deletableItems = currentSenderList.filter { !it.isDefault }
        val deletableCount = deletableItems.size
        val selectedDeletableCount = selectedSenders.size // selectedSenders only contains non-defaults now

        if (isSelectionMode) {
            // Show the whole container
            binding.bottomActionContainer.visibility = View.VISIBLE

            // Update Delete Button
            binding.deleteSelectedButton.text = if (selectedDeletableCount > 0) "Delete ($selectedDeletableCount)" else "Delete Selected"
            binding.deleteSelectedButton.isEnabled = selectedDeletableCount > 0

            // Update Select All Button
            if (deletableCount > 0 && selectedDeletableCount == deletableCount) {
                // All deletable items are selected
                binding.selectAllButton.text = "Deselect All"
            } else {
                binding.selectAllButton.text = "Select All"
            }
            // Enable select all only if there are deletable items
            binding.selectAllButton.isEnabled = deletableCount > 0

        } else {
            binding.bottomActionContainer.visibility = View.GONE
        }
        // Optional: Update Toolbar Title based on selection count
        if (isSelectionMode) {
            supportActionBar?.title = if (selectedDeletableCount > 0) "$selectedDeletableCount Selected" else "Select Senders"
        } else {
            supportActionBar?.title = "Manage Senders"
        }
    }


    // --- Data Loading ---
    private fun loadSenders() {
        lifecycleScope.launch {
            Log.d(TAG,"Coroutine launched for loadSenders.")
            val senders = try {
                withContext(Dispatchers.IO) {
                    SenderListManager.getManageableSenders(applicationContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting senders", e)
                Toast.makeText(this@ManageSendersActivity, "Error loading senders", Toast.LENGTH_SHORT).show()
                emptyList()
            }

            currentSenderList = senders

            withContext(Dispatchers.Main) {
                Log.d(TAG,"Updating UI after loading ${senders.size} senders.")
                if (senders.isEmpty()) {
                    binding.emptySendersText.visibility = View.VISIBLE
                    binding.sendersRecyclerView.visibility = View.GONE
                } else {
                    binding.emptySendersText.visibility = View.GONE
                    binding.sendersRecyclerView.visibility = View.VISIBLE
                }
                // Update UI based on current selection mode AFTER getting new list
                // This also submits the list to the adapter
                updateUiForSelectionMode()
            }
        }
    }

    // --- Deletion Logic ---
    private fun showMultiDeleteConfirmationDialog() {
        val nonDefaultToDelete = selectedSenders.filter { name ->
            currentSenderList.find { it.name == name }?.isDefault == false
        }.toSet()

        if (nonDefaultToDelete.isEmpty()) {
            Toast.makeText(this, "No deletable (non-default) senders selected.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Remove Senders?")
            .setMessage("Are you sure you want to remove ${nonDefaultToDelete.size} selected sender(s)?")
            .setPositiveButton("Remove") { _, _ ->
                nonDefaultToDelete.forEach { senderName ->
                    SenderListManager.removeSender(applicationContext, senderName)
                }
                Toast.makeText(this, "${nonDefaultToDelete.size} sender(s) removed.", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                loadSenders()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- BaseActivityWithBack Overrides (If using) ---
    override fun getLayoutResourceId(): Int = R.layout.activity_manage_senders
    override fun getScreenTitle(): String = "Manage Senders" // Initial title

}