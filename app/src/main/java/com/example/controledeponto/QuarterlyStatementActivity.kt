/*
 * Nome do Arquivo: QuarterlyStatementActivity.kt
 * Versão: 4.1.0
 * Data: 13/02/2025
 * Hora: 16:30
 * Descrição: Tela de extrato trimestral atualizada para suporte ao Dark Mode.
 */

package com.example.controledeponto

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.controledeponto.databinding.ActivityQuarterlyStatementBinding
import com.google.android.material.card.MaterialCardView
import java.util.*

class QuarterlyStatementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuarterlyStatementBinding
    private val viewModel: WorkViewModel by viewModels()

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuarterlyStatementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupObservers()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_quarterly_statement, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                handleExport()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleExport() {
        Toast.makeText(this, getString(R.string.toast_export_dev), Toast.LENGTH_SHORT).show()
    }

    private fun showMonthlyDetails(month: String) {
        val message = getString(R.string.toast_monthly_details, month)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupObservers() {
        viewModel.quarterlyMonthlyOvertime.observe(this) { monthlyList ->
            binding.layoutQuarterlyMonths.removeAllViews()
            
            val titleView = TextView(this).apply {
                text = getString(R.string.title_quarterly_history)
                setTextColor(ContextCompat.getColor(context, R.color.purple_500))
                textSize = 12f
                setPadding(8.dp(), 16.dp(), 8.dp(), 16.dp()) 
                setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.1f
                setAllCaps(true)
            }
            binding.layoutQuarterlyMonths.addView(titleView)

            var totalMinutes = 0L
            monthlyList.forEach { (month, minutes) ->
                totalMinutes += minutes
                val absMinutes = Math.abs(minutes)
                val hours = absMinutes / 60
                val mins = absMinutes % 60
                val isPositive = minutes >= 0
                val sign = if (isPositive) "+" else "-"
                
                val monthCard = MaterialCardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(4.dp(), 4.dp(), 4.dp(), 12.dp())
                    }
                    radius = 16.dp().toFloat()
                    cardElevation = 2.dp().toFloat()
                    setCardBackgroundColor(ContextCompat.getColorStateList(this@QuarterlyStatementActivity, R.color.card_audit_bg))
                    strokeWidth = 1.dp()
                    strokeColor = ContextCompat.getColor(context, R.color.separator_color)
                    
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showMonthlyDetails(month) }
                }

                val rowContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(20.dp(), 24.dp(), 20.dp(), 24.dp())
                    gravity = Gravity.CENTER_VERTICAL
                }

                val monthTextView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = month
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(context, R.color.audit_text_primary))
                }

                val valueTextView = TextView(this).apply {
                    text = String.format(Locale.getDefault(), "%s%02dh %02dm", sign, hours, mins)
                    textSize = 16f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    val colorRes = if (isPositive) R.color.status_success else R.color.status_absence
                    setTextColor(ContextCompat.getColor(context, colorRes))
                }

                rowContainer.addView(monthTextView)
                rowContainer.addView(valueTextView)
                monthCard.addView(rowContainer)
                binding.layoutQuarterlyMonths.addView(monthCard)
            }

            if (monthlyList.isNotEmpty()) {
                binding.layoutQuarterlyMonths.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(1, 24.dp())
                })

                val absTotal = Math.abs(totalMinutes)
                val totalHours = absTotal / 60
                val totalMins = absTotal % 60
                val isTotalPositive = totalMinutes >= 0
                val totalSign = if (isTotalPositive) "+" else "-"
                
                val totalCard = MaterialCardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(4.dp(), 8.dp(), 4.dp(), 32.dp())
                    }
                    radius = 24.dp().toFloat()
                    cardElevation = 8.dp().toFloat()
                    setCardBackgroundColor(ContextCompat.getColorStateList(this@QuarterlyStatementActivity, R.color.card_balance_bg))
                    strokeWidth = 2.dp()
                    strokeColor = ContextCompat.getColor(context, R.color.text_header_themed)
                }

                val totalContent = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24.dp(), 32.dp(), 24.dp(), 32.dp())
                    gravity = Gravity.CENTER
                }

                totalContent.addView(TextView(this).apply {
                    text = getString(R.string.label_total_accumulated)
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.text_header_themed))
                    setTypeface(null, Typeface.BOLD)
                    letterSpacing = 0.2f
                    setAllCaps(true)
                })

                totalContent.addView(TextView(this).apply {
                    text = String.format(Locale.getDefault(), "%s%02dh %02dm", totalSign, totalHours, totalMins)
                    textSize = 32f
                    val colorRes = if (isTotalPositive) R.color.status_success else R.color.status_absence
                    setTextColor(ContextCompat.getColor(context, colorRes))
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 8.dp(), 0, 0)
                })

                totalCard.addView(totalContent)
                binding.layoutQuarterlyMonths.addView(totalCard)
            } else {
                val emptyView = TextView(this).apply {
                    text = getString(R.string.empty_quarterly_records)
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, R.color.audit_text_secondary))
                    setPadding(0, 32.dp(), 0, 0)
                    gravity = Gravity.CENTER
                }
                binding.layoutQuarterlyMonths.addView(emptyView)
            }
        }
    }
}
