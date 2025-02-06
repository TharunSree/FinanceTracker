package com.example.financetracker

import android.Manifest
import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.database.TransactionDatabase
import com.example.financetracker.database.TransactionPattern
import com.example.financetracker.database.entity.Transaction
import com.example.financetracker.ui.dialogs.TransactionDetailsDialog
import com.example.financetracker.utils.MessageExtractor
import com.example.financetracker.viewmodel.TransactionViewModel
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity(),
    TransactionAdapter.OnTransactionInteractionListener,
    TransactionDetailsDialog.TransactionDetailsListener {

    private lateinit var transactionAdapter: TransactionAdapter
    private lateinit var smsBroadcastReceiver: SmsBroadcastReceiver
    private lateinit var messageExtractor: MessageExtractor
    private var currentTransaction: Transaction? = null
    private var currentMessageBody: String? = null

    private val transactionViewModel: TransactionViewModel by viewModels {
        val database = TransactionDatabase.getDatabase(this)
        TransactionViewModel.Factory(database)
    }

    companion object {
        private const val SMS_PERMISSION_CODE = 123
        private const val NOTIFICATION_PERMISSION_CODE = 124

        const val TRANSACTION_CHANNEL_ID = "TRANSACTION_CHANNEL"
        const val DETAILS_REQUIRED_CHANNEL_ID = "DETAILS_REQUIRED_CHANNEL"
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                registerSmsReceiver()
                setupNotificationChannel()
            } else {
                val deniedPermissions = permissions.entries.filter { !it.value }
                showPermissionsDeniedMessage(deniedPermissions.map { it.key })
            }
        }

    private val addTransactionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.let { data ->
                    try {
                        val name = data.getStringExtra("name")
                            ?: throw IllegalArgumentException("Name is required")
                        val amount = data.getDoubleExtra("amount", 0.0)
                        val date = data.getStringExtra("date")
                            ?: throw IllegalArgumentException("Date is required")
                        val category = data.getStringExtra("category") ?: "Uncategorized"

                        val transaction = Transaction(
                            id = 0,
                            name = name,
                            amount = amount,
                            date = parseDateToLong(date),
                            category = category
                        )

                        transactionViewModel.addTransaction(transaction)
                    } catch (e: Exception) {
                        Toast.makeText(
                            this,
                            "Error adding transaction: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupPermissions()
        setupNotificationChannel()
        requestNotificationPermission()
        setupRecyclerView()
        setupAddTransactionButton()
        observeTransactions()
        setupObservers()

        // Handle intent extras for notifications
        handleIntentExtras(intent)
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val transactionChannel = NotificationChannel(
                "TRANSACTION_CHANNEL",
                "Transactions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Financial transaction notifications"
                enableLights(true)
                lightColor = Color.GREEN
                enableVibration(true)
            }

            val detailsChannel = NotificationChannel(
                "DETAILS_REQUIRED_CHANNEL",
                "Transaction Details Required",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for missing transaction details"
                enableLights(true)
                lightColor = Color.YELLOW
                enableVibration(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(transactionChannel)
            notificationManager.createNotificationChannel(detailsChannel)
        }
    }

    private fun showPermissionsDeniedMessage(deniedPermissions: List<String>) {
        val message = buildString {
            append("The following permissions are required:\n\n")
            deniedPermissions.forEach { permission ->
                append("• ${getPermissionDescription(permission)}\n")
            }
            append("\nPlease grant these permissions in Settings to use all features.")
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun openAppSettings() {
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
            startActivity(this)
        }
    }

    private fun handleIntentExtras(intent: Intent) {
        if (intent.getBooleanExtra("SHOW_TRANSACTION_DIALOG", false)) {
            val message = intent.getStringExtra("TRANSACTION_MESSAGE")
            val amount = intent.getDoubleExtra("TRANSACTION_AMOUNT", 0.0)
            val date = intent.getLongExtra("TRANSACTION_DATE", System.currentTimeMillis())

            // Create a temporary transaction for the dialog
            val transaction = Transaction(
                id = 0,
                name = "Unknown Merchant",
                amount = amount,
                date = date,
                category = "Uncategorized"
            )

            message?.let {
                showTransactionDetailsDialog(transaction, it)
            }
        }
    }

    private fun getPermissionDescription(permission: String): String {
        return when (permission) {
            Manifest.permission.RECEIVE_SMS -> "SMS Reception (for transaction tracking)"
            Manifest.permission.READ_SMS -> "SMS Reading (for transaction details)"
            POST_NOTIFICATIONS -> "Notifications (for transaction alerts)"
            else -> permission
        }
    }

    private fun formatDate(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(timestamp)
    }

    private fun setupPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )

        if (!permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            requestPermissionsLauncher.launch(permissions)
        } else {
            registerSmsReceiver()
        }
    }

    private fun registerSmsReceiver() {
        smsBroadcastReceiver = SmsBroadcastReceiver()
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        registerReceiver(smsBroadcastReceiver, filter)
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.transactionRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        transactionAdapter = TransactionAdapter(emptyList(), this)
        recyclerView.adapter = transactionAdapter

        setupSwipeToDelete(recyclerView)
    }

    private fun setupAddTransactionButton() {
        findViewById<Button>(R.id.addTransactionButton).setOnClickListener {
            val intent = Intent(this, AddTransactionActivity::class.java)
            addTransactionLauncher.launch(intent)
        }
    }

    private fun observeTransactions() {
        transactionViewModel.transactions.observe(this) { transactions ->
            transactionAdapter.updateData(transactions)
        }
    }

    private fun setupSwipeToDelete(recyclerView: RecyclerView) {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT
        ) {
            private val deleteIcon =
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_delete)!!
            private val background = ColorDrawable(Color.RED)
            private val intrinsicWidth = deleteIcon.intrinsicWidth
            private val intrinsicHeight = deleteIcon.intrinsicHeight
            private val paint = Paint().apply { color = Color.WHITE }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val transaction = transactionAdapter.getTransactionAt(viewHolder.adapterPosition)
                transactionViewModel.deleteTransaction(transaction)
                Toast.makeText(this@MainActivity, "Transaction deleted", Toast.LENGTH_SHORT).show()
            }

            override fun onChildDraw(
                canvas: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val itemHeight = itemView.bottom - itemView.top

                // Draw the red delete background
                background.setBounds(
                    itemView.right + dX.toInt(),
                    itemView.top,
                    itemView.right,
                    itemView.bottom
                )
                background.draw(canvas)

                // Calculate position of delete icon
                val iconTop = itemView.top + (itemHeight - intrinsicHeight) / 2
                val iconMargin = (itemHeight - intrinsicHeight) / 2
                val iconLeft = itemView.right - iconMargin - intrinsicWidth
                val iconRight = itemView.right - iconMargin
                val iconBottom = iconTop + intrinsicHeight

                // Draw the delete icon
                deleteIcon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                deleteIcon.draw(canvas)

                super.onChildDraw(
                    canvas, recyclerView, viewHolder,
                    dX, dY, actionState, isCurrentlyActive
                )
            }
        })

        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.filter_today -> {
                transactionViewModel.loadTodayTransactions()
                true
            }

            R.id.filter_week -> {
                transactionViewModel.loadWeekTransactions()
                true
            }

            R.id.filter_month -> {
                transactionViewModel.loadMonthTransactions()
                true
            }

            R.id.filter_all -> {
                transactionViewModel.loadAllTransactions()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentExtras(intent)
    }

    private fun setupObservers() {
        transactionViewModel.transactions.observe(this) { transactions ->
            transactionAdapter.updateData(transactions)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                transactionViewModel.filteredTransactions.collect { filteredTransactions ->
                    transactionAdapter.updateData(filteredTransactions)
                    updateFilterStatus(filteredTransactions.size)
                }
            }
        }
    }

    private fun updateFilterStatus(count: Int) {
        supportActionBar?.subtitle = "Showing $count transactions"
    }

    private fun filterTransactionsByDate(period: FilterPeriod) {
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        when (period) {
            FilterPeriod.TODAY -> Unit // Start time is already set to today's beginning
            FilterPeriod.WEEK -> calendar.add(Calendar.DAY_OF_YEAR, -7)
            FilterPeriod.MONTH -> calendar.add(Calendar.MONTH, -1)
        }

        val startTime = calendar.timeInMillis
        transactionViewModel.loadTransactionsByDateRange(startTime, endTime)
    }

    private fun parseDateToLong(date: String): Long {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            dateFormat.parse(date)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    override fun onEditTransaction(transaction: Transaction) {
        val editDialog = EditTransactionDialogFragment(transaction) { updatedTransaction ->
            transactionViewModel.updateTransaction(updatedTransaction)
        }
        editDialog.show(supportFragmentManager, "EditTransactionDialog")
    }

    override fun onDeleteTransaction(transaction: Transaction) {
        transactionViewModel.deleteTransaction(transaction)
        Toast.makeText(this, "Transaction deleted", Toast.LENGTH_SHORT).show()
    }

    override fun onLongPressTransaction(transaction: Transaction) {
        val view = findViewById<RecyclerView>(R.id.transactionRecyclerView)
            .findViewHolderForAdapterPosition(
                transactionAdapter.getTransactions().indexOf(transaction)
            )?.itemView ?: return

        PopupMenu(this, view).apply {
            menuInflater.inflate(R.menu.transaction_options_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_edit -> {
                        onEditTransaction(transaction)
                        true
                    }

                    R.id.action_delete -> {
                        onDeleteTransaction(transaction)
                        true
                    }

                    else -> false
                }
            }
            show()
        }
    }

    // New methods for TransactionDetailsDialog
    fun showTransactionDetailsDialog(transaction: Transaction, messageBody: String) {
        currentTransaction = transaction
        currentMessageBody = messageBody

        val dialog = TransactionDetailsDialog.newInstance(
            originalMerchant = transaction.name,
            messageBody = messageBody
        )
        dialog.show(supportFragmentManager, "TransactionDetailsDialog")
    }

    override fun onDetailsEntered(merchant: String, category: String, saveAsPattern: Boolean) {
        currentTransaction?.let { transaction ->
            lifecycleScope.launch {
                transaction.name = merchant
                transaction.category = category
                transactionViewModel.updateTransaction(transaction)
            }
        }

        if (saveAsPattern) {
            currentMessageBody?.let { messageBody ->
                saveTransactionPattern(messageBody, merchant, category)
            }
        }
    }

    private fun saveTransactionPattern(messageBody: String, merchant: String, category: String) {
        val pattern = TransactionPattern(messageBody, merchant, category)
        val prefs = getSharedPreferences("TransactionPatterns", Context.MODE_PRIVATE)
        val patternsJson = prefs.getStringSet("patterns", mutableSetOf()) ?: mutableSetOf()
        val newPatternsJson = mutableSetOf<String>().apply {
            addAll(patternsJson)
            add(Gson().toJson(pattern))
        }
        prefs.edit().putStringSet("patterns", newPatternsJson).apply()
    }



    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(smsBroadcastReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    enum class FilterPeriod {
        TODAY, WEEK, MONTH
    }
}