package com.example.financetracker.fragments

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.example.financetracker.R
import com.google.firebase.auth.FirebaseAuth
import android.Manifest
import androidx.lifecycle.lifecycleScope
import com.example.financetracker.utils.SenderListManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AlertDialog

class GeneralSettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var auth: FirebaseAuth
    private val TAG = "GeneralSettingsFragment"

    private val requestSmsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "READ_SMS permission granted.")
                // You might need to store which action was pending and trigger it now
                // For simplicity, just inform the user to tap again.
                Toast.makeText(
                    requireContext(),
                    "Permission granted. Please tap the option again.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Log.w(TAG, "READ_SMS permission denied.")
                Toast.makeText(
                    requireContext(),
                    "SMS Reading permission is required for this feature.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        auth = FirebaseAuth.getInstance()

        // Handle account info preference
        findPreference<Preference>("account_info")?.setOnPreferenceClickListener {
            // You could start a ProfileActivity here
            Toast.makeText(requireContext(), "Account info feature coming soon", Toast.LENGTH_SHORT)
                .show()
            true
        }

        findPreference<Preference>("scan_past_transactions")?.setOnPreferenceClickListener {
            if (hasSmsPermission()) {
                triggerTransactionScan()
            } else {
                requestSmsPermission()
            }
            true // Indicate the click was handled
        }

        findPreference<Preference>("scan_senders")?.setOnPreferenceClickListener {
            if (hasSmsPermission()) {
                triggerSenderScan()
            } else {
                requestSmsPermission()
            }
            true // Indicate the click was handled
        }

        // Handle export data preference
        findPreference<Preference>("export_data")?.setOnPreferenceClickListener {
            Toast.makeText(requireContext(), "Export feature coming soon", Toast.LENGTH_SHORT)
                .show()
            true
        }

        // Handle import data preference
        findPreference<Preference>("import_data")?.setOnPreferenceClickListener {
            Toast.makeText(requireContext(), "Import feature coming soon", Toast.LENGTH_SHORT)
                .show()
            true
        }

        // Handle clear data preference
        findPreference<Preference>("clear_data")?.setOnPreferenceClickListener {
            showClearDataConfirmation()
            true
        }

        // Handle about preference
        findPreference<Preference>("about")?.setOnPreferenceClickListener {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/TharunSree/FinanceTracker")
            )
            startActivity(intent)
            true
        }

        // Update theme based on saved preference
        val themePreference = findPreference<ListPreference>("theme_mode")
        themePreference?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        // Initialize preferences based on current state
        setupInitialPreferenceValues()
    }

    private fun hasSmsPermission(): Boolean {
        return requireActivity().checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSmsPermission() {
        // Explain why you need the permission (optional but recommended)
        // Show rationale dialog if needed...
        requestSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
    }

    private fun triggerTransactionScan() {
        Toast.makeText(requireContext(), "Starting scan for past transactions...", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Triggering transaction scan")
        // TODO: Call the actual scanning logic here
        // Example: viewModel.scanPastSmsForTransactions(requireContext(), ScanPeriod.LAST_MONTH)
        // This function needs to be implemented (e.g., in TransactionViewModel)
        // It should query SMS, process them in the background, check duplicates, and save.
        showProgressDialog("Scanning Past Transactions...") // Show progress
        lifecycleScope.launch {
            // Simulate background work
            kotlinx.coroutines.delay(3000) // Replace with actual SMS query/processing
            withContext(Dispatchers.Main) {
                hideProgressDialog() // Hide progress
                Toast.makeText(requireContext(), "Past transaction scan complete (Simulated).", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun triggerSenderScan() {
        Toast.makeText(requireContext(), "Scanning SMS for new senders...", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Triggering sender scan")
        showProgressDialog("Scanning Senders...") // Show progress

        lifecycleScope.launch {
            val potentialSenders = SenderListManager.scanForPotentialSenders(requireContext())

            withContext(Dispatchers.Main) {
                hideProgressDialog() // Hide progress
                if (potentialSenders.isNotEmpty()) {
                    Log.d(TAG, "Found potential senders: $potentialSenders")
                    showSenderConfirmationDialog(potentialSenders)
                } else {
                    Log.d(TAG, "No new potential senders found.")
                    Toast.makeText(requireContext(), "No new potential financial senders found.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var progressDialog: AlertDialog? = null

    private fun showProgressDialog(message: String) {
        if (progressDialog == null) {
            progressDialog = AlertDialog.Builder(requireContext())
                .setMessage(message)
                .setCancelable(false)
                .create()
        }
        progressDialog?.show()
    }

    private fun showSenderConfirmationDialog(potentialSenders: List<String>) {
        val selectedItems = BooleanArray(potentialSenders.size) { true } // Pre-select all

        AlertDialog.Builder(requireContext())
            .setTitle("Add Financial Senders?")
            .setMultiChoiceItems(potentialSenders.toTypedArray(), selectedItems) { _, which, isChecked ->
                selectedItems[which] = isChecked
            }
            .setPositiveButton("Add Selected") { _, _ ->
                val approvedSenders = potentialSenders
                    .filterIndexed { index, _ -> selectedItems[index] }
                    .toSet()
                if (approvedSenders.isNotEmpty()) {
                    SenderListManager.saveApprovedSenders(requireContext(), approvedSenders)
                    Toast.makeText(requireContext(), "${approvedSenders.size} senders added.", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "User approved senders: $approvedSenders")
                } else {
                    Toast.makeText(requireContext(), "No senders selected.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null // Allow creation next time
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
                Toast.makeText(
                    requireContext(),
                    "Data cleared (implementation pending)",
                    Toast.LENGTH_SHORT
                ).show()
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
                    Toast.makeText(
                        requireContext(),
                        "Please log in to enable sync",
                        Toast.LENGTH_SHORT
                    ).show()
                    val syncPref = findPreference<SwitchPreferenceCompat>(key)
                    syncPref?.isChecked = false
                }
            }
        }
    }
}