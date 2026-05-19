package com.example.aiassistant.pomodoro

import android.os.CountDownTimer

/**
 * 番茄钟计时器引擎
 * 管理专注/休息/长休息的状态切换和倒计时
 */
class PomodoroTimer(private val listener: TimerListener) {

    interface TimerListener {
        fun onTick(remainingMillis: Long, totalMillis: Long)
        /** @param isSkipped true 表示用户主动跳过，false 表示自然结束 */
        fun onPhaseComplete(state: TimerState, isSkipped: Boolean)
        fun onStateChanged(newState: TimerState)
    }

    var state: TimerState = TimerState.IDLE
        private set

    var config = PomodoroConfig()
        private set

    var completedTomatoes: Int = 0
        private set

    private var countDownTimer: CountDownTimer? = null
    private var remainingMillis: Long = 0
    private var totalMillis: Long = 0
    private var pausedRemainingMillis: Long = 0
    private var pausedFromState: TimerState = TimerState.IDLE

    fun configure(config: PomodoroConfig) {
        this.config = config
    }

    fun startFocus() {
        cancelTimer()
        totalMillis = config.focusMinutes.toLong() * 60 * 1000
        remainingMillis = totalMillis
        state = TimerState.FOCUS
        listener.onStateChanged(state)
        startCountDown()
    }

    fun startShortBreak() {
        cancelTimer()
        totalMillis = config.shortBreakMinutes.toLong() * 60 * 1000
        remainingMillis = totalMillis
        state = TimerState.SHORT_BREAK
        listener.onStateChanged(state)
        startCountDown()
    }

    fun startLongBreak() {
        cancelTimer()
        totalMillis = config.longBreakMinutes.toLong() * 60 * 1000
        remainingMillis = totalMillis
        state = TimerState.LONG_BREAK
        listener.onStateChanged(state)
        startCountDown()
    }

    fun pause() {
        if (state == TimerState.FOCUS || state == TimerState.SHORT_BREAK || state == TimerState.LONG_BREAK) {
            cancelTimer()
            pausedRemainingMillis = remainingMillis
            pausedFromState = state
            state = TimerState.PAUSED
            listener.onStateChanged(state)
        }
    }

    fun resume() {
        if (state == TimerState.PAUSED) {
            remainingMillis = pausedRemainingMillis
            state = pausedFromState
            listener.onStateChanged(state)
            startCountDown()
        }
    }

    fun skip() {
        cancelTimer()
        onPhaseEnd(skipped = true)
    }

    fun stop() {
        cancelTimer()
        state = TimerState.IDLE
        remainingMillis = 0
        completedTomatoes = 0
        listener.onStateChanged(state)
    }

    fun getRemainingMillis(): Long = remainingMillis

    fun getTotalMillis(): Long = totalMillis

    fun getElapsedMillis(): Long = totalMillis - remainingMillis

    fun isRunning(): Boolean = state == TimerState.FOCUS || state == TimerState.SHORT_BREAK || state == TimerState.LONG_BREAK

    fun pausedFromStateOrdinal(): Int = pausedFromState.ordinal

    /**
     * 恢复之前正在运行的计时器状态（用于旋转屏幕后重建）
     */
    fun restoreRunning(
        savedState: TimerState,
        remainingMs: Long,
        totalMs: Long,
        savedTomatoes: Int,
        savedPausedFrom: TimerState = TimerState.FOCUS
    ) {
        cancelTimer()
        state = savedState
        remainingMillis = remainingMs
        totalMillis = totalMs
        completedTomatoes = savedTomatoes

        when (savedState) {
            TimerState.FOCUS, TimerState.SHORT_BREAK, TimerState.LONG_BREAK -> {
                listener.onStateChanged(state)
                startCountDown()
            }
            TimerState.PAUSED -> {
                pausedRemainingMillis = remainingMs
                pausedFromState = savedPausedFrom
                listener.onStateChanged(state)
            }
            else -> {
                state = TimerState.IDLE
                listener.onStateChanged(state)
            }
        }
    }

    // ── 内部 ──

    private fun startCountDown() {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(remainingMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillis = millisUntilFinished
                listener.onTick(remainingMillis, totalMillis)
            }

            override fun onFinish() {
                remainingMillis = 0
                onPhaseEnd(skipped = false)
            }
        }.start()
    }

    private fun onPhaseEnd(skipped: Boolean) {
        val completedState = when (state) {
            TimerState.PAUSED -> pausedFromState
            else -> state
        }

        // 只有专注阶段自然完成才算番茄；跳过不算
        if (completedState == TimerState.FOCUS && !skipped) {
            completedTomatoes++
        }
        listener.onPhaseComplete(completedState, skipped)

        // 自动切换到下一阶段
        when (completedState) {
            TimerState.FOCUS -> {
                if (completedTomatoes > 0 && completedTomatoes % config.longBreakInterval == 0) {
                    startLongBreak()
                } else if (config.autoStartBreak) {
                    startShortBreak()
                } else {
                    state = TimerState.IDLE
                    listener.onStateChanged(state)
                }
            }
            TimerState.SHORT_BREAK, TimerState.LONG_BREAK -> {
                if (config.autoStartFocus) {
                    startFocus()
                } else {
                    state = TimerState.IDLE
                    listener.onStateChanged(state)
                }
            }
            else -> {
                state = TimerState.IDLE
                listener.onStateChanged(state)
            }
        }
    }

    private fun cancelTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
    }
}
