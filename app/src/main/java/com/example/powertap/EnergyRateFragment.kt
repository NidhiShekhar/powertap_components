package com.drivool.iot.powertap

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
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
import com.google.firebase.database.FirebaseDatabase
import com.google.android.material.card.MaterialCardView

class EnergyRateFragment : Fragment() {
    // Temporary single-tenant association key.
    // TODO: Replace with mapped association key from resident-manager onboarding/auth flow.
    private val defaultAssociationKey = "ssowa2013gmailcom"

    private var tariffRates: List<TariffGroup> = emptyList()
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var tariffContainer: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_energy_rate, container, false)
        titleView = rootView.findViewById(R.id.txtEnergyRateTitle)
        subtitleView = rootView.findViewById(R.id.txtEnergyRateSubtitle)
        tariffContainer = rootView.findViewById(R.id.tariffContainer)

        subtitleView?.text = "Loading tariff rates from Firebase..."
        renderTariffCards(EnergyRateModel.defaultTariffRates(), "Fallback")
        loadTariffsFromFirebase()
        return rootView
    }

    override fun onDestroyView() {
        super.onDestroyView()
        titleView = null
        subtitleView = null
        tariffContainer = null
    }

    private fun loadTariffsFromFirebase() {
        // Single-tenant mode: all users read tariffs from SSOWA association.
        // This keeps behavior stable until user->association mapping is available.
        tryFetchTariffsForKeys(listOf(defaultAssociationKey), 0)
    }

    private fun tryFetchTariffsForKeys(keys: List<String>, index: Int) {
        if (index >= keys.size) {
            subtitleView?.text = "No CSO tariff found for this account. Showing fallback rates."
            tariffRates = EnergyRateModel.defaultTariffRates()
            renderTariffCards(tariffRates, "Fallback")
            return
        }

        val associationKey = keys[index]
        val detailsRef = FirebaseDatabase.getInstance()
            .getReference("CSOs/$associationKey/activeTariff/details")

        detailsRef.get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    tryFetchTariffsForKeys(keys, index + 1)
                    return@addOnSuccessListener
                }

                val detailsMap = snapshot.value as? Map<String, Any?> ?: emptyMap()
                val parsed = EnergyRateModel.parseFromFirebase(detailsMap)
                if (parsed == null || parsed.rates.isEmpty()) {
                    tryFetchTariffsForKeys(keys, index + 1)
                    return@addOnSuccessListener
                }

                tariffRates = parsed.rates
                subtitleView?.text = "Source: ${parsed.source} | Association: $associationKey"
                renderTariffCards(tariffRates, parsed.source)
            }
            .addOnFailureListener { error ->
                Log.e("EnergyRateFragment", "Tariff fetch failed for key $associationKey", error)
                tryFetchTariffsForKeys(keys, index + 1)
            }
    }

    private fun renderTariffCards(rates: List<TariffGroup>, source: String) {
        val container = tariffContainer ?: return
        container.removeAllViews()
        titleView?.text = "Electricity Tariff"
        if (subtitleView?.text.isNullOrBlank()) {
            subtitleView?.text = "Source: $source"
        }
        rates.forEach { rateGroup ->
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
