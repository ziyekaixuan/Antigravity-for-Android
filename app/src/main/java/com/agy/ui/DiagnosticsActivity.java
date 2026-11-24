package com.agy.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

import com.agy.ui.splash.LogExporter;

/**
 * Invisible entry point behind the launcher long-press shortcut
 * "导出诊断日志": copies the full diagnostics (including the task-live
 * breadcrumb log) to the clipboard so the user can paste it into chat.
 * The regular diagnostics UI only exists on the startup-failure overlay,
 * which is unreachable when the app starts normally.
 */
public final class DiagnosticsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            LogExporter.copyToClipboard(this);
            Toast.makeText(this, "诊断日志已复制到剪贴板", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
