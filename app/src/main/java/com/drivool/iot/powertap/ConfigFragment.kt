package com.drivool.iot.powertap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import java.util.Locale

class ConfigFragment : Fragment() {

    private lateinit var tabLayout: TabLayout
    private lateinit var txtIcon: TextView
    private lateinit var txtTitle: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var sliderSection: LinearLayout
    private lateinit var txtValue: TextView
    private lateinit var txtInfo: TextView
    private lateinit var btnMinus: View
    private lateinit var btnPlus: View
    private lateinit var seekBar: SeekBar

    /** Charge-limit duration in seconds (matches HomeFragment / firmware). */
    private var timeSeconds = TIME_DEFAULT_SEC
    private var units = 10

    private companion object {
        const val TIME_STEP_SEC = 5
        const val TIME_DEFAULT_SEC = 60
        const val TIME_MAX_SEC = 48 * 3600
        const val UNITS_MAX_KWH = 100
    }

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
        seekBar.max = TIME_MAX_SEC / TIME_STEP_SEC
        seekBar.progress = timeSeconds / TIME_STEP_SEC
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                timeSeconds = (progress * TIME_STEP_SEC).coerceIn(TIME_STEP_SEC, TIME_MAX_SEC)
                updateTimeUI()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        btnMinus.setOnClickListener {
            timeSeconds = maxOf(TIME_STEP_SEC, timeSeconds - TIME_STEP_SEC)
            seekBar.progress = timeSeconds / TIME_STEP_SEC
            updateTimeUI()
        }
        btnPlus.setOnClickListener {
            timeSeconds = minOf(TIME_MAX_SEC, timeSeconds + TIME_STEP_SEC)
            seekBar.progress = timeSeconds / TIME_STEP_SEC
            updateTimeUI()
        }
    }

    private fun showUnitsMode() {
        txtIcon.visibility = View.GONE
        txtSubtitle.visibility = View.GONE
        sliderSection.visibility = View.VISIBLE
        txtTitle.text = "SET UNITS"
        updateUnitsUI()
        seekBar.max = UNITS_MAX_KWH
        seekBar.progress = units
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                units = progress.coerceIn(1, UNITS_MAX_KWH)
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
            units = minOf(UNITS_MAX_KWH, units + 1)
            seekBar.progress = units
            updateUnitsUI()
        }
    }

    private fun updateTimeUI() {
        val hours = timeSeconds / 3600
        val mins = (timeSeconds % 3600) / 60
        val secs = timeSeconds % 60
        txtValue.text = String.format(Locale.getDefault(), "%d:%02d:%02d", hours, mins, secs)
        val energy = (timeSeconds / 3600f) * 3f
        txtInfo.text = String.format(
            Locale.getDefault(),
            "Estimated energy gain: ~ %.1f kWh\nCharging will stop after %d h, %d min, %d sec",
            energy,
            hours,
            mins,
            secs,
        )
    }

    private fun updateUnitsUI() {
        txtValue.text = String.format(Locale.getDefault(), "%d kWh", units)
        val estimatedHours = units / 3
        txtInfo.text = String.format(
            Locale.getDefault(),
            "Estimated duration: ~ %d hours\nCharging will stop at %d kWh",
            estimatedHours,
            units,
        )
    }
}
