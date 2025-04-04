package com.example.financetracker.adapter

import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb // Import toArgb
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.R
import com.example.financetracker.database.entity.Category
import com.example.financetracker.ui.screens.ChartColors // Import for default colors
import com.example.financetracker.viewmodel.parseColor // Import helper function

class CategoryAdapter(
    private val onEditClick: (Category) -> Unit,
    private val onDeleteClick: (Category) -> Unit,
    // Add a listener for when the item (or color indicator) is clicked
    private val onColorAreaClick: (Category) -> Unit
) : ListAdapter<Category, CategoryAdapter.CategoryViewHolder>(CategoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.categoryNameText)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
        private val colorIndicatorView: View = itemView.findViewById(R.id.categoryColorIndicator) // Get indicator

        fun bind(category: Category) {
            nameTextView.text = category.name

            // --- Set Color Indicator ---
            val parsedColor = parseColor(category.colorHex) // Use helper
            val colorInt = if (parsedColor != androidx.compose.ui.graphics.Color.Transparent && parsedColor != androidx.compose.ui.graphics.Color.Gray) {
                parsedColor.toArgb() // Convert valid Compose Color to Android Color Int
            } else {
                // Generate default color based on name if hex is null/invalid
                ChartColors.getDefaultColorByName(category.name).toArgb()
            }

            // Set background color using GradientDrawable for rounded shape
            val background = colorIndicatorView.background
            if (background is GradientDrawable) {
                background.setColor(colorInt)
            } else {
                // Fallback if background is not a shape drawable
                colorIndicatorView.setBackgroundColor(colorInt)
            }
            // --- End Set Color Indicator ---


            // --- Set Click Listeners ---
            editButton.setOnClickListener {
                onEditClick(category)
            }
            deleteButton.setOnClickListener {
                onDeleteClick(category)
            }
            // Make the color indicator clickable to change color
            colorIndicatorView.setOnClickListener {
                onColorAreaClick(category)
            }
            // Optionally make the whole item clickable for color change too
            // itemView.setOnClickListener {
            //     onColorAreaClick(category)
            // }
            // --- End Set Click Listeners ---

            // Enable/disable delete based on isDefault flag
            //deleteButton.isEnabled = !category.isDefault
            //deleteButton.alpha = if (category.isDefault) 0.5f else 1.0f
            // Optionally disable editing default category names?
            // editButton.isEnabled = !category.isDefault
            // editButton.alpha = if (category.isDefault) 0.5f else 1.0f
        }
    }

    private class CategoryDiffCallback : DiffUtil.ItemCallback<Category>() {
        override fun areItemsTheSame(oldItem: Category, newItem: Category): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Category, newItem: Category): Boolean {
            return oldItem == newItem
        }
    }
}