package com.example.aiassistant.pomodoro

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.R
import com.google.android.material.switchmaterial.SwitchMaterial

class PomodoroSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pomodoro_settings)

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        val etFocusMin = findViewById<EditText>(R.id.et_focus_min)
        val etShortBreak = findViewById<EditText>(R.id.et_short_break)
        val etLongBreak = findViewById<EditText>(R.id.et_long_break)
        val etLongInterval = findViewById<EditText>(R.id.et_long_interval)
        val etDailyTarget = findViewById<EditText>(R.id.et_daily_target)

        val switchAutoBreak = findViewById<SwitchMaterial>(R.id.switch_auto_break)
        val switchAutoFocus = findViewById<SwitchMaterial>(R.id.switch_auto_focus)
        val switchVibration = findViewById<SwitchMaterial>(R.id.switch_vibration)
        val switchKeepScreen = findViewById<SwitchMaterial>(R.id.switch_keep_screen)

        // 加载当前设置
        etFocusMin.setText(AppPreferences.getPomodoroFocusMin(this).toString())
        etShortBreak.setText(AppPreferences.getPomodoroShortBreak(this).toString())
        etLongBreak.setText(AppPreferences.getPomodoroLongBreak(this).toString())
        etLongInterval.setText(AppPreferences.getPomodoroLongInterval(this).toString())
        etDailyTarget.setText(AppPreferences.getPomodoroDailyTarget(this).toString())

        switchAutoBreak.isChecked = AppPreferences.isPomodoroAutoBreak(this)
        switchAutoFocus.isChecked = AppPreferences.isPomodoroAutoFocus(this)
        switchVibration.isChecked = AppPreferences.isPomodoroVibration(this)
        switchKeepScreen.isChecked = AppPreferences.isPomodoroKeepScreen(this)

        // 保存
        findViewById<android.view.View>(R.id.btn_save).setOnClickListener {
            val focusMin = etFocusMin.text.toString().toIntOrNull() ?: 25
            val shortBreak = etShortBreak.text.toString().toIntOrNull() ?: 5
            val longBreak = etLongBreak.text.toString().toIntOrNull() ?: 15
            val longInterval = etLongInterval.text.toString().toIntOrNull() ?: 4
            val dailyTarget = etDailyTarget.text.toString().toIntOrNull() ?: 8

            if (focusMin < 1 || focusMin > 180 ||
                shortBreak < 1 || shortBreak > 60 ||
                longBreak < 1 || longBreak > 120 ||
                longInterval < 1 || longInterval > 10 ||
                dailyTarget < 1 || dailyTarget > 30) {
                Toast.makeText(this, "数值超出范围（专注1-180，休息1-60/120，间隔1-10，目标1-30）", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            AppPreferences.setPomodoroFocusMin(this, focusMin)
            AppPreferences.setPomodoroShortBreak(this, shortBreak)
            AppPreferences.setPomodoroLongBreak(this, longBreak)
            AppPreferences.setPomodoroLongInterval(this, longInterval)
            AppPreferences.setPomodoroDailyTarget(this, dailyTarget)

            AppPreferences.setPomodoroAutoBreak(this, switchAutoBreak.isChecked)
            AppPreferences.setPomodoroAutoFocus(this, switchAutoFocus.isChecked)
            AppPreferences.setPomodoroVibration(this, switchVibration.isChecked)
            AppPreferences.setPomodoroKeepScreen(this, switchKeepScreen.isChecked)

            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
