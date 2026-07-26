package com.tunnel.demo.tunneldemo.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class DetailPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> RequestFragment()
            1 -> ResponseFragment()
            2 -> RawFragment()
            else -> RequestFragment()
        }
    }
}
