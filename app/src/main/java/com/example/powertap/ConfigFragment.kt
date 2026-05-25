package com.example.powertap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout

class ConfigFragment : Fragment() {

    private lateinit var tabLayout: TabLayout
    private lateinit var txtIcon: TextView
    private lateinit var txtTitle: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var sliderSection: LinearLayout
    private lateinit var txtValue: TextView
    private lateinit var txtInfo: TextView
    private lateinit var btnMinus: Button
    private lateinit var btnPlus: Button
    private lateinit var seekBar: SeekBar

    private var time = 60
    private var units = 10

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_config, container, false)

        tabLayout = view.findViewById(R.id.tabLayout)
        txtIcon = view.findViewById(R.id.txtIcon)
        txtTitle = view.findViewById(R.id.txtTitle)
        txtSubtitle = view.findViewById(R.id.txtSubtitle)
        sliderSection = view.findViewById(R.id.sliderSection)
        txtValue = view.findViewById(R.id.txtValue)
        txtInfo = view.findViewById(R.id.txtInfo)
        btnMinus = view.findViewById(R.id.btnMinus)
        btnPlus = view.findViewById(R.id.btnPlus)
        seekBar = view.findViewById(R.id.seekBar)

        setupTabs()
        showFullCharge()

        return view
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when(tab?.position) {
                    0 -> showFullCharge()
                    1 -> showTimeMode()
                    2 -> showUnitsMode()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showFullCharge() {
        sliderSection.visibility = View.GONE
        txtIcon.visibility = View.VISIBLE
        txtSubtitle.visibility = View.VISIBLE
        txtIcon.text = "🔋"
        txtTitle.text = "FULL CHARGE"
        txtSubtitle.text = "Charge to 100% capacity"
    }

    private fun showTimeMode() {
        txtIcon.visibility = View.GONE
        txtSubtitle.visibility = View.GONE
        sliderSection.visibility = View.VISIBLE
        txtTitle.text = "SET TIME"
        updateTimeUI()
        seekBar.max = 576
        seekBar.progress = time / 5
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                time = progress * 5
                updateTimeUI()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        btnMinus.setOnClickListener {
            time = maxOf(5, time - 5)
            seekBar.progress = time / 5
            updateTimeUI()
        }
        btnPlus.setOnClickListener {
            time += 5
            seekBar.progress = time / 5
            updateTimeUI()
        }
    }

    private fun showUnitsMode() {
        txtIcon.visibility = View.GONE
        txtSubtitle.visibility = View.GONE
        sliderSection.visibility = View.VISIBLE
        txtTitle.text = "SET UNITS"
        updateUnitsUI()
        seekBar.max = 100
        seekBar.progress = units
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                units = progress
                updateUnitsUI()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        btnMinus.setOnClickListener {
            units = maxOf(1, units - 1)
            seekBar.progress = units
            updateUnitsUI()
        }
        btnPlus.setOnClickListener {
            units += 1
            seekBar.progress = units
            updateUnitsUI()
        }
    }

    private fun updateTimeUI() {
        val hours = time / 60
        val mins = time % 60
        txtValue.text = "$hours:${mins.toString().padStart(2, '0')}"
        val energy = (time / 60f) * 3
        txtInfo.text = "Estimated energy gain: ~ ${energy.toInt()} KWh\nCharging will stop at $hours hour, $mins min"
    }

    private fun updateUnitsUI() {
        txtValue.text = "$units KWh"
        val estimatedHours = units / 3
        txtInfo.text = "Estimated duration: ~ $estimatedHours hours\nCharging will stop at $units KWh"
    }
}
