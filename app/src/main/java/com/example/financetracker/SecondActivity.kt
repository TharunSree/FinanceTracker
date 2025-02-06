package com.example.financetracker

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class SecondActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second)

        // Example: Set up a button to return a result
        val returnResultButton: Button = findViewById(R.id.return_result_button)
        returnResultButton.setOnClickListener {
            // Create the result Intent
            val resultIntent = Intent()
            resultIntent.putExtra("result_key", "Sample Result Data")
            setResult(RESULT_OK, resultIntent)
            finish() // Close this activity and return the result
        }
    }
}
