package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.llm.LlmClientFactory
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.AlertDialog
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import com.apk.claw.android.widget.LoadingDialog
import com.google.android.material.card.MaterialCardView
import dev.langchain4j.data.message.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LlmConfigActivity : BaseActivity() {

    private lateinit var etProfileName: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etModelName: EditText
    private lateinit var llProfileList: LinearLayout
    private lateinit var btnDeleteProfile: KButton
    private lateinit var btnTestProfile: KButton

    private var currentProfileId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.llm_config_title))
            showBackButton(true) { finish() }
        }

        etProfileName = findViewById(R.id.etProfileName)
        etApiKey = findViewById(R.id.etApiKey)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etModelName = findViewById(R.id.etModelName)
        llProfileList = findViewById(R.id.llProfileList)
        btnDeleteProfile = findViewById(R.id.btnDeleteProfile)
        btnTestProfile = findViewById(R.id.btnTestProfile)

        currentProfileId = KVUtils.getSelectedLlmProfile().id
        bindProfile(KVUtils.getSelectedLlmProfile())
        renderProfiles()

        findViewById<KButton>(R.id.btnAddProfile).setOnClickListener {
            val profile = KVUtils.upsertLlmProfile(
                KVUtils.LlmProfile(
                    id = "",
                    name = getString(R.string.llm_config_new_profile_name),
                    apiKey = "",
                    baseUrl = KVUtils.getDefaultLlmBaseUrl(),
                    modelName = ""
                )
            )
            KVUtils.selectLlmProfile(profile.id)
            currentProfileId = profile.id
            bindProfile(profile)
            renderProfiles()
        }

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            saveCurrentProfile()
        }

        btnTestProfile.setOnClickListener {
            testCurrentProfile()
        }

        btnDeleteProfile.setOnClickListener {
            if (!KVUtils.deleteLlmProfile(currentProfileId)) {
                Toast.makeText(this, R.string.llm_config_keep_one_profile, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selected = KVUtils.getSelectedLlmProfile()
            currentProfileId = selected.id
            bindProfile(selected)
            renderProfiles()
            refreshAgent()
            Toast.makeText(this, R.string.llm_config_profile_deleted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindProfile(profile: KVUtils.LlmProfile) {
        currentProfileId = profile.id
        etProfileName.setText(profile.name)
        etApiKey.setText(profile.apiKey)
        etBaseUrl.setText(profile.baseUrl)
        etModelName.setText(profile.modelName)
        btnDeleteProfile.visibility = if (KVUtils.getLlmProfiles().size > 1) View.VISIBLE else View.GONE
    }

    private fun profileFromInput(): KVUtils.LlmProfile? {
        val name = etProfileName.text.toString().trim()
        val apiKey = etApiKey.text.toString().trim()
        val baseUrl = etBaseUrl.text.toString().trim()
        val modelName = etModelName.text.toString().trim()

        if (apiKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.llm_config_api_key_required), Toast.LENGTH_SHORT).show()
            return null
        }
        if (modelName.isEmpty()) {
            Toast.makeText(this, getString(R.string.llm_config_model_name_required), Toast.LENGTH_SHORT).show()
            return null
        }

        return KVUtils.LlmProfile(
            id = currentProfileId,
            name = name.ifEmpty { modelName },
            apiKey = apiKey,
            baseUrl = baseUrl,
            modelName = modelName
        )
    }

    private fun saveCurrentProfile() {
        val profile = profileFromInput() ?: return
        val saved = KVUtils.upsertLlmProfile(
            profile
        )
        KVUtils.selectLlmProfile(saved.id)
        currentProfileId = saved.id
        renderProfiles()
        refreshAgent()
        Toast.makeText(this, getString(R.string.llm_config_saved), Toast.LENGTH_SHORT).show()
    }

    private fun testCurrentProfile() {
        val profile = profileFromInput() ?: return
        btnTestProfile.isEnabled = false
        btnTestProfile.alpha = 0.6f
        val loadingDialog = LoadingDialog.show(
            this,
            getString(R.string.llm_config_testing),
            cancelable = false
        )

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val config = AgentConfig.Builder()
                        .apiKey(profile.apiKey)
                        .baseUrl(profile.baseUrl)
                        .modelName(profile.modelName)
                        .temperature(0.0)
                        .maxIterations(1)
                        .build()
                    LlmClientFactory.create(config).chat(
                        listOf(UserMessage.from("Reply with exactly OK.")),
                        emptyList()
                    ).text.orEmpty().trim()
                }
            }

            loadingDialog.dismiss()
            btnTestProfile.isEnabled = true
            btnTestProfile.alpha = 1f

            result.onSuccess { response ->
                val message = if (response.isBlank()) {
                    getString(R.string.llm_config_test_success_no_response)
                } else {
                    getString(R.string.llm_config_test_success, response.take(200))
                }
                AlertDialog.show(
                    context = this@LlmConfigActivity,
                    title = getString(R.string.llm_config_test_success_title),
                    message = message
                )
            }.onFailure { error ->
                AlertDialog.show(
                    context = this@LlmConfigActivity,
                    title = getString(R.string.llm_config_test_failed_title),
                    message = getString(
                        R.string.llm_config_test_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun renderProfiles() {
        val profiles = KVUtils.getLlmProfiles()
        val selectedId = KVUtils.getSelectedLlmProfile().id
        llProfileList.removeAllViews()
        profiles.forEach { profile ->
            llProfileList.addView(createProfileCard(profile, profile.id == selectedId))
        }
        btnDeleteProfile.visibility = if (profiles.size > 1) View.VISIBLE else View.GONE
    }

    private fun createProfileCard(profile: KVUtils.LlmProfile, selected: Boolean): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = pt(8)
            }
            radius = pt(10).toFloat()
            cardElevation = pt(1).toFloat()
            setCardBackgroundColor(
                getColor(if (selected) R.color.colorBrandContainer else R.color.colorContainerBrighten)
            )
            strokeWidth = if (selected) pt(1) else 0
            strokeColor = getColor(if (selected) R.color.colorBrandPrimary else R.color.colorBorderBase)
            isClickable = true
            setOnClickListener {
                KVUtils.selectLlmProfile(profile.id)
                currentProfileId = profile.id
                bindProfile(profile)
                renderProfiles()
                refreshAgent()
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pt(14), pt(12), pt(14), pt(12))
        }

        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@LlmConfigActivity).apply {
                text = profile.name
                setTextColor(getColor(R.color.colorTextPrimary))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(this@LlmConfigActivity).apply {
                text = getString(R.string.llm_config_profile_summary, profile.modelName, profile.baseUrl)
                setTextColor(getColor(R.color.colorTextSecondary))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, pt(4), 0, 0)
            })
        })

        row.addView(TextView(this).apply {
            text = if (selected) getString(R.string.llm_config_selected) else ""
            setTextColor(getColor(R.color.colorBrandPrimary))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        card.addView(row)
        return card
    }

    private fun refreshAgent() {
        ClawApplication.appViewModelInstance.updateAgentConfig()
        ClawApplication.appViewModelInstance.initAgent()
        ClawApplication.appViewModelInstance.afterInit()
    }

    private fun pt(value: Int): Int {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_PT,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
