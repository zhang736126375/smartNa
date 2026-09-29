package com.bingo.smartna.collector.wallet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.collector.data.model.WalletEntry
import com.bingo.smartna.databinding.ItemWalletEntryBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class WalletEntryAdapter(
    private val showPaidHint: Boolean = false
) : ListAdapter<WalletEntry, WalletEntryAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemWalletEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding, showPaidHint)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), position == itemCount - 1)
    }

    class Holder(
        private val binding: ItemWalletEntryBinding,
        private val showPaidHint: Boolean
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WalletEntry, last: Boolean) {
            val context = binding.root.context
            binding.tvTitle.text = item.title
            val timeText = formatTime(item.time)
            binding.tvTime.text = if (showPaidHint && item.amount >= 0) {
                context.getString(R.string.tasks_paid_hint).let { "$timeText · $it" }
            } else {
                timeText
            }
            if (item.amount >= 0) {
                binding.tvAmount.text = context.getString(R.string.wallet_entry_amount, item.amount)
                binding.tvAmount.setTextColor(ContextCompat.getColor(context, R.color.money_green))
            } else {
                binding.tvAmount.text = context.getString(R.string.wallet_entry_out, abs(item.amount))
                binding.tvAmount.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            binding.divider.visibility = if (last) View.GONE else View.VISIBLE
        }

        private fun formatTime(time: Long): String {
            val now = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val cal = Calendar.getInstance().apply { timeInMillis = time }
            return when {
                isSameDay(cal, now) -> SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(time))
                isSameDay(cal, yesterday) -> {
                    "昨天 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(time))
                }
                else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(time))
            }
        }

        private fun isSameDay(a: Calendar, b: Calendar): Boolean {
            return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
    }

    private object Diff : DiffUtil.ItemCallback<WalletEntry>() {
        override fun areItemsTheSame(oldItem: WalletEntry, newItem: WalletEntry) =
            oldItem.time == newItem.time && oldItem.title == newItem.title

        override fun areContentsTheSame(oldItem: WalletEntry, newItem: WalletEntry) =
            oldItem == newItem
    }
}
