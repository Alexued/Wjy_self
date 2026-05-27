package com.apk.claw.android.ui.guide

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.KVUtils

class GuideActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        bindSection(
            findViewById(R.id.guideAccessibility),
            R.drawable.ic_accessibility,
            R.string.guide_title_accessibility,
            R.string.guide_desc_accessibility
        )
        bindSection(
            findViewById(R.id.guideNotification),
            R.drawable.ic_notification,
            R.string.guide_title_notification,
            R.string.guide_desc_notification
        )
        bindSection(
            findViewById(R.id.guideOverlay),
            R.drawable.ic_window,
            R.string.guide_title_overlay,
            R.string.guide_desc_overlay
        )
        bindSection(
            findViewById(R.id.guideBattery),
            R.drawable.ic_battery,
            R.string.guide_title_battery,
            R.string.guide_desc_battery
        )
        bindSection(
            findViewById(R.id.guideStorage),
            R.drawable.ic_storage,
            R.string.guide_title_storage,
            R.string.guide_desc_storage
        )

        findViewById<View>(R.id.btnStart).setOnClickListener { finishGuide() }
        findViewById<View>(R.id.tvSkip).setOnClickListener { finishGuide() }
    }

    private fun bindSection(view: View, iconRes: Int, titleRes: Int, descRes: Int) {
        view.findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.tvDescription).setText(descRes)
    }

    private fun finishGuide() {
        KVUtils.setGuideShown(true)
        finish()
    }
}
