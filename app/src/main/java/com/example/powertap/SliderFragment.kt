package com.example.powertap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment

class SliderFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_slider, container, false)

        val slider: SliderButtonView = view.findViewById(R.id.slider)
        val btnShow: Button = view.findViewById(R.id.btnShow)
        val btnHide: Button = view.findViewById(R.id.btnHide)
        val btnActive: Button = view.findViewById(R.id.btnActive)
        val btnDeactive: Button = view.findViewById(R.id.btnDeactive)

        btnActive.setOnClickListener {
            slider.activate(true)
        }

        btnDeactive.setOnClickListener {
            slider.activate(false)
        }

        btnShow.setOnClickListener {
            slider.showProgress("Starting...")
        }

        btnHide.setOnClickListener {
            slider.hideProgress()
        }

        slider.onSlideRight = {
            activity?.runOnUiThread {
                Toast.makeText(context, "Slid Right", Toast.LENGTH_SHORT).show()
            }
        }

        slider.onSlideLeft = {
            activity?.runOnUiThread {
                Toast.makeText(context, "Slid Left", Toast.LENGTH_SHORT).show()
            }
        }

        return view
    }
}
