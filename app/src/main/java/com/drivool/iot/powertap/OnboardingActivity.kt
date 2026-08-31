package com.drivool.iot.powertap

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val viewPager: ViewPager2 = findViewById(R.id.viewPager)
        val tabIndicator: TabLayout = findViewById(R.id.tabIndicator)
        val btnNext: Button = findViewById(R.id.btnNext)
        val btnSkip: Button = findViewById(R.id.btnSkip)

        val pages = listOf(
            OnboardingPage("⚡", "Welcome to PowerTap", "The smartest way to manage your EV charging. Connect, monitor, and control with ease."),
            OnboardingPage("🔋", "Full Charge", "Charge your vehicle to its maximum capacity. The session stops automatically when 100% is reached."),
            OnboardingPage("⏲️", "Set Time", "Control your schedule. Set a specific duration in hours, minutes, and seconds for your charging session."),
            OnboardingPage("🔌", "Set Units", "Budget your energy. Define a precise amount of kWh (units) you want to consume."),
            OnboardingPage("💡", "Pro Tip", "For the best experience, avoid pairing PowerTap via your phone's Bluetooth settings. If already paired, please 'Unpair' or 'Forget' it before using the app.")
        )

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = pages.size
            override fun createFragment(position: Int): Fragment {
                return OnboardingFragment.newInstance(pages[position])
            }
        }

        TabLayoutMediator(tabIndicator, viewPager) { _, _ -> }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                btnNext.text = if (position == pages.size - 1) "Get Started" else "Next"
            }
        })

        btnNext.setOnClickListener {
            if (viewPager.currentItem < pages.size - 1) {
                viewPager.currentItem += 1
            } else {
                finishOnboarding()
            }
        }

        btnSkip.setOnClickListener { finishOnboarding() }
    }

    private fun finishOnboarding() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("onboarding_done", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

data class OnboardingPage(val icon: String, val title: String, val description: String)

class OnboardingFragment : Fragment() {
    companion object {
        fun newInstance(page: OnboardingPage) = OnboardingFragment().apply {
            arguments = Bundle().apply {
                putString("icon", page.icon)
                putString("title", page.title)
                putString("description", page.description)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_onboarding_page, container, false)
        view.findViewById<TextView>(R.id.txtIcon).text = arguments?.getString("icon")
        view.findViewById<TextView>(R.id.txtTitle).text = arguments?.getString("title")
        view.findViewById<TextView>(R.id.txtDescription).text = arguments?.getString("description")
        return view
    }
}
