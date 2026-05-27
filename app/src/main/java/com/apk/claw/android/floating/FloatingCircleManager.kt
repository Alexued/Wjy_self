package com.apk.claw.android.floating

import android.app.Application
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.apk.claw.android.R
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.utils.KVUtils
import com.blankj.utilcode.util.BarUtils
import com.blankj.utilcode.util.ThreadUtils
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import com.lzf.easyfloat.interfaces.OnFloatCallbacks
import com.lzf.easyfloat.utils.DisplayUtils

object FloatingCircleManager {

    private const val FLOAT_TAG = "circle_float"
    private const val KEY_FLOAT_X = "floating_circle_x"
    private const val KEY_FLOAT_Y = "floating_circle_y"
    private const val AUTO_RESET_DELAY_MS = 5000L
    private const val TASK_NOTIFY_DURATION_MS = 3000L
    private const val MAX_STEPS_DISPLAY = 5
    private const val MAX_DETAIL_LENGTH = 96

    enum class State {
        IDLE,
        TASK_NOTIFY,
        RUNNING,
        STEPS_EXPANDED,
        SUCCESS,
        ERROR
    }

    data class StepInfo(
        val round: Int,
        val toolName: String,
        val status: StepStatus,
        val detail: String = ""
    )

    enum class StepStatus {
        RUNNING,
        SUCCESS,
        FAILED
    }

    private var isShowing = false
    private var currentState: State = State.IDLE
    private var currentRound: Int = 0
    private var currentChannel: Channel? = null
    private val stepHistory = mutableListOf<StepInfo>()
    private var currentActionText: String = ""
    private var stepsCollapsedByUser = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoResetRunnable: Runnable? = null
    private var notifyCollapseRunnable: Runnable? = null
    private var pendingTaskText: String = ""

    private var appRef: Application? = null
    private var circleWidthPx: Int = -1
    private var circleHeightPx: Int = -1

    fun show(
        application: Application,
        x: Int? = null,
        y: Int? = null
    ) {
        if (isShowing) return
        appRef = application

        val screenHeight = DisplayUtils.getScreenHeight(application)
        val savedX = getSavedX() ?: x ?: 0
        val savedY = getSavedY() ?: y ?: screenHeight / 2

        EasyFloat.with(application)
            .setLayout(R.layout.layout_floating_circle)
            .setShowPattern(ShowPattern.ALL_TIME)
            .setSidePattern(SidePattern.DEFAULT)
            .setGravity(android.view.Gravity.START or android.view.Gravity.TOP, savedX, savedY)
            .setDragEnable(true)
            .hasEditText(false)
            .setTag(FLOAT_TAG)
            .registerCallbacks(object : OnFloatCallbacks {
                override fun createdResult(isCreated: Boolean, msg: String?, view: View?) {
                    view?.findViewById<View>(R.id.floatRoot)?.let { root ->
                        if (circleWidthPx <= 0) {
                            circleWidthPx = root.layoutParams?.width ?: -1
                        }
                        if (circleHeightPx <= 0) {
                            circleHeightPx = root.layoutParams?.height ?: -1
                        }
                    }
                    view?.setOnClickListener {
                        handleFloatClick()
                    }
                    view?.findViewById<ImageView>(R.id.ivStepsClose)?.setOnClickListener {
                        collapseStepsPanel()
                    }
                    updateStateView(view, currentState)
                    view?.post { ensureFloatInBounds(view) }
                }

                override fun dismiss() {
                    isShowing = false
                }

                override fun drag(view: View, event: MotionEvent) = Unit

                override fun dragEnd(view: View) {
                    ensureFloatInBounds(view)
                }

                override fun hide(view: View) {
                    isShowing = false
                }

                override fun show(view: View) {
                    isShowing = true
                }

                override fun touchEvent(view: View, event: MotionEvent) = Unit
            })
            .show()
    }

    fun hide() {
        if (isShowing) {
            EasyFloat.dismiss(FLOAT_TAG)
            isShowing = false
        }
    }

    fun isShowing(): Boolean = isShowing

    fun setIdleState() {
        ThreadUtils.runOnUiThread {
            setState(State.IDLE)
        }
    }

