package com.agy.ui.webview;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Message;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.agy.util.SensitiveDataRedactor;

import java.util.ArrayList;
import java.util.List;

/** Routes console output to logcat, popups to PopupWindowHandler, and file chooser to callback. */
public final class AgyWebChromeClient extends WebChromeClient {

    private static final String TAG = "WebViewHost";

    /** Activity request code for web-initiated runtime permission prompts. */
    public static final int REQUEST_WEB_PERMISSIONS = 4517;

    public interface FileChooserCallback {
        boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                  FileChooserParams fileChooserParams);
    }

    private final PopupWindowHandler popupHandler;
    private final FileChooserCallback fileChooserCallback;
    private final Context context;

    private PermissionRequest pendingRequest;
    private String[] pendingGrants;

    public AgyWebChromeClient(Context context, PopupWindowHandler popupHandler,
                              FileChooserCallback fileChooserCallback) {
        this.context = context;
        this.popupHandler = popupHandler;
        this.fileChooserCallback = fileChooserCallback;
    }

    public AgyWebChromeClient(PopupWindowHandler popupHandler, FileChooserCallback fileChooserCallback) {
        this(null, popupHandler, fileChooserCallback);
    }

    public AgyWebChromeClient(PopupWindowHandler popupHandler) {
        this(null, popupHandler, null);
    }

    /**
     * Grants web media-capture requests (voice mode microphone, in-page camera)
     * when the matching Android runtime permission is held; otherwise prompts
     * for it and resolves the web request from the activity result.
     */
    @Override
    public void onPermissionRequest(PermissionRequest request) {
        if (request == null) {
            return;
        }
        List<String> grantable = new ArrayList<>();
        List<String> needed = new ArrayList<>();
        for (String res : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                grantable.add(res);
                needed.add(Manifest.permission.RECORD_AUDIO);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                grantable.add(res);
                needed.add(Manifest.permission.CAMERA);
            }
        }
        if (grantable.isEmpty() || context == null) {
            request.deny();
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String perm : needed) {
            if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (missing.isEmpty()) {
            request.grant(grantable.toArray(new String[0]));
            return;
        }
        Activity activity = asActivity(context);
        if (activity == null) {
            request.deny();
            return;
        }
        if (pendingRequest != null) {
            try {
                pendingRequest.deny();
            } catch (Exception ignored) {
            }
        }
        pendingRequest = request;
        pendingGrants = grantable.toArray(new String[0]);
        activity.requestPermissions(missing.toArray(new String[0]), REQUEST_WEB_PERMISSIONS);
    }

    @Override
    public void onPermissionRequestCanceled(PermissionRequest request) {
        if (request != null && request == pendingRequest) {
            pendingRequest = null;
            pendingGrants = null;
        }
    }

    /** Called from the host activity's onRequestPermissionsResult. */
    public boolean onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_WEB_PERMISSIONS || pendingRequest == null) {
            return false;
        }
        PermissionRequest req = pendingRequest;
        String[] grants = pendingGrants;
        pendingRequest = null;
        pendingGrants = null;
        boolean ok = grantResults != null && grantResults.length > 0;
        if (ok) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    ok = false;
                    break;
                }
            }
        }
        try {
            if (ok) {
                req.grant(grants);
            } else {
                req.deny();
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static Activity asActivity(Context ctx) {
        Context c = ctx;
        while (c != null) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            c = (c instanceof ContextWrapper) ? ((ContextWrapper) c).getBaseContext() : null;
        }
        return null;
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage cm) {
        if (cm != null) {
            String msg = SensitiveDataRedactor.redactLine(cm.message());
            String src = SensitiveDataRedactor.safeUrl(cm.sourceId());
            int line = cm.lineNumber();
            ConsoleMessage.MessageLevel lvl = cm.messageLevel();
            String tag = "AGY_JS";
            if (lvl == ConsoleMessage.MessageLevel.ERROR) {
                Log.e(tag, "[" + src + ":" + line + "] " + msg);
            } else if (lvl == ConsoleMessage.MessageLevel.WARNING) {
                Log.w(tag, "[" + src + ":" + line + "] " + msg);
            } else {
                Log.i(tag, "[" + src + ":" + line + "] " + msg);
            }
        }
        return true;
    }

    @Override
    public boolean onCreateWindow(WebView parent, boolean isDialog, boolean isUserGesture,
                                  Message resultMsg) {
        return popupHandler.handle(parent, isDialog, isUserGesture, resultMsg);
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                      FileChooserParams fileChooserParams) {
        if (fileChooserCallback != null) {
            return fileChooserCallback.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }
        return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
    }
}
