package com.example.financetracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class CategoriesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var database: TransactionDatabase
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var fab: FloatingActionButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_categories, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = TransactionDatabase.getDatabase(requireContext())

        recyclerView = view.findViewById(R.id.categoriesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val userId = auth.currentUser?.uid

        categoryAdapter = CategoryAdapter(
            onEdit = { category -> showEditCategoryDialog(category) },
            onDelete = { category -> deleteCategory(category) }
        )

        recyclerView.adapter = categoryAdapter

        // Get the fab from the activity
        fab = requireActivity().findViewById(R.id.addCategoryFab)

        fab.setOnClickListener {
            showAddCategoryDialog()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            database.categoryDao().getAllCategories(userId).collect { categories ->
                categoryAdapter.submitList(categories)
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
        val userId = auth.currentUser?.uid

        viewLifecycleOwner.lifecycleScope.launch {
            val category = Category(
                name = name,
                userId = userId,
                isDefault = false
            )

            database.categoryDao().insertCategory(category)

            // Also save to Firestore if signed in
            userId?.let {
                firestore.collection("users")
                    .document(it)
                    .collection("categories")
                    .add(mapOf(
                        "name" to name,
                        "isDefault" to false
                    ))
            }

            Toast.makeText(requireContext(), "Category added", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCategory(category: Category, newName: String) {
        val userId = auth.currentUser?.uid

        viewLifecycleOwner.lifecycleScope.launch {
            val updatedCategory = category.copy(name = newName)
            database.categoryDao().updateCategory(updatedCategory)

            // Update in Firestore if signed in
            userId?.let {
                firestore.collection("users")
                    .document(it)
                    .collection("categories")
                    .whereEqualTo("name", category.name)
                    .get()
                    .addOnSuccessListener { querySnapshot ->
                        for (document in querySnapshot.documents) {
                            document.reference.update("name", newName)
                        }
                    }
            }

            Toast.makeText(requireContext(), "Category updated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteCategory(category: Category) {
        // Don't allow deletion of default categories
        if (category.isDefault) {
            Toast.makeText(requireContext(), "Cannot delete default categories", Toast.LENGTH_SHORT).show()
            return
        }

        val userId = auth.currentUser?.uid

        AlertDialog.Builder(requireContext())
            .setTitle("Delete Category")
            .setMessage("Are you sure you want to delete this category?")
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    database.categoryDao().deleteCategory(category)

                    // Delete from Firestore if signed in
                    userId?.let {
                        firestore.collection("users")
                            .document(it)
                            .collection("categories")
                            .whereEqualTo("name", category.name)
                            .get()
                            .addOnSuccessListener { querySnapshot ->
                                for (document in querySnapshot.documents) {
                                    document.reference.delete()
                                }
                            }
                    }

                    Toast.makeText(requireContext(), "Category deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}