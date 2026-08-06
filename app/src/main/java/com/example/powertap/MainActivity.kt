package com.drivool.iot.powertap

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!AuthManager.isLoggedIn) {
            startActivity(android.content.Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        val onboardingDone = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("onboarding_done", false)
        if (!onboardingDone) {
            startActivity(android.content.Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        GatewayManager.init(this)
        PowerTapManager.init()

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
                R.id.nav_history -> replaceFragment(HistoryFragment(), "Charging History")
                R.id.nav_scan -> startActivity(android.content.Intent(this, DeviceScanActivity::class.java))
                R.id.nav_ble_test -> startActivity(android.content.Intent(this, BleTestActivity::class.java))
                R.id.nav_mqtt_gateway -> startActivity(android.content.Intent(this, MqttActivity::class.java))
                R.id.nav_power_data -> startActivity(android.content.Intent(this, PowerDataActivity::class.java))
                R.id.nav_logs -> startActivity(android.content.Intent(this, LogActivity::class.java))
                R.id.nav_settings -> startActivity(android.content.Intent(this, ProfileActivity::class.java))
                R.id.nav_logout -> {
                    AuthManager.logout()
                    val credentialManager = CredentialManager.create(this)
                    lifecycleScope.launch {
                        try {
                            credentialManager.clearCredentialState(ClearCredentialStateRequest())
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                    startActivity(android.content.Intent(this, AuthActivity::class.java))
                    finish()
                }
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
