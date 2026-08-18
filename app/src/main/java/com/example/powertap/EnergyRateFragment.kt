package com.drivool.iot.powertap

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView

class EnergyRateFragment : Fragment() {
    private val tariffRates = EnergyRateModel.defaultTariffRates()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_energy_rate, container, false)
        val tariffContainer = rootView.findViewById<LinearLayout>(R.id.tariffContainer)
        renderTariffCards(tariffContainer)
        return rootView
    }

    private fun renderTariffCards(container: LinearLayout) {
        container.removeAllViews()
        tariffRates.forEach { rateGroup ->
            container.addView(createTariffCard(rateGroup))
        }
    }

    private fun createTariffCard(rateGroup: TariffGroup): View {
        val context = requireContext()
        val spacing = dp(12)
        val card = MaterialCardView(context).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(context, R.color.divider)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = spacing
            }
        }

        val cardContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12))
        }
        card.addView(cardContent)

        cardContent.addView(TextView(context).apply {
            text = "${rateGroup.label.uppercase()} TARIFF"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })

        cardContent.addView(TextView(context).apply {
            text = rateGroup.time
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })

        val table = TableLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            isStretchAllColumns = true
            isShrinkAllColumns = true
        }

        table.addView(createHeaderRow())
        rateGroup.slabs.forEach { slab -> table.addView(createRateRow(slab)) }
        cardContent.addView(table)

        return card
    }

    private fun createHeaderRow(): TableRow {
        val context = requireContext()
        return TableRow(context).apply {
            addView(createCell("UNITS", true))
            addView(createCell("CATEGORY", true))
            addView(createCell("CHARGE / UNIT", true))
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_canvas))
        }
    }

    private fun createRateRow(slab: TariffSlab): TableRow {
        return TableRow(requireContext()).apply {
            addView(createCell(slab.units, false))
            addView(createCell(slab.category, false))
            addView(createCell("₹${slab.price}", false))
        }
    }

    private fun createCell(text: String, isHeader: Boolean): TextView {
        val context = requireContext()
        return TextView(context).apply {
            this.text = text
            textSize = if (isHeader) 13f else 14f
            setTypeface(typeface, if (isHeader) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isHeader) R.color.text_primary else R.color.text_secondary
                )
            )
            setPadding(dp(8))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
