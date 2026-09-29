package com.bingo.smartna.collector.done

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.data.mock.MockDataSource
import com.bingo.smartna.collector.hall.TaskDetailActivity
import com.bingo.smartna.databinding.ActivityJobDoneBinding
import com.blankj.utilcode.util.ClickUtils

class JobDoneActivity : BaseActivity<ActivityJobDoneBinding, BaseViewModel>() {

    override fun inflateBinding() = ActivityJobDoneBinding.inflate(layoutInflater)

    override fun initData() {
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val reward = intent.getDoubleExtra(EXTRA_REWARD, 0.0)
        val frames = intent.getIntExtra(EXTRA_FRAMES, 240)
        binding.tvTaskTitle.text = title
        binding.tvFrames.text = getString(R.string.job_done_frames, frames)
        binding.tvReward.text = getString(R.string.job_done_reward, reward)
        ClickUtils.applySingleDebouncing(binding.btnClose) { finish() }
        ClickUtils.applySingleDebouncing(binding.btnShare) {
            Toast.makeText(this, R.string.job_done_share_toast, Toast.LENGTH_SHORT).show()
        }
        ClickUtils.applySingleDebouncing(binding.btnNext) {
            val next = MockDataSource.allTasks.firstOrNull { it.id == "t_demo" }
                ?: MockDataSource.allTasks.first()
            TaskDetailActivity.start(this, next)
            finish()
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_REWARD = "reward"
        private const val EXTRA_FRAMES = "frames"

        fun start(context: Context, title: String, reward: Double, clipCount: Int) {
            context.startActivity(
                Intent(context, JobDoneActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_REWARD, reward)
                    .putExtra(EXTRA_FRAMES, clipCount * 240)
            )
        }
    }
}
