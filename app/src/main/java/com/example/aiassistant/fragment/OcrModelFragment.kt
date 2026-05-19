package com.example.aiassistant.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.aiassistant.AppPreferences
import com.example.aiassistant.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class OcrModelFragment : Fragment() {

    private lateinit var rgOcrMode: RadioGroup
    private lateinit var layoutCloudConfig: View
    private lateinit var etCloudOcrUrl: TextInputEditText
    private lateinit var etCloudOcrToken: TextInputEditText
    private lateinit var rgCloudOcrType: RadioGroup
    private lateinit var btnSave: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_ocr_model, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rgOcrMode = view.findViewById(R.id.rg_ocr_mode)
        layoutCloudConfig = view.findViewById(R.id.layout_cloud_ocr_config)
        etCloudOcrUrl = view.findViewById(R.id.et_cloud_ocr_url)
        etCloudOcrToken = view.findViewById(R.id.et_cloud_ocr_token)
        rgCloudOcrType = view.findViewById(R.id.rg_cloud_ocr_type)
        btnSave = view.findViewById(R.id.btn_save_ocr)

        loadConfig()
        setupListeners()
    }

    private fun loadConfig() {
        val ctx = requireContext()
        val ocrMode = AppPreferences.getOcrMode(ctx)
        rgOcrMode.check(if (ocrMode == AppPreferences.OCR_MODE_CLOUD) R.id.rb_ocr_cloud else R.id.rb_ocr_local)
        updateCloudVisibility(ocrMode)

        val cloudType = AppPreferences.getCloudOcrType(ctx)
        val url = if (cloudType == AppPreferences.CLOUD_OCR_TYPE_TEXT)
            AppPreferences.getCloudTextOcrUrl(ctx) else AppPreferences.getCloudOcrUrl(ctx)
        etCloudOcrUrl.setText(url)
        etCloudOcrToken.setText(AppPreferences.getCloudOcrToken(ctx))
        rgCloudOcrType.check(if (cloudType == AppPreferences.CLOUD_OCR_TYPE_TEXT) R.id.rb_ocr_text else R.id.rb_ocr_layout)
    }

    private fun setupListeners() {
        val ctx = requireContext()

        rgOcrMode.setOnCheckedChangeListener { _, id ->
            val mode = if (id == R.id.rb_ocr_cloud) AppPreferences.OCR_MODE_CLOUD else AppPreferences.OCR_MODE_LOCAL
            AppPreferences.setOcrMode(ctx, mode)
            updateCloudVisibility(mode)
        }

        rgCloudOcrType.setOnCheckedChangeListener { _, id ->
            val newType = if (id == R.id.rb_ocr_text) AppPreferences.CLOUD_OCR_TYPE_TEXT else AppPreferences.CLOUD_OCR_TYPE_LAYOUT
            val oldType = AppPreferences.getCloudOcrType(ctx)
            val currentUrl = etCloudOcrUrl.text?.toString()?.trim() ?: ""
            if (currentUrl.isNotBlank()) {
                if (oldType == AppPreferences.CLOUD_OCR_TYPE_TEXT)
                    AppPreferences.setCloudTextOcrUrl(ctx, currentUrl)
                else
                    AppPreferences.setCloudOcrUrl(ctx, currentUrl)
            }
            AppPreferences.setCloudOcrType(ctx, newType)
            val newUrl = if (newType == AppPreferences.CLOUD_OCR_TYPE_TEXT)
                AppPreferences.getCloudTextOcrUrl(ctx) else AppPreferences.getCloudOcrUrl(ctx)
            etCloudOcrUrl.setText(newUrl)
        }

        btnSave.setOnClickListener {
            val url = etCloudOcrUrl.text?.toString()?.trim() ?: ""
            val token = etCloudOcrToken.text?.toString()?.trim() ?: ""
            val cloudType = AppPreferences.getCloudOcrType(ctx)
            if (cloudType == AppPreferences.CLOUD_OCR_TYPE_TEXT)
                AppPreferences.setCloudTextOcrUrl(ctx, url)
            else
                AppPreferences.setCloudOcrUrl(ctx, url)
            AppPreferences.setCloudOcrToken(ctx, token)
            Toast.makeText(ctx, "OCR 配置已保存 ✓", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCloudVisibility(mode: Int) {
        layoutCloudConfig.visibility = if (mode == AppPreferences.OCR_MODE_CLOUD) View.VISIBLE else View.GONE
    }
}
