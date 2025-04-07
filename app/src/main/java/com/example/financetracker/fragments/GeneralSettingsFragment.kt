package com.example.financetracker.fragments

import android.Manifest // <<<*** ADDED Manifest Import ***>>>
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog // Use AppCompat AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.viewModels // Use KTX viewModels delegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.example.financetracker.ManageSendersActivity
import com.example.financetracker.R
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.utils.SenderListManager
import com.example.financetracker.viewmodel.TransactionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.progressindicator.LinearProgressIndicator

class GeneralSettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var auth: com.google.firebase.auth.FirebaseAuth // Use specific import if needed
    private val TAG = "GeneralSettingsFragment"
    private var progressTextView: TextView? = null
    private var linearProgressBar: LinearProgressIndicator? = null

    // Use KTX delegate for ViewModel scoped to this fragment
    private val transactionViewModel: TransactionViewModel by viewModels {
        TransactionViewModel.Factory(
            TransactionDatabase.getDatabase(requireActivity().applicationContext),
            requireActivity().application
        )
    }

    // Action to perform after permission granted
    private var pendingAction: (() -> Unit)? = null

    // Permission Launcher
    private val requestSmsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "READ_SMS permission granted.")
                // Execute and clear the pending action
                pendingAction?.invoke()
                pendingAction = null
            } else {
                Log.w(TAG, "READ_SMS permission denied.")
                Toast.makeText(requireContext(), "SMS Reading permission is required for this feature.", Toast.LENGTH_LONG).show()
                pendingAction = null // Clear if denied
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        auth = com.google.firebase.auth.FirebaseAuth.getInstance()

        // --- Preference Click Listeners ---

        // Manage Senders
        findPreference<Preference>("manage_senders")?.setOnPreferenceClickListener {
            Log.d(TAG, "Manage Senders preference clicked - Launching Activity")
            // Launch the new Activity instead of showing DialogFragment
            val intent = Intent(requireContext(), ManageSendersActivity::class.java)
            startActivity(intent)
            true // Indicate click was handled
        }

        // Scan Past Transactions
        findPreference<Preference>("scan_past_transactions")?.setOnPreferenceClickListener {
            handleActionWithSmsPermission { showScanPeriodChoiceDialog() }
            true
        }

        // Scan Senders (Improve Detection)
        findPreference<Preference>("scan_senders")?.setOnPreferenceClickListener {
            handleActionWithSmsPermission { triggerSenderScan() }
            true
        }

        // Other Preferences...
        findPreference<Preference>("account_info")?.setOnPreferenceClickListener {
            Toast.makeText(requireContext(), "Account info feature coming soon", Toast.LENGTH_SHORT).show()
            true
        }
        findPreference<Preference>("export_data")?.setOnPreferenceClickListener {
            Toast.makeText(requireContext(), "Export feature coming soon", Toast.LENGTH_SHORT).show()
            true
        }
        findPreference<Preference>("import_data")?.setOnPreferenceClickListener {
            Toast.makeText(requireContext(), "Import feature coming soon", Toast.LENGTH_SHORT).show()
            true
        }
        findPreference<Preference>("clear_data")?.setOnPreferenceClickListener {
            showClearDataConfirmation()
            true
        }
        findPreference<Preference>("about")?.setOnPreferenceClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TharunSree/FinanceTracker"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open browser.", Toast.LENGTH_SHORT).show()
            }
            true
        }

        // Setup summaries and initial states
        setupInitialPreferenceValues()
        findPreference<ListPreference>("theme_mode")?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>("currency")?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    // --- Permission Handling ---

    private fun hasSmsPermission(): Boolean {
        // Use requireContext() safely within fragment lifecycle
        return requireContext().checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSmsPermission() {
        // Consider adding shouldShowRequestPermissionRationale logic here
        requestSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
    }

    // Helper to simplify permission check before action
    private fun handleActionWithSmsPermission(action: () -> Unit) {
        if (hasSmsPermission()) {
            action() // Permission granted, execute action
        } else {
            pendingAction = action // Permission needed, store action
            requestSmsPermission() // Request permission
        }
    }

    // --- Scan Trigger Functions ---

    private fun showScanPeriodChoiceDialog() {
        val periods = arrayOf("Last Month", "All Time")
        AlertDialog.Builder(requireContext())
            .setTitle("Scan SMS For Transactions")
            .setItems(periods) { dialog, which ->
                val selectedPeriod = when (which) {
                    0 -> TransactionViewModel.ScanPeriod.LAST_MONTH
                    1 -> TransactionViewModel.ScanPeriod.ALL_TIME
                    else -> TransactionViewModel.ScanPeriod.LAST_MONTH // Default
                }
                dialog.dismiss()
                triggerTransactionScan(selectedPeriod) // Trigger scan with selection
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerTransactionScan(period: TransactionViewModel.ScanPeriod) {
        val periodText = if (period == TransactionViewModel.ScanPeriod.LAST_MONTH) "last month" else "all time"
        Toast.makeText(requireContext(), "Starting scan for $periodText...", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Triggering transaction scan for period: $period")
        showProgressDialog("Starting scan...") // Initial message

        // Observe BOTH status and progress values
        observeScanStatus()
        observeScanProgress() // <<< ADDED: Observe numeric progress

        transactionViewModel.scanPastSmsForTransactions(requireContext().applicationContext, period)
    }

    private fun triggerSenderScan() {
        Toast.makeText(requireContext(), "Scanning SMS for new senders...", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Triggering sender scan")
        showProgressDialog("Scanning Senders...")

        lifecycleScope.launch { // Launch coroutine for background task
            val potentialSenders = try {
                // Make sure SenderListManager uses applicationContext if needed long term
                SenderListManager.scanForPotentialSenders(requireContext().applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error during sender scan", e)
                withContext(Dispatchers.Main) { // Show error on main thread
                    hideProgressDialog()
                    Toast.makeText(requireContext(), "Error scanning senders: ${e.message}", Toast.LENGTH_LONG).show()
                }
                emptyList<String>() // Return empty list on error
            }


            // Update UI on main thread
            withContext(Dispatchers.Main) {
                hideProgressDialog()
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

    // --- Dialogs ---

    private var progressDialog: AlertDialog? = null

    private fun showProgressDialog(message: String) {
        if (progressDialog == null) {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_progress, null)
            progressTextView = dialogView.findViewById(R.id.progressText)
            linearProgressBar = dialogView.findViewById(R.id.linearProgressBar) // <<< Find LinearProgressIndicator
            progressDialog = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setCancelable(false)
                .create()
        }
        progressTextView?.text = message // Update text message
        // Reset progress bar visually when dialog is first shown or message changes significantly
        linearProgressBar?.progress = 0
        linearProgressBar?.visibility = View.INVISIBLE // Hide bar until first progress value arrives

        if (progressDialog?.isShowing == false) {
            progressDialog?.show()
        }
    }

    private fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
        progressTextView = null
        linearProgressBar = null // <<< Clear ProgressBar reference
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
                    .toSet() // Convert to Set for efficient saving
                if (approvedSenders.isNotEmpty()) {
                    SenderListManager.saveApprovedSenders(requireContext().applicationContext, approvedSenders)
                    Toast.makeText(requireContext(), "${approvedSenders.size} sender(s) added.", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "User approved senders: $approvedSenders")
                } else {
                    Toast.makeText(requireContext(), "No senders selected.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearDataConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear All Data")
            .setMessage("Are you sure you want to delete all your financial data? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                Log.d(TAG, "Clearing all transactions...")
                showProgressDialog("Clearing Data...")
                // Use ViewModel to clear data
                transactionViewModel.clearTransactions() // This should handle Room and notify observers
                // Optionally observe a completion state from ViewModel if needed
                hideProgressDialog() // Hide progress after initiating clear
                Toast.makeText(requireContext(), "All local transaction data cleared.", Toast.LENGTH_SHORT).show()
                // Note: This currently doesn't clear Firestore data. Add that logic if required.
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    // --- ViewModel Observation ---
    private fun observeScanStatus() {
        // Observe only the final status messages or errors
        transactionViewModel.scanStatus.removeObservers(viewLifecycleOwner) // Ensure no duplicates
        transactionViewModel.scanStatus.observe(viewLifecycleOwner) { status ->
            if (status != null) {
                Log.d(TAG, "Scan Status Update: $status")
                if (status.startsWith("Scan Complete") || status.startsWith("Error") || status == "Permission Error" || status.startsWith("Scan Finished") || status == "No messages found") {
                    hideProgressDialog() // Hide on completion or error
                    Toast.makeText(requireContext(), status, Toast.LENGTH_LONG).show()
                    transactionViewModel.scanStatus.value = null // Reset status
                } else {
                    // Update the text part of the dialog if it's still showing
                    progressTextView?.text = status
                }
            }
        }
    }


    private fun observeScanProgress() {
        transactionViewModel.scanProgressValues.removeObservers(viewLifecycleOwner) // Ensure no duplicates
        transactionViewModel.scanProgressValues.observe(viewLifecycleOwner) { progressPair ->
            if (progressPair != null && progressDialog?.isShowing == true) {
                val current = progressPair.first
                val total = progressPair.second
                Log.d(TAG, "Scan Progress Update: $current / $total")

                linearProgressBar?.let { bar ->
                    if (total > 0) {
                        bar.max = total
                        // Use animated update
                        bar.setProgressCompat(current, true)
                        bar.visibility = View.VISIBLE // Show bar once we have data
                    } else {
                        // Handle case with 0 total messages (hide bar)
                        bar.visibility = View.INVISIBLE
                        bar.progress = 0
                        bar.max = 100 // Reset max
                    }
                }
                // Update text view as well (optional redundancy, or refine status message)
                progressTextView?.text = "Scanning: $current / $total"

            } else if (progressPair == null) {
                // Reset progress bar if LiveData emits null (e.g., at scan start)
                linearProgressBar?.visibility = View.INVISIBLE
                linearProgressBar?.progress = 0
                linearProgressBar?.max = 100
            }
        }
    }
    // --- Preference Initialization and Listener ---

    private fun setupInitialPreferenceValues() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        updateAccountSummary(auth.currentUser)
        updateSyncPreference(auth.currentUser, prefs.getBoolean("sync_data", true))
        updateSmsNotificationState(prefs.getBoolean("notifications_enabled", true))
        // Set initial summary for ListPreferences if needed (handled by Provider now)
    }

    private fun updateAccountSummary(currentUser: com.google.firebase.auth.FirebaseUser?) {
        val accountInfoPref = findPreference<Preference>("account_info")
        accountInfoPref?.summary = currentUser?.email ?: "You're using the app as a guest"
    }

    private fun updateSyncPreference(currentUser: com.google.firebase.auth.FirebaseUser?, currentValue: Boolean) {
        val syncPref = findPreference<SwitchPreferenceCompat>("sync_data")
        syncPref?.isEnabled = currentUser != null
        if (currentUser == null) {
            syncPref?.isChecked = false // Force disable if not logged in
            syncPref?.summary = "Login required to enable sync"
        } else {
            syncPref?.isChecked = currentValue // Reflect stored value if logged in
            syncPref?.summary = if(currentValue) "Automatically back up your financial data" else "Sync is disabled"
        }
    }

    private fun updateSmsNotificationState(notificationsEnabled: Boolean) {
        val smsPref = findPreference<SwitchPreferenceCompat>("sms_notification")
        smsPref?.isEnabled = notificationsEnabled
        if (!notificationsEnabled) {
            smsPref?.isChecked = false // Disable SMS if main notifications are off
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        // Refresh UI state in case login status changed while paused
        setupInitialPreferenceValues()
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            "theme_mode" -> {
                val themeValue = sharedPreferences.getString(key, "system")
                Log.d(TAG, "Theme changed to: $themeValue")
                val mode = when (themeValue) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode) // Apply theme change
            }
            "notifications_enabled" -> {
                val enabled = sharedPreferences.getBoolean(key, true)
                Log.d(TAG, "Notifications enabled changed to: $enabled")
                updateSmsNotificationState(enabled)
            }
            "sync_data" -> {
                val syncEnabled = sharedPreferences.getBoolean(key, true)
                Log.d(TAG, "Sync data changed to: $syncEnabled")
                updateSyncPreference(auth.currentUser, syncEnabled) // Re-validate with login status
                if (syncEnabled && auth.currentUser == null) {
                    Toast.makeText(requireContext(), "Please log in to enable sync", Toast.LENGTH_SHORT).show()
                    // Switch might be reset by updateSyncPreference, but ensure consistency
                    (findPreference<SwitchPreferenceCompat>(key))?.isChecked = false
                }
                // TODO: Add logic to actually start/stop sync service or mechanism
            }
            "currency", "sms_notification" -> {
                Log.d(TAG, "Preference changed: $key = ${sharedPreferences.all[key]}")
                // Update summaries or trigger actions if needed
            }
        }
    }
}