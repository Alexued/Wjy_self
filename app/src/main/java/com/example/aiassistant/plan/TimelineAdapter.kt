package com.example.aiassistant.plan

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R

class TimelineAdapter(
    private val onToggle: (PlanTask) -> Unit,
    private val onDelete: (PlanTask) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TASK = 1
    }

    private val items = mutableListOf<TimelineItem>()
    private val expandedDates = mutableSetOf<String>()

    fun setData(grouped: List<Pair<String, List<PlanTask>>>) {
        items.clear()
        for ((date, tasks) in grouped) {
            // 默认展开今天和有未完成任务的日期
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            val hasPending = tasks.any { !it.isCompleted }
            if (expandedDates.isEmpty()) {
                // 首次加载：展开今天和有未完成任务的日期
                if (date == today || hasPending) expandedDates.add(date)
            }
            val isExpanded = expandedDates.contains(date)
            items.add(TimelineItem.Header(date, tasks.size, tasks.count { it.isCompleted }, isExpanded))
            if (isExpanded) {
                for (task in tasks) {
                    items.add(TimelineItem.Task(task))
                }
            }
        }
        notifyDataSetChanged()
    }

    fun getAllItems(): List<TimelineItem> = items

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is TimelineItem.Header -> TYPE_HEADER
            is TimelineItem.Task -> TYPE_TASK
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_timeline_date_header, parent, false))
            else -> TaskVH(inflater.inflate(R.layout.item_plan_task, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TimelineItem.Header -> (holder as HeaderVH).bind(item)
            is TimelineItem.Task -> (holder as TaskVH).bind(item.task)
        }
    }

    override fun getItemCount() = items.size

    // ── Header VH ────────────────────────────────────────────────────

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvArrow: TextView = view.findViewById(R.id.tv_arrow)
        private val tvDate: TextView = view.findViewById(R.id.tv_date)
        private val tvStats: TextView = view.findViewById(R.id.tv_stats)

        fun bind(header: TimelineItem.Header) {
            tvArrow.text = if (header.isExpanded) "▼" else "▶"

            // 格式化日期显示
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val date = sdf.parse(header.date)
                val today = sdf.format(java.util.Date())
                val displayFmt = java.text.SimpleDateFormat("M月d日 EEEE", java.util.Locale.CHINA)
                tvDate.text = when (header.date) {
                    today -> "今天"
                    else -> displayFmt.format(date!!)
                }
            } catch (_: Exception) {
                tvDate.text = header.date
            }

            tvStats.text = "${header.doneCount}/${header.totalCount}"

            // 颜色：有未完成任务时日期高亮
            if (header.doneCount < header.totalCount) {
                tvDate.setTextColor(0xFF1F2937.toInt())
            } else {
                tvDate.setTextColor(0xFF9CA3AF.toInt())
            }

            itemView.setOnClickListener {
                if (header.isExpanded) {
                    expandedDates.remove(header.date)
                } else {
                    expandedDates.add(header.date)
                }
                // 重新构建数据
                val currentData = getCurrentGroupedData()
                setData(currentData)
            }
        }
    }

    // ── Task VH ──────────────────────────────────────────────────────

    inner class TaskVH(view: View) : RecyclerView.ViewHolder(view) {
        private val cbDone: CheckBox = view.findViewById(R.id.cb_done)
        private val tvTitle: TextView = view.findViewById(R.id.tv_title)
        private val tvDesc: TextView = view.findViewById(R.id.tv_description)
        private val tvPriority: TextView = view.findViewById(R.id.tv_priority)
        private val btnDelete: TextView = view.findViewById(R.id.btn_delete)

        fun bind(task: PlanTask) {
            cbDone.setOnCheckedChangeListener(null)
            cbDone.isChecked = task.isCompleted
            cbDone.setOnCheckedChangeListener { _, _ -> onToggle(task) }

            tvTitle.text = task.title
            if (task.isCompleted) {
                tvTitle.paintFlags = tvTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                tvTitle.alpha = 0.5f
            } else {
                tvTitle.paintFlags = tvTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                tvTitle.alpha = 1.0f
            }

            if (task.description.isNotBlank()) {
                tvDesc.text = task.description
                tvDesc.visibility = View.VISIBLE
            } else {
                tvDesc.visibility = View.GONE
            }

            when (task.priority) {
                PlanTask.PRIORITY_IMPORTANT -> {
                    tvPriority.text = "重要"
                    tvPriority.setTextColor(0xFFF59E0B.toInt())
                    tvPriority.setBackgroundResource(R.drawable.bg_tag_orange)
                    tvPriority.visibility = View.VISIBLE
                }
                PlanTask.PRIORITY_URGENT -> {
                    tvPriority.text = "紧急"
                    tvPriority.setTextColor(0xFFEF4444.toInt())
                    tvPriority.setBackgroundResource(R.drawable.bg_tag_red)
                    tvPriority.visibility = View.VISIBLE
                }
                else -> tvPriority.visibility = View.GONE
            }

            btnDelete.setOnClickListener { onDelete(task) }
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    /**
     * 从当前 items 中还原分组数据（用于展开/收起后重建）
     */
    private fun getCurrentGroupedData(): List<Pair<String, List<PlanTask>>> {
        val result = mutableListOf<Pair<String, List<PlanTask>>>()
        var currentDate = ""
        val currentTasks = mutableListOf<PlanTask>()

        for (item in items) {
            when (item) {
                is TimelineItem.Header -> {
                    if (currentDate.isNotEmpty()) {
                        result.add(Pair(currentDate, currentTasks.toList()))
                    }
                    currentDate = item.date
                    currentTasks.clear()
                }
                is TimelineItem.Task -> {
                    currentTasks.add(item.task)
                }
            }
        }
        if (currentDate.isNotEmpty()) {
            result.add(Pair(currentDate, currentTasks.toList()))
        }
        return result
    }

    // ── 数据模型 ──────────────────────────────────────────────────────

    sealed class TimelineItem {
        data class Header(
            val date: String,
            val totalCount: Int,
            val doneCount: Int,
            val isExpanded: Boolean
        ) : TimelineItem()

        data class Task(val task: PlanTask) : TimelineItem()
    }
}
