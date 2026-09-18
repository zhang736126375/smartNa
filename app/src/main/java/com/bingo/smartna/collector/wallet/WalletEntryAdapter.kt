package com.bingo.smartna.collector.wallet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.collector.data.model.WalletEntry
import com.bingo.smartna.databinding.ItemWalletEntryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WalletEntryAdapter : ListAdapter<WalletEntry, WalletEntryAdapter.Holder>(Diff) {

    private val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemWalletEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding, formatter)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(
        private val binding: ItemWalletEntryBinding,
        private val formatter: SimpleDateFormat
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WalletEntry) {
            binding.tvTitle.text = item.title
            binding.tvTime.text = formatter.format(Date(item.time))
            binding.tvAmount.text = binding.root.context.getString(R.string.wallet_entry_amount, item.amount)
        }
    }

    private object Diff : DiffUtil.ItemCallback<WalletEntry>() {
        override fun areItemsTheSame(oldItem: WalletEntry, newItem: WalletEntry) =
            oldItem.time == newItem.time && oldItem.title == newItem.title

        override fun areContentsTheSame(oldItem: WalletEntry, newItem: WalletEntry) =
            oldItem == newItem
    }
}
