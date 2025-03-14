// SettingsActivity.kt
package com.example.financetracker

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.adapter.CategoryAdapter
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.entity.Category
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var database: TransactionDatabase
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        database = TransactionDatabase.getDatabase(this)

        recyclerView = findViewById(R.id.categoriesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val userId = auth.currentUser?.uid

        categoryAdapter = CategoryAdapter(
            onEdit = { category -> showEditCategoryDialog(category) },
            onDelete = { category -> deleteCategory(category) }
        )

        recyclerView.adapter = categoryAdapter

        val fab = findViewById<FloatingActionButton>(R.id.addCategoryFab)
        fab.setOnClickListener {
            showAddCategoryDialog()
        }

        lifecycleScope.launch {
            database.categoryDao().getAllCategories(userId).collect { categories ->
                categoryAdapter.submitList(categories)
            }
        }
    }

    private fun showAddCategoryDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_category, null)
        val nameEditText = dialogView.findViewById<EditText>(R.id.categoryNameEditText)

        AlertDialog.Builder(this)
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
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_category, null)
        val nameEditText = dialogView.findViewById<EditText>(R.id.categoryNameEditText)
        nameEditText.setText(category.name)

        AlertDialog.Builder(this)
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

        lifecycleScope.launch {
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

            Toast.makeText(this@SettingsActivity, "Category added", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCategory(category: Category, newName: String) {
        val userId = auth.currentUser?.uid

        lifecycleScope.launch {
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

            Toast.makeText(this@SettingsActivity, "Category updated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteCategory(category: Category) {
        // Don't allow deletion of default categories
        if (category.isDefault) {
            Toast.makeText(this, "Cannot delete default categories", Toast.LENGTH_SHORT).show()
            return
        }

        val userId = auth.currentUser?.uid

        AlertDialog.Builder(this)
            .setTitle("Delete Category")
            .setMessage("Are you sure you want to delete this category?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
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

                    Toast.makeText(this@SettingsActivity, "Category deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}