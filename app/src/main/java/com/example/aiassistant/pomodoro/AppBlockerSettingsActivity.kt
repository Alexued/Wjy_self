package com.example.aiassistant.pomodoro

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.R

/**
 * 为指定任务配置白名单应用
 * 系统核心应用始终允许，不可取消；用户可额外选择允许的应用
 */
class AppBlockerSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_TITLE = "task_title"

        fun start(context: Context, taskTitle: String) {
            context.startActivity(Intent(context, AppBlockerSettingsActivity::class.java).apply {
                putExtra(EXTRA_TASK_TITLE, taskTitle)
            })
        }
    }

    private data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: Drawable?
    )

    private var allApps = listOf<AppInfo>()
    private var filteredApps = listOf<AppInfo>()
    private var selectedPackages = mutableSetOf<String>()
    private lateinit var taskTitle: String

    private lateinit var adapter: AppAdapter
    private lateinit var tvSelectedCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_blocker_settings)

        taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: ""

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_whitelist).visibility = View.GONE
        findViewById<View>(R.id.btn_blacklist).visibility = View.GONE

        tvSelectedCount = findViewById(R.id.tv_selected_count)
        val tvModeDesc = findViewById<TextView>(R.id.tv_mode_desc)
        val etSearch = findViewById<EditText>(R.id.et_search)
        val rvApps = findViewById<RecyclerView>(R.id.rv_apps)

        val tvTitle = findViewById<TextView>(R.id.tv_title)
        tvTitle?.text = if (taskTitle.isNotEmpty()) "白名单 · $taskTitle" else "白名单设置"

        tvModeDesc.text = "勾选允许使用的应用，未勾选的将在专注时被拦截"

        // 加载该任务的白名单（首次为系统默认，之后为用户实际选择）
        selectedPackages = AppPreferences.getTaskWhitelist(this, taskTitle).toMutableSet()

        allApps = loadInstalledApps()
        filteredApps = allApps

        adapter = AppAdapter()
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                filteredApps = if (query.isEmpty()) allApps
                else allApps.filter {
                    it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
                }
                adapter.notifyDataSetChanged()
            }
        })

        updateSelectedCount()
    }

    override fun onPause() {
        super.onPause()
        AppPreferences.setTaskWhitelist(this, taskTitle, selectedPackages)
    }

    private fun updateSelectedCount() {
        tvSelectedCount.text = "已允许 ${selectedPackages.size} 个应用"
    }

    private fun loadInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val myPackage = packageName

        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val appMap = mutableMapOf<String, AppInfo>()

        try {
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == myPackage) continue
                appMap[pkg] = AppInfo(
                    packageName = pkg,
                    appName = ri.loadLabel(pm).toString(),
                    icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
                )
            }
        } catch (_: Exception) {}

        if (appMap.size < 5) {
            try {
                @Suppress("DEPRECATION")
                for (pkgInfo in pm.getInstalledPackages(0)) {
                    val pkg = pkgInfo.packageName
                    if (pkg == myPackage || appMap.containsKey(pkg)) continue
                    val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: continue
                    appMap[pkg] = AppInfo(
                        packageName = pkg,
                        appName = pkgInfo.applicationInfo?.loadLabel(pm)?.toString() ?: pkg,
                        icon = try { pkgInfo.applicationInfo?.loadIcon(pm) } catch (_: Exception) { null }
                    )
                }
            } catch (_: Exception) {}
        }

        return appMap.values.sortedBy { it.appName.lowercase() }
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.iv_app_icon)
            val tvName: TextView = view.findViewById(R.id.tv_app_name)
            val cbSelect: CheckBox = view.findViewById(R.id.cb_select)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_select, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = filteredApps[position]
            holder.tvName.text = app.appName
            holder.ivIcon.setImageDrawable(app.icon)
            holder.cbSelect.isChecked = selectedPackages.contains(app.packageName)

            holder.itemView.setOnClickListener {
                if (selectedPackages.contains(app.packageName)) {
                    selectedPackages.remove(app.packageName)
                    holder.cbSelect.isChecked = false
                } else {
                    selectedPackages.add(app.packageName)
                    holder.cbSelect.isChecked = true
                }
                updateSelectedCount()
                AppPreferences.setTaskWhitelist(this@AppBlockerSettingsActivity, taskTitle, selectedPackages)
            }
        }

        override fun getItemCount() = filteredApps.size
    }
}