    fun showTaskNotify(taskText: String, channel: Channel) {
        ThreadUtils.runOnUiThread {
            pendingTaskText = taskText
            currentChannel = channel
            currentRound = 0
            currentActionText = appString(R.string.floating_step_waiting)
            stepHistory.clear()
            stepsCollapsedByUser = false
            cancelNotifyCollapse()
            setState(State.TASK_NOTIFY)
            notifyCollapseRunnable = Runnable {
                setState(State.STEPS_EXPANDED)
            }
            mainHandler.postDelayed(notifyCollapseRunnable!!, TASK_NOTIFY_DURATION_MS)
        }
    }

    fun setRunningState(round: Int, channel: Channel) {
        ThreadUtils.runOnUiThread {
            currentRound = round
            currentChannel = channel
            currentActionText = appString(R.string.floating_step_thinking)
            if (currentState == State.TASK_NOTIFY) {
                refreshStepsIfVisible()
                return@runOnUiThread
            }
            setState(if (stepsCollapsedByUser) State.RUNNING else State.STEPS_EXPANDED)
        }
    }

    fun setSuccessState() {
        ThreadUtils.runOnUiThread {
            setState(State.SUCCESS)
            scheduleAutoReset()
        }
    }

    fun setErrorState() {
        ThreadUtils.runOnUiThread {
            setState(State.ERROR)
            scheduleAutoReset()
        }
    }

    fun addStep(round: Int, toolName: String, status: StepStatus, detail: String = "") {
        ThreadUtils.runOnUiThread {
            currentRound = round
            currentActionText = appString(R.string.floating_step_tool_call, toolName)
            stepHistory.add(StepInfo(round, toolName, status, detail))
            if (stepHistory.size > MAX_STEPS_DISPLAY) {
                stepHistory.removeAt(0)
            }
            refreshStepsIfVisible()
        }
    }

    fun updateLastStep(status: StepStatus, detail: String = "") {
        ThreadUtils.runOnUiThread {
            if (stepHistory.isEmpty()) return@runOnUiThread

            val lastIndex = stepHistory.size - 1
            val lastStep = stepHistory[lastIndex]
            stepHistory[lastIndex] = lastStep.copy(status = status, detail = compactDetail(detail))
            currentActionText = when (status) {
                StepStatus.RUNNING -> appString(R.string.floating_step_tool_call, lastStep.toolName)
                StepStatus.SUCCESS -> appString(R.string.floating_step_tool_done, lastStep.toolName)
                StepStatus.FAILED -> appString(R.string.floating_step_tool_failed, lastStep.toolName)
            }
            refreshStepsIfVisible()
        }
    }

    @Deprecated("Use externalClickListener instead", ReplaceWith("externalClickListener"))
    var onFloatClick: () -> Unit
        get() = externalClickListener ?: {}
        set(value) {
            externalClickListener = value
        }

    var externalClickListener: (() -> Unit)? = null

    private fun setState(state: State) {
        currentState = state
        EasyFloat.getFloatView(FLOAT_TAG)?.let { updateStateView(it, state) }
    }

    private fun updateStateView(view: View?, state: State) {
        if (view == null) return

        val cardIdle = view.findViewById<View>(R.id.cardIdle)
        val cardTaskNotify = view.findViewById<View>(R.id.cardTaskNotify)
        val cardRunning = view.findViewById<View>(R.id.cardRunning)
        val cardSuccess = view.findViewById<View>(R.id.cardSuccess)
        val cardError = view.findViewById<View>(R.id.cardError)
        val cardSteps = view.findViewById<View>(R.id.cardSteps)

        cardIdle?.visibility = View.GONE
        cardTaskNotify?.visibility = View.GONE
        cardRunning?.visibility = View.GONE
        cardSuccess?.visibility = View.GONE
        cardError?.visibility = View.GONE
        cardSteps?.visibility = View.GONE

        cancelAutoReset()

        when (state) {
            State.IDLE -> {
                cardIdle?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth())
                stepHistory.clear()
                currentActionText = ""
                stepsCollapsedByUser = false
            }

            State.TASK_NOTIFY -> {
                cardTaskNotify?.visibility = View.VISIBLE
                val app = appRef ?: return
                val displayText = pendingTaskText.ellipsize(40)
                view.findViewById<TextView>(R.id.tvTaskNotify)?.text =
                    app.getString(R.string.floating_task_received, displayText)
                view.findViewById<ImageView>(R.id.ivNotifyChannelLogo)
                    ?.setImageResource(getChannelIcon(currentChannel))
                setFloatRootWidth(view, WindowManager.LayoutParams.WRAP_CONTENT)
            }

            State.RUNNING -> {
                cancelNotifyCollapse()
                cardRunning?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth())
                view.findViewById<TextView>(R.id.tvRound)?.text = currentRound.toString()
                view.findViewById<ImageView>(R.id.ivChannelLogo)
                    ?.setImageResource(getChannelIcon(currentChannel))
            }

