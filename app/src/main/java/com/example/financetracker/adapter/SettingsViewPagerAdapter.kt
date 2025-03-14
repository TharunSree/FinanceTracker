package com.example.financetracker.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.financetracker.fragments.CategoriesFragment
import com.example.financetracker.fragments.GeneralSettingsFragment

class SettingsViewPagerAdapter(
    fragmentActivity: FragmentActivity
) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> GeneralSettingsFragment()
            1 -> CategoriesFragment()
            else -> throw IllegalArgumentException("Invalid position $position")
        }
    }
}