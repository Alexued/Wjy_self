package com.apk.claw.android.tool.impl.mobile;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.service.ClawAccessibilityService;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TapTool extends BaseTool {

    @Override
    public String getName() {
        return "tap";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_tap);
    }

    @Override
    public String getDescriptionEN() {
        return "Tap at the specified screen coordinates (x, y).";
    }

    @Override
    public String getDescriptionCN() {
        return "在指定的屏幕坐标 (x, y) 处点击。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("x", "integer", "X coordinate on screen", true),
                new ToolParameter("y", "integer", "Y coordinate on screen", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        int x = requireInt(params, "x");
        int y = requireInt(params, "y");
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);
        boolean success = service.performTap(x, y);
        return success ? ToolResult.success("Tapped at (" + x + ", " + y + ")")
                : ToolResult.error("Failed to tap at (" + x + ", " + y + ")");
    }
}
