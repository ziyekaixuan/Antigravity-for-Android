package com.agy.core;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.agy.runtime.RuntimeManager;
import com.agy.ui.TaskLiveNotifier;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Foreground host for the patched core (language_server). Holds a wake lock,
 * streams the process log to files/logs/core.log, and publishes serverUrl once
 * ready.
 */
public final class CoreServerService extends Service {

    private static final String TAG = "CoreServer";

    private RuntimeManager runtime;
    private WakeLockHolder wakeLock;
    private OpenUrlWatcher openUrlWatcher;
    private CoreProcessSupervisor supervisor;
    private com.agy.log.LogRingBuffer logBuffer;
    private com.agy.gateway.SystemGatewayServer gatewayServer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private Thread heartbeatThread;

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = new RuntimeManager(this);
        logBuffer = new com.agy.log.LogRingBuffer();
        gatewayServer = new com.agy.gateway.SystemGatewayServer(this, logBuffer, com.agy.BuildConfig.GATEWAY_PORT);
        gatewayServer.start();

        Log.i(TAG, "state paths: HOME=" + getFilesDir().getAbsolutePath()
                + " appData=" + runtime.getAppDataDir().getAbsolutePath()
                + " projects=" + runtime.getProjectsDir().getAbsolutePath()
                + " runtime=" + runtime.getRuntimeDir().getAbsolutePath());
        CoreRuntimeState.httpsPort = runtime.getHttpsPort();
        CoreRuntimeState.csrfToken = runtime.getCsrfToken();
        CoreRuntimeState.restarting = false;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.serverUrl = null;

        ForegroundNotification.start(this);
        wakeLock = new WakeLockHolder();
        wakeLock.acquire(this);

        openUrlWatcher = new OpenUrlWatcher(this);
        openUrlWatcher.start();

        supervisor = new CoreProcessSupervisor(this, runtime, openUrlWatcher, logBuffer,
                this::onSupervisorTerminated);
        supervisor.start();

