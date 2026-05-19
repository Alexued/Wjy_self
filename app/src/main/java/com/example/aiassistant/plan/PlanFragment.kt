package com.example.aiassistant.plan

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PlanFragment : Fragment() {

    // 0=日历模式, 1=时间线模式
    private var viewMode = 0
    private var currentYear = 0
    private var currentMonth = 0
    private var currentFilter = 0 // 0=全部, 1=未完成, 2=已完成

    private lateinit var layoutCalendar: LinearLayout
    private lateinit var layoutTimeline: LinearLayout
    private lateinit var btnViewMode: TextView
    private lateinit var tvMonthTitle: TextView
    private lateinit var rvCalendar: RecyclerView
    private lateinit var rvTimeline: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvTaskStats: TextView
    private lateinit var fabAdd: FloatingActionButton

    private lateinit var calendarAdapter: CalendarDayAdapter
    private lateinit var timelineAdapter: TimelineAdapter

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_plan, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cal = Calendar.getInstance()
        currentYear = cal.get(Calendar.YEAR)
        currentMonth = cal.get(Calendar.MONTH) + 1

        layoutCalendar = view.findViewById(R.id.layout_calendar)
        layoutTimeline = view.findViewById(R.id.layout_timeline)
        btnViewMode = view.findViewById(R.id.btn_view_mode)
        tvMonthTitle = view.findViewById(R.id.tv_month_title)
        rvCalendar = view.findViewById(R.id.rv_calendar)
        rvTimeline = view.findViewById(R.id.rv_timeline)
        tvEmpty = view.findViewById(R.id.tv_empty)
        tvTaskStats = view.findViewById(R.id.tv_task_stats)
        fabAdd = view.findViewById(R.id.fab_add_task)

        // 日历适配器
        calendarAdapter = CalendarDayAdapter { day ->
            // 点击日期 → 切换到时间线并筛选该日
            viewMode = 1
            switchView()
            loadTimeline()
        }
        rvCalendar.layoutManager = GridLayoutManager(requireContext(), 7)
        rvCalendar.adapter = calendarAdapter

        // 时间线适配器
        timelineAdapter = TimelineAdapter(
            onToggle = { task ->
                PlanManager.toggleComplete(task.id)
                loadTimeline()
            },
            onDelete = { task ->
                AlertDialog.Builder(requireContext())
                    .setTitle("删除任务")
                    .setMessage("确定删除「${task.title}」？")
                    .setPositiveButton("删除") { _, _ ->
                        PlanManager.deleteTask(task.id)
                        loadTimeline()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
        rvTimeline.layoutManager = LinearLayoutManager(requireContext())
        rvTimeline.adapter = timelineAdapter

        // 月份切换
        view.findViewById<TextView>(R.id.btn_prev_month).setOnClickListener {
            currentMonth--
            if (currentMonth < 1) { currentMonth = 12; currentYear-- }
            loadCalendar()
        }
        view.findViewById<TextView>(R.id.btn_next_month).setOnClickListener {
            currentMonth++
            if (currentMonth > 12) { currentMonth = 1; currentYear++ }
            loadCalendar()
        }

        // 视图切换
        btnViewMode.setOnClickListener {
            viewMode = if (viewMode == 0) 1 else 0
            switchView()
        }

        // 筛选按钮
        view.findViewById<TextView>(R.id.filter_all).setOnClickListener { setFilter(0) }
        view.findViewById<TextView>(R.id.filter_pending).setOnClickListener { setFilter(1) }
        view.findViewById<TextView>(R.id.filter_done).setOnClickListener { setFilter(2) }

        // 添加任务
        fabAdd.setOnClickListener { showAddTaskDialog() }

        // 初始化星期标题行
        initWeekHeader(view)

        switchView()
    }

    override fun onResume() {
        super.onResume()
        if (viewMode == 0) loadCalendar() else loadTimeline()
    }

    // ── 视图切换 ──────────────────────────────────────────────────────

    private fun switchView() {
        if (viewMode == 0) {
            layoutCalendar.visibility = View.VISIBLE
            layoutTimeline.visibility = View.GONE
            btnViewMode.text = "📅 日历"
            loadCalendar()
        } else {
            layoutCalendar.visibility = View.GONE
            layoutTimeline.visibility = View.VISIBLE
            btnViewMode.text = "📊 时间线"
            loadTimeline()
        }
    }

    // ── 筛选 ──────────────────────────────────────────────────────────

    private fun setFilter(filter: Int) {
        currentFilter = filter
        val v = requireView()
        val allBtn = v.findViewById<TextView>(R.id.filter_all)
        val pendingBtn = v.findViewById<TextView>(R.id.filter_pending)
        val doneBtn = v.findViewById<TextView>(R.id.filter_done)

        // 重置样式
        for (btn in listOf(allBtn, pendingBtn, doneBtn)) {
            btn.setTextColor(0xFF6B7280.toInt())
            btn.setBackgroundResource(R.drawable.bg_default_chip)
        }

        // 高亮当前
        val selected = when (filter) {
            1 -> pendingBtn
            2 -> doneBtn
            else -> allBtn
        }
        selected.setTextColor(0xFFFFFFFF.toInt())
        selected.setBackgroundResource(R.drawable.bg_tag_blue)

        loadTimeline()
    }

    // ── 日历 ──────────────────────────────────────────────────────────

    private fun initWeekHeader(view: View) {
        val header = view.findViewById<LinearLayout>(R.id.layout_week_header)
        val days = arrayOf("日", "一", "二", "三", "四", "五", "六")
        for (day in days) {
            val tv = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = day
                textSize = 12f
                setTextColor(0xFF9CA3AF.toInt())
                gravity = android.view.Gravity.CENTER
            }
            header.addView(tv)
        }
    }

    private fun loadCalendar() {
        tvMonthTitle.text = "${currentYear}年${currentMonth}月"

        val stats = PlanManager.getDailyStats(currentYear, currentMonth)

        val cal = Calendar.getInstance()
        cal.set(currentYear, currentMonth - 1, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val days = mutableListOf<CalendarDay>()
        for (i in 0 until firstDayOfWeek) {
            days.add(CalendarDay(0, null))
        }
        val todayStr = dateFormat.format(Date())
        for (d in 1..daysInMonth) {
            val dateStr = String.format("%04d-%02d-%02d", currentYear, currentMonth, d)
            val stat = stats[dateStr]
            days.add(CalendarDay(
                day = d,
                dateStr = dateStr,
                isToday = dateStr == todayStr,
                totalTasks = stat?.first ?: 0,
                doneTasks = stat?.second ?: 0
            ))
        }
        calendarAdapter.setData(days)
    }

    // ── 时间线 ──────────────────────────────────────────────────────

    private fun loadTimeline() {
        val grouped = PlanManager.getGroupedTasks(currentFilter)
        timelineAdapter.setData(grouped)

        // 统计
        var totalTasks = 0
        var totalDone = 0
        for ((_, tasks) in grouped) {
            totalTasks += tasks.size
            totalDone += tasks.count { it.isCompleted }
        }
        tvTaskStats.text = if (totalTasks > 0) "共${totalTasks}条 已完成${totalDone}条" else ""

        tvEmpty.visibility = if (grouped.isEmpty()) View.VISIBLE else View.GONE
        rvTimeline.visibility = if (grouped.isEmpty()) View.GONE else View.VISIBLE
    }

    // ── 添加任务对话框 ────────────────────────────────────────────────

    private fun showAddTaskDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_task, null)
        val etTitle = dialogView.findViewById<EditText>(R.id.et_title)
        val etDesc = dialogView.findViewById<EditText>(R.id.et_description)
        val tvDate = dialogView.findViewById<TextView>(R.id.tv_date)
        val rgPriority = dialogView.findViewById<RadioGroup>(R.id.rg_priority)

        val today = dateFormat.format(Date())
        tvDate.text = today

        tvDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, year, month, day ->
                tvDate.text = String.format("%04d-%02d-%02d", year, month + 1, day)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val title = etTitle.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(requireContext(), "请输入任务标题", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val priority = when (rgPriority.checkedRadioButtonId) {
                    R.id.rb_important -> PlanTask.PRIORITY_IMPORTANT
                    R.id.rb_urgent -> PlanTask.PRIORITY_URGENT
                    else -> PlanTask.PRIORITY_NORMAL
                }
                PlanManager.addTask(PlanTask(
                    title = title,
                    description = etDesc.text.toString().trim(),
                    date = tvDate.text.toString(),
                    priority = priority
                ))
                Toast.makeText(requireContext(), "已添加", Toast.LENGTH_SHORT).show()
                if (viewMode == 0) loadCalendar() else loadTimeline()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 数据类 ────────────────────────────────────────────────────────

    data class CalendarDay(
        val day: Int,
        val dateStr: String?,
        val isToday: Boolean = false,
        val totalTasks: Int = 0,
        val doneTasks: Int = 0
    )

    // ── 日历适配器 ────────────────────────────────────────────────────

    inner class CalendarDayAdapter(
        private val onDayClick: (Int) -> Unit
    ) : RecyclerView.Adapter<CalendarDayAdapter.VH>() {

        private var items = listOf<CalendarDay>()

        fun setData(data: List<CalendarDay>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_calendar_day, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            if (item.day == 0) {
                holder.tvDay.text = ""
                holder.dotIndicator.visibility = View.GONE
                holder.itemView.setOnClickListener(null)
            } else {
                holder.tvDay.text = item.day.toString()

                if (item.isToday) {
                    holder.tvDay.setBackgroundResource(R.drawable.bg_tag_blue)
                    holder.tvDay.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    holder.tvDay.background = null
                    holder.tvDay.setTextColor(0xFF374151.toInt())
                }

                if (item.totalTasks > 0) {
                    holder.dotIndicator.visibility = View.VISIBLE
                    val dotBg = holder.dotIndicator.background as? android.graphics.drawable.GradientDrawable
                    if (item.doneTasks >= item.totalTasks) {
                        dotBg?.setColor(0xFF10B981.toInt())
                    } else {
                        dotBg?.setColor(0xFF2563EB.toInt())
                    }
                } else {
                    holder.dotIndicator.visibility = View.GONE
                }

                holder.itemView.setOnClickListener { onDayClick(item.day) }
            }
        }

        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvDay: TextView = view.findViewById(R.id.tv_day)
            val dotIndicator: View = view.findViewById(R.id.dot_indicator)
        }
    }
}
