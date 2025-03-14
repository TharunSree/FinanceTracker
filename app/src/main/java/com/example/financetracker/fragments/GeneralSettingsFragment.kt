package com.example.financetracker.fragments

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.example.financetracker.R
import com.google.firebase.auth.FirebaseAuth

class GeneralSettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var auth: FirebaseAuth

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        auth = FirebaseAuth.getInstance()

        // Handle account info preference
        findPreference<Preference>("account_info")?.setOnPreferenceClickListener {
            // You could start a ProfileActivity here
            Toast.makeText(requireContext(), "Account info feature coming soon", Toast.LENGTH_SHORT).show()
            true
        }

        // Handle export data preference
        findPreference<Preference>("export_data")?.setOnPreferenceClickListener {
            Toast.makeText(requireContext(), "Export feature coming soon", Toast.LENGTH_SHORT).show()
            true
        }

        // Handle import data preference
        findPreference<Preference>("import_data")?.setOnPreferenceClickListener {
            Toast.makeText(requireContext(), "Import feature coming soon", Toast.LENGTH_SHORT).show()
            true
        }

        // Handle clear data preference
        findPreference<Preference>("clear_data")?.setOnPreferenceClickListener {
            showClearDataConfirmation()
            true
        }

        // Handle about preference
        findPreference<Preference>("about")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TharunSree/FinanceTracker"))
            startActivity(intent)
            true
        }

        // Update theme based on saved preference
        val themePreference = findPreference<ListPreference>("theme_mode")
        themePreference?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        // Initialize preferences based on current state
        setupInitialPreferenceValues()
    }

    private fun setupInitialPreferenceValues() {
        // Update account info based on auth state
        val currentUser = auth.currentUser
        val accountInfoPref = findPreference<Preference>("account_info")
        if (currentUser != null) {
            accountInfoPref?.summary = currentUser.email
        } else {
            accountInfoPref?.summary = "You're using the app as a guest"
        }

        // Disable sync if not logged in
        val syncPref = findPreference<SwitchPreferenceCompat>("sync_data")
        syncPref?.isEnabled = currentUser != null
        if (currentUser == null) {
            syncPref?.isChecked = false
            syncPref?.summary = "Login required to enable sync"
        }
    }

    private fun showClearDataConfirmation() {
        // Show a confirmation dialog for clearing data
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear All Data")
            .setMessage("Are you sure you want to delete all your financial data? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                Toast.makeText(requireContext(), "Data cleared (implementation pending)", Toast.LENGTH_SHORT).show()
                // TODO: Implement actual data clearing logic
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            "theme_mode" -> {
                val themeValue = sharedPreferences.getString(key, "system")
                when (themeValue) {
                    "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
            }
            "notifications_enabled" -> {
                val enabled = sharedPreferences.getBoolean(key, true)
                // Toggle notification functionality here
                val smsPreference = findPreference<SwitchPreferenceCompat>("sms_notification")
                smsPreference?.isEnabled = enabled
                if (!enabled) {
                    smsPreference?.isChecked = false
                }
            }
            "currency" -> {
                val currencyCode = sharedPreferences.getString(key, "INR")
                // Update currency throughout the app
                // You might want to use a shared ViewModel or another method
                // to communicate this change to other parts of the app
            }
            "sync_data" -> {
                val syncEnabled = sharedPreferences.getBoolean(key, true)
                if (syncEnabled && auth.currentUser == null) {
                    // User tried to enable sync but is not logged in
                    Toast.makeText(requireContext(), "Please log in to enable sync", Toast.LENGTH_SHORT).show()
                    val syncPref = findPreference<SwitchPreferenceCompat>(key)
                    syncPref?.isChecked = false
                }
            }
        }
    }
}