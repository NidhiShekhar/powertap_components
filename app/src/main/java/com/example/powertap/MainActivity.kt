package com.example.powertap

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        val navView: NavigationView = findViewById(R.id.nav_view)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> replaceFragment(HomeFragment(), "Home")
                R.id.nav_lcd -> replaceFragment(LCDFragment(), "LCD Demo")
                R.id.nav_config -> replaceFragment(ConfigFragment(), "Config Panel")
                R.id.nav_slider -> replaceFragment(SliderFragment(), "Slider Button")
                R.id.nav_energy -> replaceFragment(PlaceholderFragment("Energy Rate"), "Energy Rate")
                R.id.nav_history -> replaceFragment(PlaceholderFragment("Charging History"), "Charging History")
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Default fragment
        if (savedInstanceState == null) {
            replaceFragment(HomeFragment(), "Home")
            navView.setCheckedItem(R.id.nav_home)
        }
    }

    private fun replaceFragment(fragment: Fragment, title: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        findViewById<MaterialToolbar>(R.id.toolbar).title = title
    }
}
