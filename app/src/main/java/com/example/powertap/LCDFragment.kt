package com.example.powertap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment

class LCDFragment : Fragment() {

    private lateinit var lcd: TwoLineLCDView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_lcd, container, false)

        lcd = view.findViewById(R.id.lcdView)

        val btnIdle: Button = view.findViewById(R.id.btnIdle)
        val btnStarting: Button = view.findViewById(R.id.btnStarting)
        val btnCharging: Button = view.findViewById(R.id.btnCharging)
        val btnStopping: Button = view.findViewById(R.id.btnStopping)

        btnIdle.setOnClickListener {
            showIdle()
        }

        btnStarting.setOnClickListener {
            showStarting()
        }

        btnCharging.setOnClickListener {
            showCharging()
        }

        btnStopping.setOnClickListener {
            showStopping()
        }

        showIdle()

        return view
    }

    private fun showIdle() {
        lcd.setText(
            listOf(
                LCDSegment("248V", 28f, Align.LEFT),
                LCDSegment("119.6Wh", 28f, Align.RIGHT)
            ),
            listOf(
                LCDSegment("15MAR 1:17PM", 24f, Align.CENTER)
            )
        )
    }

    private fun showStarting() {
        lcd.setText(
            listOf(
                LCDSegment("Starting...", 28f, Align.CENTER)
            ),
            listOf(
                LCDSegment("Please Wait", 24f, Align.CENTER)
            )
        )
    }

    private fun showCharging() {
        lcd.setText(
            listOf(
                LCDSegment("240V", 28f, Align.LEFT),
                LCDSegment("4.5A", 28f, Align.CENTER),
                LCDSegment("1.1kW", 28f, Align.RIGHT)
            ),
            listOf(
                LCDSegment("⌁", 24f, Align.LEFT),
                LCDSegment("130.6Wh", 24f, Align.LEFT),
                LCDSegment("00:00:36", 24f, Align.RIGHT)
            )
        )
    }

    private fun showStopping() {
        lcd.setText(
            listOf(
                LCDSegment("Stopping...", 28f, Align.CENTER)
            ),
            listOf(
                LCDSegment("Disconnecting", 24f, Align.CENTER)
            )
        )
    }
}
