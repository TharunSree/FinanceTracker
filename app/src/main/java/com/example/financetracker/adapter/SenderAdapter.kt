package com.example.financetracker.adapter // Or your preferred package

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.R
import com.example.financetracker.utils.SenderListManager
import com.google.android.material.switchmaterial.SwitchMaterial

class SenderAdapter(
    private val onSenderToggled: (sender: SenderListManager.SenderInfo, isEnabled: Boolean) -> Unit,
    private val onItemClicked: (sender: SenderListManager.SenderInfo) -> Unit,
    private val onItemLongClicked: (sender: SenderListManager.SenderInfo) -> Boolean
) : ListAdapter<SenderAdapter.SenderAdapterItem, SenderAdapter.SenderViewHolder>(SenderDiffCallback()) {

    // Internal wrapper for list items to manage selection state for DiffUtil
    data class SenderAdapterItem(
        val senderInfo: SenderListManager.SenderInfo,
        var isSelected: Boolean = false
    )

    var isSelectionMode: Boolean = false
        set(value) {
            field = value
            // Note: You might need to explicitly call notifyDataSetChanged() here if
            // just changing the mode doesn't trigger a redraw automatically
            // However, the fragment usually handles this by resubmitting the list.
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SenderViewHolder {
        Log.d("SenderAdapter", "onCreateViewHolder called")
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sender, parent, false)
        return SenderViewHolder(view)
    }

    override fun onBindViewHolder(holder: SenderViewHolder, position: Int) {
        Log.d("SenderAdapter", "onBindViewHolder called for position $position")
        holder.bind(getItem(position))
    }

    // Helper to update the list, mapping SenderInfo to SenderAdapterItem with selection state
    fun submitListWithSelection(newList: List<SenderListManager.SenderInfo>, selectedItems: Set<String>) {
        val adapterItems = newList.map { info ->
            SenderAdapterItem(info, selectedItems.contains(info.name))
        }
        // Use the ListAdapter's submitList for efficient updates
        submitList(adapterItems)
        Log.d("SenderAdapter", "submitListWithSelection called. New list size: ${adapterItems.size}, Adapter count: $itemCount")
    }

    // --- ViewHolder ---
    inner class SenderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.senderNameTextView)
        private val enabledSwitch: SwitchMaterial = itemView.findViewById(R.id.senderEnabledSwitch)
        private val checkBox: CheckBox = itemView.findViewById(R.id.senderCheckBox)

        fun bind(adapterItem: SenderAdapterItem) {
            val senderInfo = adapterItem.senderInfo
            Log.v("SenderAdapter", "Binding item: ${senderInfo.name}, Selected: ${adapterItem.isSelected}, Mode: $isSelectionMode")

            try {
                nameTextView.text = senderInfo.name

                if (isSelectionMode) {
                    // Selection Mode UI
                    enabledSwitch.visibility = View.GONE
                    checkBox.visibility = View.VISIBLE
                    checkBox.isEnabled = !senderInfo.isDefault // Cannot select/delete defaults
                    checkBox.alpha = if (senderInfo.isDefault) 0.5f else 1.0f

                    // Set checkbox state WITHOUT triggering listener
                    checkBox.setOnCheckedChangeListener(null)
                    checkBox.isChecked = adapterItem.isSelected && !senderInfo.isDefault

                    // Handle item click to toggle selection
                    itemView.setOnClickListener {
                        if (!senderInfo.isDefault) {
                            Log.d("SenderAdapter", "Item clicked (selection mode): ${senderInfo.name}")
                            onItemClicked(senderInfo) // Notify fragment
                        } else {
                            Toast.makeText(itemView.context, "Default senders cannot be deleted", Toast.LENGTH_SHORT).show()
                        }
                    }
                    itemView.setOnLongClickListener(null) // Disable long click in this mode

                } else {
                    // Normal Mode UI
                    enabledSwitch.visibility = View.VISIBLE
                    checkBox.visibility = View.GONE

                    // Set switch state and listener
                    enabledSwitch.setOnCheckedChangeListener(null) // Remove listener before setting state
                    enabledSwitch.isChecked = senderInfo.isEnabled
                    enabledSwitch.alpha = 1.0f // Always full alpha in normal mode (dimming handled elsewhere if needed)
                    enabledSwitch.isEnabled = true // Switch is always enabled

                    enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                        Log.d("SenderAdapter", "Switch toggled for ${senderInfo.name}: $isChecked")
                        onSenderToggled(senderInfo, isChecked) // Notify fragment
                    }

                    // Handle item long click to enter selection mode
                    itemView.setOnClickListener(null)
                    itemView.setOnLongClickListener {
                        Log.d("SenderAdapter", "Item long clicked (normal mode): ${senderInfo.name}")
                        onItemLongClicked(senderInfo) // Notify fragment
                    }
                }
                // Log.v("SenderAdapter", "Binding finished successfully for: ${senderInfo.name}")

            } catch (e: Exception) {
                Log.e("SenderAdapter", "Error binding item at position $adapterPosition: ${senderInfo.name}", e)
                nameTextView.text = "Error binding ${senderInfo.name}"
                nameTextView.setTextColor(android.graphics.Color.RED)
            }
        }
    }

    // DiffUtil for the wrapper item
    private class SenderDiffCallback : DiffUtil.ItemCallback<SenderAdapterItem>() {
        override fun areItemsTheSame(oldItem: SenderAdapterItem, newItem: SenderAdapterItem): Boolean {
            return oldItem.senderInfo.name == newItem.senderInfo.name
        }
        override fun areContentsTheSame(oldItem: SenderAdapterItem, newItem: SenderAdapterItem): Boolean {
            // Compare both the underlying info AND the selection state
            return oldItem == newItem
        }
    }
}