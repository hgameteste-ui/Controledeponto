/*
 * Nome: HistoryActivity.kt
 * Versão: 1.1.0
 * Data: 24/05/2024
 * Hora: 23:00
 * Descrição: Activity para exibição do histórico de registros, atualizada para suportar exibição de dados com intervalos.
 * 
 * Histórico de Modificações:
 * 24/05/2024 23:00 - Alterada observação para usar allWorkDaysWithIntervals, garantindo que pausas sejam consideradas no total do dia.
 */

package com.example.controledeponto

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.controledeponto.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val viewModel: WorkViewModel by viewModels()
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupObservers()
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter { item ->
            // Ao clicar em um item do histórico, poderíamos voltar para a MainActivity 
            // e selecionar aquela data, mas por enquanto apenas exibimos.
            finish() 
        }
        binding.rvHistory.adapter = historyAdapter
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
    }

    private fun setupObservers() {
        // Observa a lista que contém os intervalos para cálculos precisos
        viewModel.allWorkDaysWithIntervals.observe(this) { list ->
            if (list.isNullOrEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.rvHistory.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.rvHistory.visibility = View.VISIBLE
                historyAdapter.submitList(list.sortedByDescending { it.workDay.date })
            }
        }
    }
}
