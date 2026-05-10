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
        historyAdapter = HistoryAdapter { workDay ->
            // Ao clicar em um item do histórico, poderíamos voltar para a MainActivity 
            // e selecionar aquela data, mas por enquanto apenas exibimos.
            finish() 
        }
        binding.rvHistory.adapter = historyAdapter
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
    }

    private fun setupObservers() {
        viewModel.allWorkDays.observe(this) { list ->
            if (list.isNullOrEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.rvHistory.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.rvHistory.visibility = View.VISIBLE
                historyAdapter.submitList(list.sortedByDescending { it.date })
            }
        }
    }
}
