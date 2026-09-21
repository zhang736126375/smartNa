package com.bingo.smartna.collector.device

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.databinding.ItemDeviceKitBinding
import com.blankj.utilcode.util.ClickUtils

class DeviceKitAdapter(
    private val onSelect: (DeviceKit) -> Unit
) : RecyclerView.Adapter<DeviceKitAdapter.Holder>() {

    private val kits = DeviceKit.entries
    var selected: DeviceKit? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDeviceKitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(kits[position], kits[position] == selected, onSelect)
    }

    override fun getItemCount() = kits.size

    inner class Holder(private val binding: ItemDeviceKitBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(kit: DeviceKit, selected: Boolean, onSelect: (DeviceKit) -> Unit) {
            binding.tvTitle.setText(kit.titleRes)
            binding.tvTitle.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (selected) R.color.blue_primary else R.color.text_dark
                )
            )
            binding.ivKit.setImageResource(
                if (kit == DeviceKit.GRIPPER) R.drawable.ic_kit_gripper else R.drawable.ic_kit_ego
            )
            binding.root.setBackgroundResource(
                if (selected) R.drawable.bg_kit_card_selected else R.drawable.bg_kit_card
            )
            ClickUtils.applySingleDebouncing(binding.root) {
                this@DeviceKitAdapter.selected = kit
                onSelect(kit)
            }
        }
    }
}
