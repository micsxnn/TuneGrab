package com.tunegrab.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tunegrab.app.R
import com.tunegrab.app.databinding.FragmentSearchBinding
import com.tunegrab.app.search.SearchAdapter
import com.tunegrab.app.search.SearchViewModel
import com.tunegrab.app.search.SearchUiState
import com.tunegrab.app.search.ProcessedSearchResult
import com.tunegrab.app.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Aba de busca: permite buscar músicas/vídeos no YouTube e baixar diretamente
 * Usa o mesmo mecanismo do app (NewPipeExtractor + PoToken) para busca
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SearchViewModel by viewModels {
        SearchViewModel.factory(requireContext())
    }

    private lateinit var adapter: SearchAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupButtons()
        observeState()
    }

    private fun setupRecyclerView() {
        adapter = SearchAdapter { result -> onResultClick(result) }
        binding.recyclerViewResults.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewResults.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnSearch.setOnClickListener { performSearch() }

        binding.inputSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }
    }

    private fun performSearch() {
        val query = binding.inputSearch.text?.toString().orEmpty().trim()
        if (query.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.search_empty_query, Toast.LENGTH_SHORT).show()
            return
        }
        if (query.length < 2) {
            Toast.makeText(requireContext(), R.string.search_min_length, Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.search(query)
    }

    /**
     * Ao clicar em um resultado, abre o seletor de formato para download
     * usando o mesmo fluxo que o HomeFragment
     */
    private fun onResultClick(result: ProcessedSearchResult) {
        val activity = activity as? MainActivity ?: return
        val url = result.webUrl

        // Reutiliza o método existente de download
        activity.openDownloadFor(url)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state -> updateUi(state) }
                }
                launch {
                    viewModel.searchResults.collectLatest { results ->
                        adapter.updateResults(results)
                        binding.recyclerViewResults.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
                        binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun updateUi(state: SearchUiState) {
        binding.progress.visibility = if (state.isSearching) View.VISIBLE else View.GONE
        binding.btnSearch.isEnabled = !state.isSearching

        binding.tvStatus.visibility = if (state.isSearching || state.error != null) View.VISIBLE else View.GONE

        if (state.error != null) {
            binding.tvStatus.text = if (state.isSearching) {
                getString(R.string.status_searching)
            } else {
                state.error
            }
            binding.btnSearch.text = getString(R.string.btn_retry)
            binding.btnSearch.setOnClickListener {
                binding.btnSearch.text = getString(R.string.btn_search)
                viewModel.retrySearch()
            }
        } else {
            binding.tvStatus.text = if (state.isSearching) {
                getString(R.string.status_searching)
            } else {
                getString(R.string.search_results_count, state.results.size)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): SearchFragment = SearchFragment()
    }
}