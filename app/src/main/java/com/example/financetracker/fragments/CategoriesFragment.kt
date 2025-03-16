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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.R
import com.example.financetracker.adapter.CategoryAdapter
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Category
import com.example.financetracker.utils.CategoryUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class CategoriesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var database: TransactionDatabase
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var fab: FloatingActionButton
    private lateinit var emptyView: TextView
    private val TAG = "CategoriesFragment"

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
            database = TransactionDatabase.getDatabase(requireContext())

            recyclerView = view.findViewById(R.id.categoriesRecyclerView)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())

            try {
                emptyView = view.findViewById(R.id.emptyCategoriesText)
            } catch (e: Exception) {
                Log.e(TAG, "Empty view not found", e)
            }

            val userId = auth.currentUser?.uid ?: "guest_user"
            Log.d(TAG, "User ID: $userId")

            categoryAdapter = CategoryAdapter(
                onEdit = { category -> showEditCategoryDialog(category) },
                onDelete = { category -> deleteCategory(category) }
            )

            recyclerView.adapter = categoryAdapter

            fab = requireActivity().findViewById(R.id.addCategoryFab)
            fab.visibility = View.VISIBLE
            fab.setOnClickListener {
                showAddCategoryDialog()
            }

            // Start observing categories
            observeCategories(userId)

        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated", e)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeCategories(userId: String?) {
        lifecycleScope.launch {
            try {
                // Just observe the categories - no initialization here
                database.categoryDao().getAllCategories(userId)
                    .catch { e ->
                        Log.e(TAG, "Error loading categories", e)
                        context?.let {
                            Toast.makeText(it, "Error loading categories", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .collect { categories ->
                        Log.d(TAG, "Observed ${categories.size} categories")
                        categoryAdapter.submitList(categories)

                        if (::emptyView.isInitialized) {
                            emptyView.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in observeCategories", e)
                context?.let {
                    Toast.makeText(it, "Error observing categories: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadCategories(userId: String?) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Loading categories for user: $userId")

                // Collect categories from flow
                database.categoryDao().getAllCategories(userId)
                    .catch { e ->
                        Log.e(TAG, "Error loading categories", e)
                        context?.let {
                            Toast.makeText(it, "Error loading categories", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .collect { categories ->
                        Log.d(TAG, "Found ${categories.size} categories")
                        categoryAdapter.submitList(categories)

                        // Update empty state if needed
                        if (::emptyView.isInitialized) {
                            emptyView.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
                        }

                        // If we have no categories, add default ones
                        if (categories.isEmpty()) {
                            CategoryUtils.addDefaultCategories(requireContext(), userId ?: "guest_user")
                            // After adding defaults, refresh the list
                            loadCategories(userId)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadCategories", e)
                context?.let {
                    Toast.makeText(it, "Error loading categories: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        fab.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        fab.visibility = View.GONE
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
                loadCategories(userId)
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
                loadCategories(category.userId)
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