package com.example.aiassistant.pomodoro

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R
import java.text.SimpleDateFormat
import java.util.*

class PomodoroStatsActivity : AppCompatActivity() {

    private lateinit var tabStats: TextView
    private lateinit var tabHistory: TextView
    private lateinit var contentFrame: ViewGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pomodoro_stats)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        tabStats = findViewById(R.id.tab_stats)
        tabHistory = findViewById(R.id.tab_history)
        contentFrame = findViewById(R.id.content_frame)

        tabStats.setOnClickListener { showStats() }
        tabHistory.setOnClickListener { showHistory() }

        showStats()
    }

    private fun showStats() {
        tabStats.setBackgroundResource(R.drawable.bg_pomodoro_button)
        tabStats.setTextColor(Color.WHITE)
        tabHistory.setBackgroundResource(R.drawable.bg_pomodoro_button_secondary)
        tabHistory.setTextColor(resources.getColor(R.color.text_secondary, null))

        contentFrame.removeAllViews()
        val view = LayoutInflater.from(this).inflate(R.layout.fragment_pomodoro_stats, contentFrame, false)
        contentFrame.addView(view)
        setupStatsView(view)
    }

    private fun showHistory() {
        tabHistory.setBackgroundResource(R.drawable.bg_pomodoro_button)
        tabHistory.setTextColor(Color.WHITE)
        tabStats.setBackgroundResource(R.drawable.bg_pomodoro_button_secondary)
        tabStats.setTextColor(resources.getColor(R.color.text_secondary, null))

        contentFrame.removeAllViews()
        val view = LayoutInflater.from(this).inflate(R.layout.fragment_pomodoro_history, contentFrame, false)
        contentFrame.addView(view)
        setupHistoryView(view)
    }

    private fun setupStatsView(view: View) {
        val statsWeekTime = view.findViewById<TextView>(R.id.stats_week_time)
        val statsWeekCount = view.findViewById<TextView>(R.id.stats_week_count)
        val statsWeekAvg = view.findViewById<TextView>(R.id.stats_week_avg)
        val layoutChart = view.findViewById<LinearLayout>(R.id.layout_chart)
        val layoutTags = view.findViewById<LinearLayout>(R.id.layout_tags)

        // 本周统计
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val offset = if (dow == Calendar.SUNDAY) -6 else -(dow - Calendar.MONDAY)
        cal.add(Calendar.DAY_OF_MONTH, offset)
        val weekStartMs = cal.timeInMillis

        cal.add(Calendar.DAY_OF_MONTH, 7)
        val weekEndMs = cal.timeInMillis

        val weekStats = PomodoroManager.getStatsByDateRange(weekStartMs, weekEndMs)
        val weekTimeH = weekStats.totalFocusMinutes / 60
        val weekTimeM = weekStats.totalFocusMinutes % 60
        statsWeekTime.text = if (weekTimeH > 0) "${weekTimeH}h${weekTimeM}m" else "${weekTimeM}m"
        statsWeekCount.text = "${weekStats.completedCount}"
        val daysPassed = ((System.currentTimeMillis() - weekStartMs) / (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
        statsWeekAvg.text = String.format("%.1f", weekStats.completedCount.toFloat() / daysPassed)

        // 柱状图
        val dailyStats = PomodoroManager.getDailyStatsForWeek()
        val maxCount = dailyStats.maxOfOrNull { it.tomatoCount }?.coerceAtLeast(1) ?: 1
        layoutChart.removeAllViews()
        for (stat in dailyStats) {
            val barContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }

            val barHeight = if (maxCount > 0) (stat.tomatoCount.toFloat() / maxCount * 120).toInt() else 0
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(24.dp, barHeight.dp)
                setBackgroundColor(if (stat.tomatoCount > 0) Color.parseColor("#3B82F6") else Color.parseColor("#E5E7EB"))
            }
            barContainer.addView(bar)
            layoutChart.addView(barContainer)
        }

        // 标签分布
        val tagDist = PomodoroManager.getTagDistribution(weekStartMs, weekEndMs)
        val totalTags = tagDist.sumOf { it.second }.coerceAtLeast(1)
        // 清除已有的标签条目（保留标题）
        while (layoutTags.childCount > 1) {
            layoutTags.removeViewAt(layoutTags.childCount - 1)
        }
        val colors = listOf("#3B82F6", "#10B981", "#F59E0B", "#EF4444", "#8B5CF6", "#EC4899")
        for ((index, tagPair) in tagDist.withIndex()) {
            val (tag, count) = tagPair
            val pct = count * 100 / totalTags
            val tagRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.dp }
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            header.addView(TextView(this@PomodoroStatsActivity).apply {
                text = tag
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_primary, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(TextView(this@PomodoroStatsActivity).apply {
                text = "$pct%"
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_secondary, null))
            })
            tagRow.addView(header)

            // Progress bar background with fill inside
            val barBg = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 6.dp
                ).apply { topMargin = 4.dp }
                setBackgroundResource(R.drawable.bg_noise_chip)
                clipChildren = false
            }
            val fillWidth = (resources.displayMetrics.widthPixels * 0.82f * pct / 100f).toInt()
            val barFill = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(fillWidth.coerceAtLeast(1), 6.dp)
                setBackgroundColor(Color.parseColor(colors[index % colors.size]))
            }
            barBg.addView(barFill)
            tagRow.addView(barBg)

            layoutTags.addView(tagRow)
        }

        if (tagDist.isEmpty()) {
            layoutTags.addView(TextView(this).apply {
                text = "暂无数据"
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_tertiary, null))
                setPadding(0, 8.dp, 0, 0)
            })
        }
    }

    private fun setupHistoryView(view: View) {
        val rvHistory = view.findViewById<RecyclerView>(R.id.rv_history)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)

        val sessions = PomodoroManager.getRecentSessions(100)
        if (sessions.isEmpty()) {
            rvHistory.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            return
        }

        rvHistory.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        rvHistory.layoutManager = LinearLayoutManager(this)

        // 按日期分组
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val displayFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINESE)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val grouped = sessions.groupBy { dateFormat.format(Date(it.startedAt)) }

        val groupItems = grouped.map { (dateStr, daySessions) ->
            val date = dateFormat.parse(dateStr)
            val displayDate = if (date != null) displayFormat.format(date) else dateStr
            val totalMin = daySessions.filter { it.isCompleted }.sumOf { it.durationMinutes }
            val completedCount = daySessions.count { it.isCompleted }
            HistoryGroup(displayDate, "${completedCount}个番茄 · ${totalMin}分钟", daySessions.map {
                HistoryItem(
                    it.taskTitle.ifEmpty { "专注学习" },
                    timeFormat.format(Date(it.startedAt)),
                    "${it.durationMinutes}分钟",
                    it.tag,
                    it.isCompleted
                )
            })
        }

        rvHistory.adapter = HistoryGroupAdapter(groupItems)
    }

    data class HistoryGroup(val date: String, val stats: String, val items: List<HistoryItem>)
    data class HistoryItem(val title: String, val time: String, val duration: String, val tag: String, val completed: Boolean)

    inner class HistoryGroupAdapter(private val groups: List<HistoryGroup>) :
        RecyclerView.Adapter<HistoryGroupAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate: TextView = view.findViewById(R.id.tv_date)
            val tvDateStats: TextView = view.findViewById(R.id.tv_date_stats)
            val layoutItems: LinearLayout = view.findViewById(R.id.layout_items)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pomodoro_history_group, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val group = groups[position]
            holder.tvDate.text = group.date
            holder.tvDateStats.text = group.stats
            holder.layoutItems.removeAllViews()

            for (item in group.items) {
                val itemView = LayoutInflater.from(holder.itemView.context)
                    .inflate(R.layout.item_pomodoro_history_item, holder.layoutItems, false)
                itemView.findViewById<TextView>(R.id.tv_title).text = item.title
                itemView.findViewById<TextView>(R.id.tv_time).text = item.time
                itemView.findViewById<TextView>(R.id.tv_duration).text = item.duration
                itemView.findViewById<TextView>(R.id.tv_tag).apply {
                    text = item.tag
                    visibility = if (item.tag.isEmpty()) View.GONE else View.VISIBLE
                }
                val dot = itemView.findViewById<View>(R.id.status_dot)
                dot.setBackgroundColor(if (item.completed) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
                holder.layoutItems.addView(itemView)
            }
        }

        override fun getItemCount() = groups.size
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
