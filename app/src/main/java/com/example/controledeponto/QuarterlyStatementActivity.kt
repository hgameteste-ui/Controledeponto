/**
 * Nome do Arquivo: QuarterlyStatementActivity.kt
 * Pacote: com.example.controledeponto
 * Projeto: Controle de Ponto Eletrônico
 *
 * Descrição:
 * Tela dedicada à exibição do extrato trimestral de horas extras. 
 * Apresenta o histórico móvel dos últimos 3 meses a partir do mês selecionado,
 * permitindo uma visão detalhada do banco de horas acumulado.
 *
 * Histórico de Modificações:
 * Versão   Data        Autor           Descrição
 * -----------------------------------------------------------------------------------------
 * 2.0.1    Jun/2026    Walter R. C.    Ajuste de contraste nas TextViews para melhor legibilidade no tema escuro.
 * 2.0.0    Jun/2026    Walter R. C.    Consolidação do extrato móvel (rolling quarter) para exibição 
 *                                      dos últimos 3 meses retroativos à data de seleção.
 * 1.9.8    Jun/2026    Walter R. C.    Criação da tela de extrato trimestral.
 */

package com.example.controledeponto

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.controledeponto.databinding.ActivityQuarterlyStatementBinding
import java.util.*

class QuarterlyStatementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuarterlyStatementBinding
    private val viewModel: WorkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuarterlyStatementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupObservers()
    }

    private fun setupObservers() {
        // Correção: Observa quarterlyMonthlyOvertime para o extrato do trimestre calendário conforme solicitado
        viewModel.quarterlyMonthlyOvertime.observe(this) { monthlyList ->
            binding.layoutQuarterlyMonths.removeAllViews()
            
            val titleView = TextView(this).apply {
                text = "HISTÓRICO DO TRIMESTRE ATUAL"
                setTextColor(resources.getColor(android.R.color.holo_blue_light, theme))
                textSize = 12f
                setPadding(0, 0, 0, 24)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            binding.layoutQuarterlyMonths.addView(titleView)

            var totalMinutes = 0L
            monthlyList.forEach { (month, minutes) ->
                totalMinutes += minutes
                val absMinutes = Math.abs(minutes)
                val hours = absMinutes / 60
                val mins = absMinutes % 60
                val sign = if (minutes >= 0) "+" else "-"
                
                val textView = TextView(this).apply {
                    text = String.format(Locale.getDefault(), "%s: %s%02dh %02dm", month, sign, hours, mins)
                    textSize = 17f
                    // Correção: Alterado de 'white' para 'holo_blue_light' para garantir visibilidade em temas claros
                    setTextColor(resources.getColor(android.R.color.holo_blue_light, theme))
                    setPadding(0, 12, 0, 12)
                }
                binding.layoutQuarterlyMonths.addView(textView)
            }

            if (monthlyList.isNotEmpty()) {
                val separator = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 3).apply {
                        setMargins(0, 24, 0, 24)
                    }
                    setBackgroundColor(resources.getColor(android.R.color.darker_gray, theme))
                }
                binding.layoutQuarterlyMonths.addView(separator)

                val absTotal = Math.abs(totalMinutes)
                val totalHours = absTotal / 60
                val totalMins = absTotal % 60
                val totalSign = if (totalMinutes >= 0) "+" else "-"
                
                val totalTextView = TextView(this).apply {
                    text = String.format(Locale.getDefault(), "TOTAL ACUMULADO: %s%02dh %02dm", totalSign, totalHours, totalMins)
                    textSize = 20f
                    setTextColor(resources.getColor(android.R.color.holo_blue_light, theme))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 8, 0, 8)
                }
                binding.layoutQuarterlyMonths.addView(totalTextView)
            } else {
                val emptyView = TextView(this).apply {
                    text = "Sem registros para o período selecionado."
                    textSize = 14f
                    setTextColor(resources.getColor(android.R.color.darker_gray, theme))
                    setPadding(0, 16, 0, 0)
                }
                binding.layoutQuarterlyMonths.addView(emptyView)
            }
        }
    }
}
