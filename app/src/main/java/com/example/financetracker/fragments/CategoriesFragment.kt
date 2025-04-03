package com.example.financetracker.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels // Use KTX delegate
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.R
import com.example.financetracker.adapter.CategoryAdapter
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Category
import com.example.financetracker.ui.screens.ChartColors // Import ChartColors
import com.example.financetracker.utils.CategoryUtils
import com.example.financetracker.viewmodel.TransactionViewModel // Import ViewModel
import com.example.financetracker.viewmodel.parseColor // Import parseColor
import com.github.dhaval2404.colorpicker.ColorPickerDialog // Import Color Picker
import com.github.dhaval2404.colorpicker.model.ColorShape // Import Color Shape
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toArgb

class CategoriesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var database: TransactionDatabase
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var fab: FloatingActionButton
    private lateinit var emptyView: TextView
    private val TAG = "CategoriesFragment"

    private val transactionViewModel: TransactionViewModel by viewModels {
        TransactionViewModel.Factory(
            TransactionDatabase.getDatabase(requireActivity().applicationContext),
            requireActivity().application
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_categories, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            Log.d(TAG, "Initializing CategoriesFragment")
            // Database instance might not be needed directly if ViewModel handles all DB access
            // database = TransactionDatabase.getDatabase(requireContext())

            recyclerView = view.findViewById(R.id.categoriesRecyclerView)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())

            try { emptyView = view.findViewById(R.id.emptyCategoriesText) }
            catch (e: Exception) { Log.e(TAG, "Empty view not found", e) }

            val userId = auth.currentUser?.uid // Get userId or handle null case

            // --- Initialize Adapter with all listeners ---
            categoryAdapter = CategoryAdapter(
                onEditClick = { category -> showEditCategoryDialog(category) },
                onDeleteClick = { category -> deleteCategory(category) },
                onColorAreaClick = { category -> showColorPicker(category) } // Add color click handler
            )
            // --- End Adapter Initialization ---

            recyclerView.adapter = categoryAdapter

            // Setup FAB (ensure ID exists in Activity layout hosting this fragment)
            try {
                fab = requireActivity().findViewById(R.id.addCategoryFab)
                fab.visibility = View.VISIBLE // Make sure FAB is visible when this fragment is shown
                fab.setOnClickListener { showAddCategoryDialog() }
            } catch (e: Exception) {
                Log.e(TAG, "Could not find addCategoryFab in Activity layout", e)
            }

            observeCategories(userId)

        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated", e)
            Toast.makeText(context, "Error setting up categories: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeCategories(userId: String?) {
        // Use the database instance from the ViewModel's factory or inject ViewModel directly
        val dao = TransactionDatabase.getDatabase(requireContext()).categoryDao()
        // Inside your CategoriesFragment, likely in onViewCreated or similar

// Assuming 'dao', 'userId', 'categoryAdapter', 'emptyView' are initialized appropriately
// Example using viewLifecycleOwner.lifecycleScope
        viewLifecycleOwner.lifecycleScope.launch {
            // Observe the Flow of categories for the current user (or null for guest/global)
            dao.getAllCategories(userId) // This Flow includes user-specific and global categories
                .catch { e ->
                    // Handle any errors during Flow collection
                    Log.e(TAG, "Error observing categories", e)
                    // Optionally show an error message to the user
                }
                .collect { categories ->
                    // This block executes whenever the category list changes in the database
                    Log.d(TAG, "Observed ${categories.size} categories for user $userId")

                    // Update the RecyclerView adapter with the latest list
                    categoryAdapter.submitList(categories)

                    // Show/hide the empty view based on the list content
                    // Ensure emptyView is initialized before accessing visibility
                    if (::emptyView.isInitialized) {
                        emptyView.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
                        Log.d(TAG, "Empty view visibility set to: ${emptyView.visibility}")
                    } else {
                        Log.w(TAG, "emptyView not initialized when trying to set visibility.")
                    }

                    // --- REMOVED THE BLOCK THAT CALLED CategoryUtils.addDefaultCategories ---
                    // The fragment should NOT be responsible for adding defaults here.
                    // The CategoryUtils.initializeCategories() function (called once elsewhere, e.g., MainActivity)
                    // handles the initial setup, including adding defaults if the DB is empty for the user.
                    // The Flow will automatically emit the updated list if defaults were added during initialization.
                }
        }


    }



    override fun onResume() {
        super.onResume()
        try { if(::fab.isInitialized) fab.visibility = View.VISIBLE } catch (e: Exception) {Log.e(TAG, "FAB error onResume", e)}
    }

    override fun onPause() {
        super.onPause()
        try { if(::fab.isInitialized) fab.visibility = View.GONE } catch (e: Exception) {Log.e(TAG, "FAB error onPause", e)}
    }

    // --- Function to show Color Picker ---
    private fun showColorPicker(category: Category) {
        val currentHexColor = category.colorHex
        // Parse current color or get default for picker initial state
        val defaultColorInt = try {
            if (!currentHexColor.isNullOrBlank()) {
                android.graphics.Color.parseColor(currentHexColor)
            } else {
                ChartColors.getDefaultColorByName(category.name).toArgb() // Use default
            }
        } catch (e: Exception) { android.graphics.Color.GRAY }

        ColorPickerDialog
            .Builder(requireContext())
            .setTitle("Pick Color for ${category.name}")
            .setColorShape(ColorShape.SQAURE) // Or CIRCLE
            .setDefaultColor(defaultColorInt) // Set current/default color
            .setColorListener { colorInt, colorHex -> // Use the listener that gives both Int and Hex
                Log.d(TAG, "Color selected: $colorHex for category ID ${category.id}")
                // Call ViewModel to update the color in the database
                transactionViewModel.updateCategoryColor(category.id, colorHex) // Pass hex string
            }
            .setNegativeButton("Cancel")
            // .setPositiveButton("Save") // Listener above handles save
            .show()
    }

    private fun showAddCategoryDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_category, null)
        val nameEditText = dialogView.findViewById<EditText>(R.id.categoryNameEditText)

        AlertDialog.Builder(requireContext())
            .setTitle("Add Category")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = nameEditText.text.toString().trim()
                if (name.isNotEmpty()) {
                    addCategory(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditCategoryDialog(category: Category) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_category, null)
        val nameEditText = dialogView.findViewById<EditText>(R.id.categoryNameEditText)
        nameEditText.setText(category.name)

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Category")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = nameEditText.text.toString().trim()
                if (name.isNotEmpty()) {
                    updateCategory(category, name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addCategory(name: String) {
        val userId = auth.currentUser?.uid ?: "guest_user"

        lifecycleScope.launch {
            try {
                val category = Category(
                    name = name,
                    userId = userId,
                    isDefault = false
                )

                // Use CategoryUtils to add category
                CategoryUtils.addCategory(requireContext(), category)

                Toast.makeText(requireContext(), "Category added", Toast.LENGTH_SHORT).show()

                // Refresh categories

            } catch (e: Exception) {
                Log.e(TAG, "Error adding category", e)
                Toast.makeText(requireContext(), "Error adding category: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateCategory(category: Category, newName: String) {
        lifecycleScope.launch {
            try {
                val updatedCategory = category.copy(name = newName)

                // Use CategoryUtils to update category
                CategoryUtils.updateCategory(requireContext(), updatedCategory)

                Toast.makeText(requireContext(), "Category updated", Toast.LENGTH_SHORT).show()

                // Refresh categories

            } catch (e: Exception) {
                Log.e(TAG, "Error updating category", e)
                Toast.makeText(requireContext(), "Error updating category: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteCategory(category: Category) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Category")
            .setMessage("Are you sure you want to delete this category?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        CategoryUtils.deleteCategory(requireContext(), category)
                        Toast.makeText(requireContext(), "Category deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting category", e)
                        Toast.makeText(requireContext(), "Error deleting category: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}