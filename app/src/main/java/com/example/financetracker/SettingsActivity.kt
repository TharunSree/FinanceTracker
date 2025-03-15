package com.example.financetracker

import android.os.Bundle
import androidx.viewpager2.widget.ViewPager2
import com.example.financetracker.adapter.SettingsViewPagerAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth

class SettingsActivity : BaseActivity() {

    override fun getLayoutResourceId(): Int = R.layout.activity_settings

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var adapter: SettingsViewPagerAdapter
    private lateinit var addCategoryFab: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up the ViewPager and TabLayout
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        addCategoryFab = findViewById(R.id.addCategoryFab)

        adapter = SettingsViewPagerAdapter(this)
        viewPager.adapter = adapter

        // Set up TabLayoutMediator to connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_general)
                1 -> getString(R.string.tab_categories)
                else -> null
            }
        }.attach()

        // Show/hide FAB based on selected tab
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                addCategoryFab.visibility = if (position == 1) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Set title in action bar
        supportActionBar?.title = "Settings"
    }
}