        startHeartbeat();
        startBackgroundDaemon();
    }

    /**
     * Real task-state monitoring: every 5s, ask the core itself for its
     * cascade states (CascadeStatusMonitor) and drive the live
     * notification from that ground truth - ACTIVE starts/refreshes it,
     * two consecutive IDLE polls (10s) conclude it. This replaces the
     * earlier web-DOM heuristics, which could see a task start but kept
     * missing its end. If the status RPC errors while the plain probe
     * says the core is alive, the monitor silently stands down for that
     * tick (no conclusions drawn); only a genuinely unreachable core
     * (3 failed polls with a dead probe) flips the island to 连接中断.
     * The loop body is wrapped so no failure can kill the thread, and
     * requestImmediatePoll() wakes it instantly when the app returns to
     * the foreground.
     */
    private static final Object MONITOR_WAKE = new Object();

    /** Wake the monitor loop for an immediate poll (e.g. app foregrounded). */
    public static void requestImmediatePoll() {
        synchronized (MONITOR_WAKE) {
            MONITOR_WAKE.notifyAll();
        }
    }

    // Monitor state (touched only by the heartbeat thread).
    private int monFails = 0;
    private int monIdleStreak = 0;
    private boolean monSawActive = false;
    private boolean monUnsupportedLogged = false;
    private boolean monBatteryWarned = false;
    private String monLastDebugView = "";
    private long monLastDebugLogAt = 0L;

    private void startHeartbeat() {
        heartbeatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!destroyed) {
                    try {
                        synchronized (MONITOR_WAKE) {
                            MONITOR_WAKE.wait(5000L);
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (destroyed) {
                        return;
                    }
                    // A tick must never kill this thread: an uncaught
                    // failure would silently stop all monitoring while
                    // the island keeps saying "running".
                    try {
                        monitorTick();
                    } catch (Throwable t) {
                        com.agy.log.TaskLiveLog.append(CoreServerService.this,
                                "monitor tick error: " + t);
                    }
                }
            }
        }, "agy-task-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void monitorTick() {
        if (!TaskLiveNotifier.isEnabled(CoreServerService.this)) {
            monFails = 0;
            monIdleStreak = 0;
            monSawActive = false;
            return;
        }
        CascadeStatusMonitor.Result r = CascadeStatusMonitor.poll(
                CoreServerService.this, CoreRuntimeState.httpsPort,
                CoreRuntimeState.csrfToken);
        // Breadcrumb: what the monitor actually sees - logged whenever the
        // view changes, and once a minute while a run is being tracked.
        long now = System.currentTimeMillis();
        if (!r.debug.isEmpty()
                && (!r.debug.equals(monLastDebugView)
                || (TaskLiveNotifier.isRunning() && now - monLastDebugLogAt >= 60000L))) {
            monLastDebugView = r.debug;
            monLastDebugLogAt = now;
            com.agy.log.TaskLiveLog.append(CoreServerService.this,
                    "monitor view: " + r.debug);
        }
        if (TaskLiveNotifier.isRunning()) {
            if (!monBatteryWarned) {
                monBatteryWarned = true;
                try {
                    android.os.PowerManager pm = (android.os.PowerManager)
                            getSystemService(POWER_SERVICE);
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        com.agy.log.TaskLiveLog.append(CoreServerService.this,
                                "battery NOT exempt: OEM may freeze background monitoring");
                    }
                } catch (Exception ignored) {
                }
            }
        } else {
            monBatteryWarned = false;
        }
        switch (r.kind) {
            case ACTIVE:
                if (monFails > 0 || TaskLiveNotifier.isStalled()) {
                    com.agy.log.TaskLiveLog.append(CoreServerService.this,
                            "monitor core alive again");
                    TaskLiveNotifier.clearStalled(CoreServerService.this);
                }
                monFails = 0;
                monIdleStreak = 0;
                monSawActive = true;
                TaskLiveNotifier.monitorActive(CoreServerService.this, r.title);
                break;
            case IDLE:
                if (monFails > 0 || TaskLiveNotifier.isStalled()) {
                    com.agy.log.TaskLiveLog.append(CoreServerService.this,
                            "monitor core alive again");
                    TaskLiveNotifier.clearStalled(CoreServerService.this);
                }
                monFails = 0;
                if (TaskLiveNotifier.isRunning()) {
                    monIdleStreak++;
                    long runningFor = System.currentTimeMillis()
                            - TaskLiveNotifier.runningSinceMillis();
                    // Conclude after 2 consecutive idle polls. A run the
                    // monitor itself saw active needs nothing more; a run
                    // only the web hook started must be 15s old first, so
                    // a just-sent message has time to register its
                    // trajectory with the core.
                    if (monIdleStreak >= 2 && (monSawActive || runningFor >= 15000L)) {
                        TaskLiveNotifier.monitorIdle(CoreServerService.this);
                        monIdleStreak = 0;
                        monSawActive = false;
                    }
                } else {
                    monIdleStreak = 0;
                    monSawActive = false;
                }
                break;
            default: // ERROR
                monIdleStreak = 0;
                if (probeCore()) {
                    // Core alive but the status RPC failed - stand down
                    // quietly, draw no conclusions.
                    if (!monUnsupportedLogged) {
                        monUnsupportedLogged = true;
                        com.agy.log.TaskLiveLog.append(CoreServerService.this,
                                "monitor RPC failed but core alive; standing down");
                    }
                    monFails = 0;
                } else if (TaskLiveNotifier.isRunning()) {
                    monFails++;
                    com.agy.log.TaskLiveLog.append(CoreServerService.this,
                            "monitor poll FAILED x" + monFails);
                    if (monFails >= 3) {
                        TaskLiveNotifier.postStalled(CoreServerService.this);
                    }
                } else {
                    monFails = 0;
                }
                break;
        }
    }

    /**
     * Liveness check for the embedded core. Reuses {@link CoreReadyProbe},
     * which pins the bundled core certificate (SHA-256) instead of trusting
     * any certificate presented on loopback.
     */
    private boolean probeCore() {
        int port = CoreRuntimeState.httpsPort;
        if (port <= 0) {
            return false;
        }
        return CoreReadyProbe.probeOnce(this, port);
    }

    private void startBackgroundDaemon() {
        new Thread(() -> {
            try {
                // Give runtime environment a brief moment to stabilize
                Thread.sleep(1500);
                File daemonBin = new File(getFilesDir(), "usr/bin/agy-daemon");
                if (daemonBin.canExecute()) {
                    Log.i(TAG, "Starting autonomous background daemon...");
                    ProcessBuilder pb = new ProcessBuilder(daemonBin.getAbsolutePath(), "start");
                    pb.directory(getFilesDir());
                    pb.environment().put("PATH", getFilesDir().getAbsolutePath() + "/usr/bin:" + System.getenv("PATH"));
                    pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                    pb.start();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to start agy-daemon: " + e.getMessage());
            }
        }, "AgyDaemonStarter").start();
    }

    private void stopBackgroundDaemon() {
        try {
            File daemonBin = new File(getFilesDir(), "usr/bin/agy-daemon");
            if (daemonBin.canExecute()) {
                Log.i(TAG, "Stopping autonomous background daemon...");
                ProcessBuilder pb = new ProcessBuilder(daemonBin.getAbsolutePath(), "stop");
                pb.directory(getFilesDir());
                pb.environment().put("PATH", getFilesDir().getAbsolutePath() + "/usr/bin:" + System.getenv("PATH"));
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                Process p = pb.start();
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "agy-daemon stop timed out after 1500ms; destroying forcibly");
                    p.destroyForcibly();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop agy-daemon: " + e.getMessage());
        }
    }

    private void onSupervisorTerminated() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                CoreRuntimeState.serverUrl = null;
                CoreRuntimeState.restarting = false;
                if (CoreRuntimeState.lastError == null) {
                    CoreRuntimeState.lastError = "core supervisor stopped unexpectedly";
                }
                if (wakeLock != null) {
                    wakeLock.release();
                }
                if (runtime != null) {
                    runtime.stopNetworkMonitor();
                }
                if (openUrlWatcher != null) {
                    openUrlWatcher.stop();
                }
                ForegroundNotification.update(CoreServerService.this,
                        "Core stopped — open app to retry");
            }
        });
    }

    @Override
    public void onDestroy() {
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
        stopBackgroundDaemon();
        if (gatewayServer != null) {
            gatewayServer.stop();
        }
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        // Intentional stop: the watchdog must not treat the exit as a crash.
        if (supervisor != null) {
            supervisor.stop();
        }
        if (openUrlWatcher != null) {
            openUrlWatcher.stop();
        }
        if (wakeLock != null) {
            wakeLock.release();
        }
        if (runtime != null) {
            runtime.stopNetworkMonitor();
        }
        CoreRuntimeState.serverUrl = null;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.restarting = false;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
