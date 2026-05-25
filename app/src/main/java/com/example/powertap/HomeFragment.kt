package com.example.powertap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout

import java.util.Locale

class HomeFragment : Fragment() {

    private var time = 60
    private var units = 10

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        val lcdView: TwoLineLCDView = view.findViewById(R.id.lcd_view)
        val sliderButton: SliderButtonView = view.findViewById(R.id.slider_button)
        val tabLayout: TabLayout = view.findViewById(R.id.tabLayout)
        val txtTitle: TextView = view.findViewById(R.id.txtTitle)
        val txtSubtitle: TextView = view.findViewById(R.id.txtSubtitle)
        val txtIcon: TextView = view.findViewById(R.id.txtIcon)
        val txtValue: TextView = view.findViewById(R.id.txtValue)
        val txtInfo: TextView = view.findViewById(R.id.txtInfo)
        val sliderSection: View = view.findViewById(R.id.sliderSection)
        val seekBar: SeekBar = view.findViewById(R.id.seekBar)
        val btnMinus: Button = view.findViewById(R.id.btnMinus)
        val btnPlus: Button = view.findViewById(R.id.btnPlus)

        // Initialize LCD
        lcdView.setText(
            listOf(LCDSegment("0V", 28f, Align.LEFT, 1f, true), LCDSegment("0Wh", 28f, Align.RIGHT, 1f, true)),
            listOf(LCDSegment("9APR 12:04AM", 24f, Align.CENTER, 1f, true))
        )

        fun updateTimeUI() {
            val hours = time / 60
            val mins = time % 60
            txtValue.text = String.format(Locale.getDefault(), "%d:%02d", hours, mins)
            val energy = (time / 60f) * 3
            txtInfo.text = String.format(Locale.getDefault(), "Estimated energy gain: ~ %d KWh\nCharging will stop at %d hour, %d min", energy.toInt(), hours, mins)
        }

        fun updateUnitsUI() {
            txtValue.text = String.format(Locale.getDefault(), "%d KWh", units)
            val estimatedHours = units / 3
            txtInfo.text = String.format(Locale.getDefault(), "Estimated duration: ~ %d hours\nCharging will stop at %d KWh", estimatedHours, units)
        }

        // Initialize Tabs
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        txtTitle.text = "FULL CHARGE"
                        txtSubtitle.visibility = View.VISIBLE
                        txtIcon.visibility = View.VISIBLE
                        txtIcon.text = "🔋"
                        txtSubtitle.text = "Charge to 100% capacity"
                        sliderSection.visibility = View.GONE
                    }
                    1 -> {
                        txtTitle.text = "SET TIME"
                        txtSubtitle.visibility = View.GONE
                        txtIcon.visibility = View.GONE
                        sliderSection.visibility = View.VISIBLE
                        seekBar.max = 576
                        seekBar.progress = time / 5
                        updateTimeUI()
                    }
                    2 -> {
                        txtTitle.text = "SET UNITS"
                        txtSubtitle.visibility = View.GONE
                        txtIcon.visibility = View.GONE
                        sliderSection.visibility = View.VISIBLE
                        seekBar.max = 100
                        seekBar.progress = units
                        updateUnitsUI()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Slider logic
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val currentTab = tabLayout.selectedTabPosition
                    if (currentTab == 1) { // SET TIME
                        time = progress * 5
                        updateTimeUI()
                    } else if (currentTab == 2) { // SET UNITS
                        units = progress
                        updateUnitsUI()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnMinus.setOnClickListener {
            val currentTab = tabLayout.selectedTabPosition
            if (currentTab == 1) {
                time = maxOf(5, time - 5)
                seekBar.progress = time / 5
                updateTimeUI()
            } else if (currentTab == 2) {
                units = maxOf(1, units - 1)
                seekBar.progress = units
                updateUnitsUI()
            }
        }

        btnPlus.setOnClickListener {
            val currentTab = tabLayout.selectedTabPosition
            if (currentTab == 1) {
                time += 5
                seekBar.progress = time / 5
                updateTimeUI()
            } else if (currentTab == 2) {
                units += 1
                seekBar.progress = units
                updateUnitsUI()
            }
        }

        sliderButton.activate(false)
        sliderButton.setText("Device is offline")

        return view
    }
}