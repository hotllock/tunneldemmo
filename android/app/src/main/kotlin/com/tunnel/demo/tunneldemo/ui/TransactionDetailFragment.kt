package com.tunnel.demo.tunneldemo.ui

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import com.tunnel.demo.tunneldemo.R
import com.tunnel.demo.tunneldemo.databinding.FragmentTransactionDetailBinding
import com.tunnel.demo.tunneldemo.model.Transaction
import com.tunnel.demo.tunneldemo.service.LocalProxyServer
import com.tunnel.demo.tunneldemo.util.formatTimestamp
import com.tunnel.demo.tunneldemo.util.truncate
import kotlinx.coroutines.launch

class TransactionDetailFragment : Fragment() {

    private var _binding: FragmentTransactionDetailBinding? = null
    private val binding get() = _binding!!

    private var transaction: Transaction? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        transaction = arguments?.getSerializable("transaction") as? Transaction
        transaction?.let { populateUi(it) }
        setupTabs()
        setupReplayButton()
    }

    private fun populateUi(t: Transaction) {
        binding.tvDetailMethod.text = t.method.value
        binding.tvDetailUrl.text = t.fullUrl.truncate(200)
        binding.tvDetailHost.text = t.host
        binding.tvDetailPath.text = t.path
        binding.tvDetailStatusCode.text = "${t.statusCode}"
        binding.tvDetailRequestTime.text = t.requestTime.formatTimestamp()
        binding.tvDetailResponseTime.text = t.responseTime.formatTimestamp()
        binding.tvDetailElapsed.text = t.elapsed.format()
        binding.tvDetailSize.text = t.contentLength.formatBytes()
        binding.tvDetailDestination.text = "${t.destinationIp}:${t.destinationPort}"
        binding.chipHttps.isChecked = t.isSecure
        binding.chipHttps.visibility = if (t.isSecure) View.VISIBLE else View.GONE
    }

    private fun setupTabs() {
        val tabTitles = arrayOf("Request", "Response", "Raw")
        val tabIcons = arrayOf(
            R.drawable.ic_play,
            R.drawable.ic_stats,
            R.drawable.ic_search
        )

        val adapter = DetailPagerAdapter(this)
        binding.viewPagerDetail.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPagerDetail) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    private fun setupReplayButton() {
        binding.btnReplay.setOnClickListener {
            val t = transaction ?: return@setOnClickListener
            lifecycleScope.launch {
                binding.btnReplay.isEnabled = false
                binding.btnReplay.text = "Replaying..."
                val result = LocalProxyServer.replayRequest(t)
                binding.btnReplay.isEnabled = true
                binding.btnReplay.text = "Replay"
                if (result != null) {
                    transaction = result
                    populateUi(result)
                    Toast.makeText(context, "Replay complete (${result.statusCode})", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun Long.format(): String = when {
        this < 1000 -> "${this}ms"
        this < 60000 -> "%.1fs".format(this / 1000.0)
        else -> "${this / 60000}m ${(this % 60000) / 1000}s"
    }

    private fun Long.formatBytes(): String = when {
        this < 1024 -> "$this B"
        this < 1048576 -> "%.1f KB".format(this / 1024.0)
        this < 1073741824 -> "%.1f MB".format(this / 1048576.0)
        else -> "%.1f GB".format(this / 1073741824.0)
    }
}
