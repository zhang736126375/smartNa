package com.bingo.smartna.collector.hall

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bingo.smartna.R
import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.databinding.ItemHallCategoryBinding
import com.blankj.utilcode.util.ClickUtils

data class HallCategoryItem(
    val category: HallCategory,
    val remainCount: Int
)

class HallCategoryAdapter(
    private val onClick: (HallCategory) -> Unit
) : ListAdapter<HallCategoryItem, HallCategoryAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemHallCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class Holder(private val binding: ItemHallCategoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HallCategoryItem, onClick: (HallCategory) -> Unit) {
            val context = binding.root.context
            binding.tvName.setText(
                when (item.category) {
                    HallCategory.LIFE -> R.string.hall_category_life
                    HallCategory.FACTORY -> R.string.hall_category_factory
                }
            )
            binding.tvCount.text = context.getString(R.string.hall_category_count, item.remainCount)
            binding.root.setBackgroundResource(
                when (item.category) {
                    HallCategory.LIFE -> R.drawable.bg_hall_category_life
                    HallCategory.FACTORY -> R.drawable.bg_hall_category_factory
                }
            )
            ClickUtils.applySingleDebouncing(binding.root) { onClick(item.category) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<HallCategoryItem>() {
        override fun areItemsTheSame(oldItem: HallCategoryItem, newItem: HallCategoryItem) =
            oldItem.category == newItem.category

        override fun areContentsTheSame(oldItem: HallCategoryItem, newItem: HallCategoryItem) =
            oldItem == newItem
    }
}
