package com.example.powertap

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class PlaceholderFragment() : Fragment() {

    private var title: String = ""

    constructor(title: String) : this() {
        this.title = title
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val tv = TextView(context)
        tv.text = "This is the $title page\n(Placeholder)"
        tv.textSize = 24f
        tv.gravity = Gravity.CENTER
        tv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return tv
    }
}
