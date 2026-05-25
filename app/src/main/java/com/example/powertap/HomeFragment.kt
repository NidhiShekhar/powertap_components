package com.example.powertap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout

class HomeFragment : Fragment() {

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
        val sliderSection: View = view.findViewById(R.id.sliderSection)
        val seekBar: SeekBar = view.findViewById(R.id.seekBar)

        // Initialize LCD
        lcdView.setText(
            listOf(LCDSegment("0V", 28f, Align.LEFT, 1f, true), LCDSegment("0Wh", 28f, Align.RIGHT, 1f, true)),
            listOf(LCDSegment("9APR 12:04AM", 24f, Align.CENTER, 1f, true))
        )

        // Initialize Tabs
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        txtTitle.text = "FULL CHARGE"
                        txtSubtitle.visibility = View.VISIBLE
                        txtIcon.visibility = View.VISIBLE
                        txtIcon.text = "🔋"
                        sliderSection.visibility = View.GONE
                    }
                    1 -> {
                        txtTitle.text = "SET TIME"
                        txtSubtitle.visibility = View.GONE
                        txtIcon.visibility = View.GONE
                        sliderSection.visibility = View.VISIBLE
                        txtValue.text = "1:00"
                    }
                    2 -> {
                        txtTitle.text = "SET UNITS"
                        txtSubtitle.visibility = View.GONE
                        txtIcon.visibility = View.GONE
                        sliderSection.visibility = View.VISIBLE
                        txtValue.text = "10 KWh"
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Slider logic
        sliderButton.activate(false)
        sliderButton.setText("Device is offline")

        return view
    }
}