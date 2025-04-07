package com.example.financetracker.adapter // Or your preferred package

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.R
import com.example.financetracker.utils.SenderListManager // Import SenderInfo
import com.google.android.material.switchmaterial.SwitchMaterial

class SenderAdapter(
    private val onSenderToggled: (sender: SenderListManager.SenderInfo, isEnabled: Boolean) -> Unit
) : ListAdapter<SenderListManager.SenderInfo, SenderAdapter.SenderViewHolder>(SenderDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SenderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sender, parent, false)
        return SenderViewHolder(view)
    }

    override fun onBindViewHolder(holder: SenderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SenderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.senderNameTextView)
        private val enabledSwitch: SwitchMaterial = itemView.findViewById(R.id.senderEnabledSwitch)

        fun bind(senderInfo: SenderListManager.SenderInfo) {
            nameTextView.text = senderInfo.name
            // Disable switch for default senders if you don't want them editable
            // enabledSwitch.isEnabled = !senderInfo.isDefault

            // Set initial state without triggering listener
            enabledSwitch.setOnCheckedChangeListener(null) // Temporarily remove listener
            enabledSwitch.isChecked = senderInfo.isEnabled
            enabledSwitch.alpha = if (senderInfo.isDefault) 0.7f else 1.0f // Dim defaults slightly

            // Set listener to handle user toggles
            enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                onSenderToggled(senderInfo, isChecked)
            }
        }
    }

    private class SenderDiffCallback : DiffUtil.ItemCallback<SenderListManager.SenderInfo>() {
        override fun areItemsTheSame(oldItem: SenderListManager.SenderInfo, newItem: SenderListManager.SenderInfo): Boolean {
            return oldItem.name == newItem.name // Names should be unique identifiers here
        }

        override fun areContentsTheSame(oldItem: SenderListManager.SenderInfo, newItem: SenderListManager.SenderInfo): Boolean {
            return oldItem == newItem // Compare all fields
        }
    }
}