package com.apk.claw.android.tool.impl;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class WaitTool extends BaseTool {

    @Override
    public String getName() {
        return "wait";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_wait);
    }

    @Override
    public String getDescriptionEN() {
        return "Wait for a specified number of milliseconds. Useful for waiting for UI transitions, animations, or loading to complete.";
    }

    @Override
    public String getDescriptionCN() {
        return "等待指定的毫秒数。适用于等待UI过渡、动画或加载完成。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("duration_ms", "integer", "Duration to wait in milliseconds", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long duration = requireLong(params, "duration_ms");
        if (duration < 0 || duration > 30000) {
            return ToolResult.error("Duration must be between 0 and 30000 milliseconds");
        }
        try {
            Thread.sleep(duration);
            return ToolResult.success("Waited for " + duration + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Wait was interrupted");
        }
    }
}