            State.STEPS_EXPANDED -> {
                cancelNotifyCollapse()
                cardSteps?.visibility = View.VISIBLE
                setFloatRootWidth(view, WindowManager.LayoutParams.WRAP_CONTENT)
                updateStepsPanel(view)
            }

            State.SUCCESS -> {
                cancelNotifyCollapse()
                cardSuccess?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth())
            }

            State.ERROR -> {
                cancelNotifyCollapse()
                cardError?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth())
            }
        }
    }

    @DrawableRes
    private fun getChannelIcon(channel: Channel?): Int {
        return when (channel) {
            Channel.DINGTALK -> R.drawable.ic_channel_dingtalk
            Channel.FEISHU -> R.drawable.ic_channel_feishu
            Channel.QQ -> R.drawable.ic_channel_qq
            Channel.DISCORD -> R.drawable.ic_channel_discord
            Channel.TELEGRAM -> R.drawable.ic_channel_telegram
            Channel.WECHAT -> R.drawable.ic_channel_wechat
            else -> R.drawable.ic_launcher
        }
    }

    private fun scheduleAutoReset() {
        cancelAutoReset()
        autoResetRunnable = Runnable {
            setIdleState()
        }
        mainHandler.postDelayed(autoResetRunnable!!, AUTO_RESET_DELAY_MS)
    }

    private fun cancelAutoReset() {
        autoResetRunnable?.let {
            mainHandler.removeCallbacks(it)
            autoResetRunnable = null
        }
    }

    private fun cancelNotifyCollapse() {
        notifyCollapseRunnable?.let {
            mainHandler.removeCallbacks(it)
            notifyCollapseRunnable = null
        }
    }

    private fun ensureFloatInBounds(view: View) {
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val navBarHeight = getNavigationBarHeight()

        var wmParams: WindowManager.LayoutParams? = null
        var wmView: View? = view
        while (wmView != null) {
            val lp = wmView.layoutParams
            if (lp is WindowManager.LayoutParams) {
                wmParams = lp
                break
            }
            wmView = wmView.parent as? View
        }

        if (wmParams != null) {
            val floatHeight = (wmView ?: view).height
            val floatWidth = (wmView ?: view).width
            val maxX = (screenWidth - floatWidth).coerceAtLeast(0)
            val maxY = (screenHeight - floatHeight - navBarHeight - 50).coerceAtLeast(0)
            val clampedX = wmParams.x.coerceIn(0, maxX)
            val clampedY = wmParams.y.coerceIn(0, maxY)
            if (clampedX != wmParams.x || clampedY != wmParams.y) {
                EasyFloat.updateFloat(FLOAT_TAG, clampedX, clampedY)
            }
            savePosition(clampedX, clampedY)
            return
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewBottom = location[1] + view.height
        if (viewBottom > screenHeight - navBarHeight || location[1] < 0) {
            val safeY = screenHeight / 3
            EasyFloat.updateFloat(FLOAT_TAG, location[0].coerceIn(0, screenWidth), safeY)
            savePosition(location[0].coerceIn(0, screenWidth), safeY)
        } else {
            savePosition(location[0], location[1])
        }
    }

    private fun getNavigationBarHeight(): Int = BarUtils.getNavBarHeight()

    private fun setFloatRootWidth(view: View, widthPx: Int) {
        val root = view.findViewById<View>(R.id.floatRoot) ?: return
        val lp = root.layoutParams ?: return
        val heightPx = if (widthPx == WindowManager.LayoutParams.WRAP_CONTENT) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            getCircleHeight()
        }
        if (lp.width != widthPx || lp.height != heightPx) {
            lp.width = widthPx
            lp.height = heightPx
            root.layoutParams = lp
        }
    }

    private fun getCircleWidth(): Int {
        return if (circleWidthPx > 0) circleWidthPx else WindowManager.LayoutParams.WRAP_CONTENT
    }

    private fun getCircleHeight(): Int {
        return if (circleHeightPx > 0) circleHeightPx else WindowManager.LayoutParams.WRAP_CONTENT
    }

    private fun savePosition(x: Int, y: Int) {
        KVUtils.putInt(KEY_FLOAT_X, x)
        KVUtils.putInt(KEY_FLOAT_Y, y)
    }

    private fun getSavedX(): Int? {
        val x = KVUtils.getInt(KEY_FLOAT_X, -1)
        return if (x == -1) null else x
    }

    private fun getSavedY(): Int? {
        val y = KVUtils.getInt(KEY_FLOAT_Y, -1)
        return if (y == -1) null else y
    }

    private fun handleFloatClick() {
        when (currentState) {
            State.RUNNING -> expandStepsPanel()
            State.STEPS_EXPANDED -> collapseStepsPanel()
            else -> externalClickListener?.invoke()
        }
    }

    private fun expandStepsPanel() {
        stepsCollapsedByUser = false
        setState(State.STEPS_EXPANDED)
    }

    private fun collapseStepsPanel() {
        stepsCollapsedByUser = true
        setState(State.RUNNING)
    }

    private fun refreshStepsIfVisible() {
        if (currentState == State.STEPS_EXPANDED || currentState == State.TASK_NOTIFY) {
            EasyFloat.getFloatView(FLOAT_TAG)?.let { updateStepsPanel(it) }
        }
    }

    private fun updateStepsPanel(view: View) {
        val app = appRef ?: return
        view.findViewById<TextView>(R.id.tvStepsRound)?.text =
            app.getString(R.string.floating_steps_round, currentRound)
        view.findViewById<TextView>(R.id.tvStepsCurrentAction)?.text =
            currentActionText.ifEmpty { app.getString(R.string.floating_step_waiting) }

        val stepsList = view.findViewById<LinearLayout>(R.id.llStepsList) ?: return
        stepsList.removeAllViews()

        if (stepHistory.isEmpty()) {
            stepsList.addView(
                TextView(app).apply {
                    text = app.getString(R.string.floating_no_steps)
                    setTextColor(app.getColor(R.color.colorBrandOnPrimary))
                    alpha = 0.7f
                    textSize = 10f
                }
            )
            return
        }

        for (step in stepHistory.asReversed()) {
            stepsList.addView(createStepItemView(step, app))
        }
    }

    private fun createStepItemView(step: StepInfo, app: Application): View {
        val layout = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 6
            }
        }

        layout.addView(
            TextView(app).apply {
                text = step.toolName
                setTextColor(app.getColor(R.color.colorBrandOnPrimary))
                textSize = 11f
            }
        )

        val statusText = when (step.status) {
            StepStatus.RUNNING -> app.getString(R.string.floating_step_tool_call, step.toolName)
            StepStatus.SUCCESS -> {
                if (step.detail.isNotEmpty()) {
                    app.getString(R.string.floating_step_tool_result, step.detail)
                } else {
                    app.getString(R.string.channel_msg_tool_success)
                }
            }
            StepStatus.FAILED -> app.getString(
                R.string.floating_step_tool_result,
                step.detail.ifEmpty { app.getString(R.string.channel_msg_tool_failure) }
            )
        }

        layout.addView(
            TextView(app).apply {
                text = statusText
                setTextColor(app.getColor(R.color.colorBrandOnPrimary))
                alpha = 0.7f
                textSize = 9f
                maxLines = 2
            }
        )

        return layout
    }

    private fun appString(resId: Int, vararg args: Any): String {
        val app = appRef ?: return ""
        return if (args.isEmpty()) app.getString(resId) else app.getString(resId, *args)
    }

    private fun compactDetail(detail: String): String {
        return detail.trim().replace('\n', ' ').ellipsize(MAX_DETAIL_LENGTH)
    }

    private fun String.ellipsize(maxLength: Int): String {
        return if (length > maxLength) take(maxLength) + "..." else this
    }
}
