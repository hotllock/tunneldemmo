package com.tunnel.demo.tunneldemo.ui

import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tunnel.demo.tunneldemo.R
import com.tunnel.demo.tunneldemo.model.HttpMethod
import com.tunnel.demo.tunneldemo.model.Transaction
import com.tunnel.demo.tunneldemo.model.TransactionStatus

class TransactionAdapter(
    private val onItemClick: (Transaction) -> Unit,
    private val onReplayClick: (Transaction) -> Unit
) : ListAdapter<Transaction, TransactionAdapter.ViewHolder>(DiffCallback()) {

    private val methodColors = mapOf(
        HttpMethod.GET to R.color.http_get,
        HttpMethod.POST to R.color.http_post,
        HttpMethod.PUT to R.color.http_put,
        HttpMethod.DELETE to R.color.http_delete,
        HttpMethod.PATCH to R.color.http_patch,
        HttpMethod.CONNECT to R.color.http_other,
        HttpMethod.HEAD to R.color.http_other,
        HttpMethod.OPTIONS to R.color.http_other,
        HttpMethod.OTHER to R.color.http_other
    )

    private val statusColors = mapOf(
        2 to R.color.status_2xx,
        3 to R.color.status_3xx,
        4 to R.color.status_4xx,
        5 to R.color.status_5xx
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.itemView.alpha = 0f
        holder.itemView.translationY = 20f
        holder.itemView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .start()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMethod: TextView = itemView.findViewById(R.id.tv_method)
        private val tvHost: TextView = itemView.findViewById(R.id.tv_host)
        private val tvPath: TextView = itemView.findViewById(R.id.tv_path)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_status)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val btnReplay: ImageButton = itemView.findViewById(R.id.btn_replay)

        fun bind(t: Transaction) {
            tvMethod.text = t.method.value
            val colorId = methodColors[t.method] ?: R.color.http_other
            tvMethod.setTextColor(ContextCompat.getColor(itemView.context, colorId))
            tvMethod.backgroundTintList = ContextCompat.getColorStateList(
                itemView.context, R.color.accent_container
            )

            tvHost.text = t.host.ifEmpty {
                try {
                    java.net.URL(t.fullUrl).host
                } catch (_: Exception) { t.fullUrl }
            }
            tvPath.text = t.path.ifEmpty { "/" }

            if (t.statusCode > 0) {
                val cat = t.statusCode / 100
                val scId = statusColors[cat] ?: R.color.status_unknown
                tvStatus.setTextColor(ContextCompat.getColor(itemView.context, scId))
                tvStatus.text = t.statusCode.toString()
            } else {
                tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_disabled))
                tvStatus.text = when (t.status) {
                    TransactionStatus.IN_PROGRESS -> "..."
                    TransactionStatus.FAILED -> "ERR"
                    else -> "--"
                }
            }

            tvTime.text = when {
                t.elapsed > 0 -> "${t.elapsed}ms"
                t.status == TransactionStatus.IN_PROGRESS -> "..."
                else -> ""
            }

            btnReplay.visibility = if (t.statusCode > 0) View.VISIBLE else View.GONE
            btnReplay.setOnClickListener { onReplayClick(t) }

            itemView.setOnClickListener { onItemClick(t) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Transaction>() {
        override fun areItemsTheSame(old: Transaction, new: Transaction): Boolean = old.id == new.id
        override fun areContentsTheSame(old: Transaction, new: Transaction): Boolean = old == new
    }
}
