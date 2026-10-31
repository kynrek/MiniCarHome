package com.kynrek.minicarhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.net.Uri;
import android.os.IBinder;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Home screen for the JoyeBox: Google Maps runs as a freeform window over the left two-thirds,
 * and a panel on the right third shows a clock, now-playing card and shortcuts.
 *
 * The panel and the app browser are overlay windows: since Android 14, Maps' window carries a
 * full-screen input sink that swallows touches meant for the home screen underneath, and overlays
 * sit above it. Needs (granted over ADB): enable_freeform_support=1, a hidden-API exemption for
 * ActivityOptions.setLaunchWindowingMode, SYSTEM_ALERT_WINDOW, and the notification listener.
 */
public class HomeActivity extends Activity {
    private static final String TAG = "BoxHome";
    private static final String MAPS = "com.google.android.apps.maps";
    private static final int PICK_NONE = 0, PICK_SHORTCUT = 1, PICK_NAV = 2, PICK_ASSISTANT = 3;
    // The main window is a multi-window task with launch bounds: unlike a freeform window it has no caption
    // bar (▭ ✕). Freeform is only used briefly to close a window that has to be reopened at a new size.
    private static final int WINDOWING_MODE_FREEFORM = 5, WINDOWING_MODE_MULTI_WINDOW = 6;
    private static final int FREEFORM_CAPTION_DP = 24;   // measured: 24 px at 160 dpi; scales with display size
    private final java.util.Map<String, Rect> openedAt = new java.util.HashMap<>();   // pane apps and their window bounds
    private String reopening;   // app being closed and reopened at a new size; ignore other starts meanwhile
    private String paneTarget;   // the app most recently opened in the pane
    private long paneLaunchedAt;
    private static final long PANE_RESCUE_MS = 10000;
    private static final int MAX_MAPS_ATTEMPTS = 30;
    private static final long MAPS_RETRY_MS = 2000;
    private int mapsAttempts;
    private boolean mapsPending, topResumed, editing;
    private String paneApp;   // app opened in the main pane over the main display app, if any
    /**
     * Launcher icons that only bounce to another screen and close themselves; in a freeform window the
     * bounce never lands, so open the real screen directly. (Podcast Addict: noHistory launcher in its
     * own task affinity.)
     */
    private static final java.util.Map<String, String> TRAMPOLINES = java.util.Collections.singletonMap(
            "com.bambuna.podcastaddict/com.bambuna.podcastaddict.activity.LauncherActivity",
            "com.bambuna.podcastaddict/com.bambuna.podcastaddict.activity.PodcastListActivity");
    // Right/left panel: icons per row sets its width, at a fixed width per icon.
    private static final int MIN_COLUMNS = 2, MAX_COLUMNS = 4, DEFAULT_COLUMNS = 3;
    private static final int COLUMN_DP = 84;   // a 48-unit icon plus room for its label
    private static final int PAD_EDGE = 14, PAD_INNER = 6;
    private int columns = DEFAULT_COLUMNS;
    private boolean panelLeft, settingsOpen;
    private TextView mapSlot;
    private static final int MAX_RECENT = 10;
    private static final String[] SHORTCUTS = {
            MAPS,
            "app.symfonik.music.player",
            "com.bambuna.podcastaddict",
            "com.audible.application",
            "com.audiobookshelf.app",
            "com.android.vending",
            "com.applepie.setting",    // the box's own Settings
            "com.android.settings",    // Android Settings
            "local.boxtools/local.boxtools.WirelessDebugActivity",   // package/class for apps with several icons
            "org.codeaurora.dialer",   // Phone
            "com.android.mms",         // Messaging
    };

    private static final int BG = 0xFF0E1416, CARD = 0xFF1B2428, TEXT = 0xFFE8EEF0, DIM = 0xFF9AA8AD, ACCENT = 0xFF6FD3C0;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PackageManager pm;
    private WindowManager wm;
    private SharedPreferences prefs;
    private View panel;
    private BrowserView browser;
    private boolean panelShown, browserShown;
    private TextView mediaTitle, mediaArtist, playButton, wifiLabel, cellLabel;
    private SignalBars wifiBars, cellBars;
    private TextView weatherTemp, weatherRange;
    private static final long WEATHER_REFRESH_MS = 15 * 60_000;
    private static final long SIGNAL_REFRESH_MS = 5000;
    private ImageView mediaArt;
    private LinearLayout progressRow;
    private ProgressBar mediaProgress;
    private TextView mediaRemaining;
    private MediaSessionManager sessions;
    private MediaController controller;
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { showMedia(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { showMedia(); }
        @Override public void onSessionDestroyed() { pickController(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = list -> pickController();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, prefs.getInt("columns", DEFAULT_COLUMNS)));
        panelLeft = prefs.getBoolean("panel_left", false);
        setContentView(buildBackdrop());
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived, main);
        // An app installed, updated (new name or icon) or removed: redraw the shortcuts.
        android.content.IntentFilter apps = new android.content.IntentFilter();
        apps.addAction(Intent.ACTION_PACKAGE_ADDED);
        apps.addAction(Intent.ACTION_PACKAGE_REPLACED);
        apps.addAction(Intent.ACTION_PACKAGE_REMOVED);
        apps.addAction(Intent.ACTION_PACKAGE_CHANGED);
        apps.addDataScheme("package");
        registerReceiver(appsChanged, apps);
        panel = buildPanel();
        main.postDelayed(this::ensureMaps, 800);
        if (!prefs.contains("density")) setDensity(DEFAULT_DENSITY);   // first run: bigger than the box's 120 dpi
    }

    private final android.content.BroadcastReceiver appsChanged = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            main.removeCallbacks(appsRebuild);
            main.postDelayed(appsRebuild, 500);   // an update sends removed, added and replaced in a row
        }
    };
    private final Runnable appsRebuild = () -> rebuildPanel();

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(appsChanged);
        Shizuku.removeBinderReceivedListener(shizukuReceived);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed (or the Home gesture): close the app browser and bring the Maps window back.
        hideBrowser();
        main.postDelayed(this::ensureMaps, 250);
    }

    @Override
    protected void onStart() {
        super.onStart();
        showPanel();
        main.removeCallbacks(signalTick);
        main.post(signalTick);
        showWeather();
        main.removeCallbacks(weatherTick);
        main.post(weatherTick);
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerName(), main);
            pickController();
        } catch (SecurityException e) {
            mediaTitle.setText("Now playing needs notification access");
            mediaArtist.setText("");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Another app is now full screen: stop reopening Maps over it and take the overlays down.
        mapsPending = false;
        main.removeCallbacks(mapsRetry);
        main.removeCallbacks(signalTick);
        main.removeCallbacks(weatherTick);
        main.removeCallbacks(progressTick);
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        setListening(false);
        hideBrowser();
        hidePanel();
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsListener);
        setController(null);
        rescuePaneApp();
    }

    /**
     * An app just opened in the pane covered the home screen: its splash screen started the main screen in a
     * new task (Aladdin Connect does this), and a new task opens full screen. Remember the app. With Shizuku,
     * close that task and open the app again the hand-off way; without it, the app stays full screen.
     */
    private void rescuePaneApp() {
        if (paneTarget == null || SystemClock.uptimeMillis() - paneLaunchedAt > PANE_RESCUE_MS) return;
        paneLaunchedAt = 0;
        String pkg = paneTarget;
        java.util.Set<String> apps = new java.util.HashSet<>(handoffApps());
        if (!apps.add(pkg)) return;
        prefs.edit().putStringSet("handoff_apps", apps).apply();
        Log.i(TAG, pkg + " hands off to a new task");
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null || !shizukuReady()) return;
        Integer task = findTask(pkg);
        if (task == null) return;
        try {
            Object atm = activityTasks();
            atm.getClass().getMethod("removeTask", int.class).invoke(atm, task);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "removeTask failed", e);
            return;
        }
        main.postDelayed(() -> startHandoffApp(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pkg), 600);
    }

    /**
     * Apps whose splash screen opens the main screen in a new task: started freeform, that task inherits
     * freeform but not the pane's bounds, so Shizuku resizes it into the pane once it appears (it keeps the
     * ▭ ✕ caption bar). Without Shizuku they open full screen, which at least shows all of them.
     */
    private void startHandoffApp(Intent i, String pkg) {
        if (!shizukuReady()) {
            paneLaunchedAt = 0;   // full screen on purpose
            try { startActivity(i); } catch (RuntimeException e) { Log.w(TAG, "launch failed", e); }
            return;
        }
        startPane(i, WINDOWING_MODE_FREEFORM);
        Rect bounds = mapsBounds();
        bounds.bottom -= sysDp(FREEFORM_CAPTION_DP);   // the caption bar pushes the app down by its height
        for (long delay : new long[]{1500, 3000, 5000}) {   // the main screen's task appears after the splash
            main.postDelayed(() -> { if (pkg.equals(paneTarget)) fitTask(pkg, bounds); }, delay);
        }
    }

    // ---- Shizuku (optional) ----
    // A helper app with adb-level access. When it's running and has allowed MiniCarHome, MiniCarHome can resize
    // other apps' windows, which Android otherwise doesn't allow.

    private static final int SHIZUKU_REQUEST = 7;

    private final Shizuku.OnBinderReceivedListener shizukuReceived = () -> {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                    && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(SHIZUKU_REQUEST);   // asks once, in Shizuku's own dialog
            }
        } catch (RuntimeException e) { Log.w(TAG, "Shizuku permission check failed", e); }
    };

    private boolean shizukuReady() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** IActivityTaskManager, called through Shizuku (as the shell user). */
    private Object activityTasks() throws ReflectiveOperationException {
        IBinder b = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity_task"));
        return Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    /** The id of the app's most recent visible task, or null. */
    private Integer findTask(String pkg) {
        android.app.ActivityManager.RunningTaskInfo t = findTaskInfo(pkg);
        return t == null ? null : t.taskId;
    }

    private boolean portraitOnly(android.app.ActivityManager.RunningTaskInfo task) {
        try {
            int o = pm.getActivityInfo(task.topActivity, 0).screenOrientation;
            return o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    private android.app.ActivityManager.RunningTaskInfo findTaskInfo(String pkg) {
        try {
            Object atm = activityTasks();
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals("getTasks")) continue;
                // getTasks(int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId)
                Class<?>[] types = m.getParameterTypes();
                Object[] args = new Object[types.length];
                for (int k = 0; k < types.length; k++) {
                    args[k] = types[k] == boolean.class ? (Object) false : (Object) (k == 0 ? 20 : 0);
                }
                for (Object o : (List<?>) m.invoke(atm, args)) {
                    android.app.ActivityManager.RunningTaskInfo t = (android.app.ActivityManager.RunningTaskInfo) o;
                    ComponentName top = t.topActivity != null ? t.topActivity : t.baseActivity;
                    if (top != null && pkg.equals(top.getPackageName())) return t;
                }
                return null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "getTasks failed", e);
        }
        return null;
    }

    /**
     * Put the app's task in the pane at its full size with a WindowContainerTransaction: caption-less
     * multi-window like any other app, except for apps locked to portrait, which multi-window letterboxes into a
     * narrow column. Those stay freeform, which ignores the lock; Android keeps a freeform window below the
     * status bar, so they keep their caption bar there. If the transaction is refused, just resize the task.
     */
    private void fitTask(String pkg, Rect captioned) {
        android.app.ActivityManager.RunningTaskInfo task = findTaskInfo(pkg);
        if (task == null) return;
        try {
            Object atm = activityTasks();
            Object token = android.app.TaskInfo.class.getField("token").get(task);
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object wct = wctClass.getConstructor().newInstance();
            Rect bounds = captioned;
            if (!portraitOnly(task)) {
                wctClass.getMethod("setWindowingMode", tokenClass, int.class).invoke(wct, token, WINDOWING_MODE_MULTI_WINDOW);
                bounds = mapsBounds();
            }
            wctClass.getMethod("setBounds", tokenClass, Rect.class).invoke(wct, token, bounds);
            Object controller = atm.getClass().getMethod("getWindowOrganizerController").invoke(atm);
            IBinder b = new ShizukuBinderWrapper((IBinder) controller.getClass().getMethod("asBinder").invoke(controller));
            Object wo = Class.forName("android.window.IWindowOrganizerController$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, b);
            wo.getClass().getMethod("applyTransaction", wctClass).invoke(wo, wct);
            return;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "window transaction failed; resizing instead", e);
        }
        try {
            Object atm = activityTasks();
            atm.getClass().getMethod("resizeTask", int.class, Rect.class, int.class).invoke(atm, task.taskId, captioned, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "resizeTask failed", e);
        }
    }

    private java.util.Set<String> handoffApps() {
        return prefs.getStringSet("handoff_apps", java.util.Collections.emptySet());
    }

    // ---- Maps window ----

    private Rect mapsBounds() {
        Rect screen = wm.getMaximumWindowMetrics().getBounds();
        int mapsWidth = screen.width() - panelWidth();
        int left = panelLeft ? screen.width() - mapsWidth : 0;
        return new Rect(left, 0, left + mapsWidth, screen.height());
    }

    /** After a width or side change, move Maps to its new place (startInPane reopens it at the new size). */
    private void resizeMaps(Rect old) { ensureMaps(); }

    private void resizeMaps(Rect old, String oldApp) { ensureMaps(); }   // the old app's window stays underneath

    /**
     * Open the Maps window and keep retrying until it actually comes up: right after boot Maps can be
     * unavailable or the start can be dropped, which left an empty slot until it was tapped.
     * Success shows up as this activity losing top-resumed status to Maps.
     */
    private void ensureMaps() {
        mapsAttempts = 0;
        mapsPending = true;
        main.removeCallbacks(mapsRetry);
        main.post(mapsRetry);
    }

    private final Runnable mapsRetry = new Runnable() {
        @Override public void run() {
            if (!mapsPending) return;
            if (mapsAttempts > 0 && !topResumed) {
                // Something (Maps) is above us: either our last try worked or Maps was already open.
                Log.i(TAG, "Maps window is up after " + mapsAttempts + " tries");
                mapsPending = false;
                return;
            }
            if (++mapsAttempts > MAX_MAPS_ATTEMPTS) {
                Log.w(TAG, "Maps window did not come up after " + MAX_MAPS_ATTEMPTS + " tries");
                mapsPending = false;
                return;
            }
            Log.i(TAG, "opening Maps window, try " + mapsAttempts);
            launchMaps();
            main.postDelayed(this, MAPS_RETRY_MS);
        }
    };

    @Override
    public void onTopResumedActivityChanged(boolean top) {
        super.onTopResumedActivityChanged(top);
        topResumed = top;
        if (!top && mapsPending) {
            Log.i(TAG, "Maps window is up after " + mapsAttempts + " tries");
            mapsPending = false;
            main.removeCallbacks(mapsRetry);
        }
    }

    /** Back to the home screen: close the app in the main window (if any) and show the main display app. */
    private void goHome() {
        hideBrowser();
        settingsOpen = false;
        editing = false;
        paneApp = null;   // its window stays underneath the main display app
        rebuildPanel();
        launchMaps();
    }

    private void launchMaps() {
        Intent i = pm.getLaunchIntentForPackage(navApp());
        if (i == null) {
            Log.i(TAG, "Maps not available yet");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startInPane(i, navApp());
    }

    /**
     * Start an app in the main pane, as a caption-less multi-window task. The window shell only shows tasks
     * it manages, and a new multi-window task for an app that's already running (a music app playing in the
     * background, say) can stay hidden. Freeform tasks are always shown, so every start goes to freeform first
     * and switches the same task to multi-window a moment later; the caption bar shows only briefly.
     *
     * Android also keeps an open window's size, so if this app's window was opened at another size (the
     * panel's width or side changed since), Box Tools taps its freeform close button and it's reopened.
     */
    private void startInPane(Intent i, String pkg) {
        if (pkg.equals(reopening)) return;   // e.g. the Maps retry firing mid-close
        paneTarget = pkg;
        paneLaunchedAt = SystemClock.uptimeMillis();
        if (handoffApps().contains(pkg)) { startHandoffApp(i, pkg); return; }
        Rect want = mapsBounds(), had = openedAt.get(pkg);
        startPane(i, WINDOWING_MODE_FREEFORM);
        if (had == null || had.equals(want)) {
            dropCaption(i, pkg, want);
            return;
        }
        Log.i(TAG, "reopening " + pkg + " at " + want + " (was " + had + ")");
        reopening = pkg;
        hidePanel();   // the old window's close button can sit where the panel is now
        main.postDelayed(() -> sendBroadcast(new Intent("local.boxtools.action.CLOSE_WINDOW")
                .setPackage("local.boxtools").putExtra("package", pkg)), 600);   // Box Tools checks our CONTROL permission
        main.postDelayed(() -> {
            showPanel();
            reopening = null;
            if (!pkg.equals(paneTarget)) return;   // another app was opened meanwhile
            startPane(i, WINDOWING_MODE_FREEFORM);
            dropCaption(i, pkg, want);
        }, 3000);
    }

    /** Switch the app's freeform task to multi-window, unless another app was opened in the meantime. */
    private void dropCaption(Intent i, String pkg, Rect bounds) {
        openedAt.put(pkg, bounds);
        main.postDelayed(() -> {
            if (pkg.equals(paneTarget) && !pkg.equals(reopening)) startPane(i, WINDOWING_MODE_MULTI_WINDOW);
        }, 1000);
    }

    private void startPane(Intent i, int mode) {
        try {
            startActivity(i, paneOptions(mode));
        } catch (RuntimeException e) {
            Log.w(TAG, "launch failed", e);
            Toast.makeText(this, "Can't open: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Launch options that put an app in a window over the main pane. */
    private Bundle paneOptions(int mode) {
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchBounds(mapsBounds());
        try {
            Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            m.invoke(o, mode);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "windowed launch unavailable", e);
        }
        return o.toBundle();
    }

    /**
     * With "Open apps in main window" on, apps open in the main pane over the main display app, which keeps
     * running underneath (Home brings it back). Opening another app puts it over the previous one.
     * Box Tools' own icons (Stock Home, Wireless Debug) still open normally.
     */
    private boolean opensInPane(ComponentName cn) {
        return prefs.getBoolean("apps_in_pane", true) && !"local.boxtools".equals(cn.getPackageName());
    }

    private void launchInPane(Intent i, String pkg) {
        paneApp = pkg;   // the previous app's window stays underneath
        main.postDelayed(() -> startInPane(i, pkg), 400);
    }

    // ---- overlays ----

    private WindowManager.LayoutParams overlayParams(int width, int gravity, boolean focusable) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!focusable) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.OPAQUE);
        lp.gravity = gravity;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        return lp;
    }

    private void showPanel() {
        if (panelShown) return;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "MiniCarHome needs the 'display over other apps' permission", Toast.LENGTH_LONG).show();
            return;
        }
        wm.addView(panel, overlayParams(panelWidth(), Gravity.TOP | (panelLeft ? Gravity.START : Gravity.END), false));
        panelShown = true;
    }

    /** Panel width for the chosen icons per row, never more than 60% of the screen. */
    private int panelWidth() {
        int max = wm.getMaximumWindowMetrics().getBounds().width() * 3 / 5;
        return Math.min(max, dp(PAD_EDGE) + dp(PAD_INNER) + columns * dp(COLUMN_DP));
    }

    // Settings live in SharedPreferences, which are written to disk and survive reboots.
    private void setColumns(int n) {
        n = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, n));
        if (n == columns) return;
        Rect old = mapsBounds();
        columns = n;
        prefs.edit().putInt("columns", n).apply();
        applyLayout(old);
    }

    /** The main display app shown in the big window; Google Maps by default. */
    private String navApp() {
        String app = prefs.getString("nav_app", MAPS);
        return pm.getLaunchIntentForPackage(app) != null ? app : MAPS;
    }

    private void setNavApp(String pkg) {
        if (pkg.equals(navApp())) return;
        Rect old = mapsBounds();
        String oldApp = navApp();
        prefs.edit().putString("nav_app", pkg).apply();
        mapSlot.setText(appName(pkg) + "\nTap to open");
        rebuildPanel();
        updateMapSlot();
        resizeMaps(old, oldApp);   // closes the old app's window, then opens the new one in its place
    }

    /**
     * What the mic button does: "builtin" (default) listens on the panel and sends the destination straight to
     * the main display app; "system" opens Android's assistant; "app" opens the chosen assistant app.
     */
    private String assistantMode() {
        String mode = prefs.getString("assistant_mode", "builtin");
        return "app".equals(mode) && assistantApp() == null ? "builtin" : mode;
    }

    /** The chosen assistant app, if it's still installed. */
    private String assistantApp() {
        String app = prefs.getString("assistant_app", null);
        return app != null && pm.getLaunchIntentForPackage(app) != null ? app : null;
    }

    private void setPref(String key, boolean on) {
        prefs.edit().putBoolean(key, on).apply();
        rebuildPanel();
    }

    private void setString(String key, String value) {
        prefs.edit().putString(key, value).apply();
        rebuildPanel();
    }

    private void setSignalPlace(String place) {
        prefs.edit().putString("signals", place).apply();
        rebuildPanel();
    }

    private void setPanelLeft(boolean left) {
        if (left == panelLeft) return;
        Rect old = mapsBounds();
        panelLeft = left;
        prefs.edit().putBoolean("panel_left", left).apply();
        applyLayout(old);
    }

    private void applyLayout(Rect oldMaps) {
        rebuildPanel();
        updateMapSlot();
        pickController();
        resizeMaps(oldMaps);
    }

    // ---- editable shortcuts (saved as package or package/class names) ----

    private List<String> shortcuts() {
        List<String> out = new ArrayList<>();
        String saved = prefs.getString("shortcuts", null);
        if (saved == null) Collections.addAll(out, SHORTCUTS);
        else if (!saved.isEmpty()) Collections.addAll(out, saved.split("\\|"));
        return out;
    }

    private void saveShortcuts(List<String> list) {
        prefs.edit().putString("shortcuts", TextUtils.join("|", list)).apply();
        rebuildPanel();
    }

    private void moveShortcut(int i, int by) {
        List<String> list = shortcuts();
        int j = i + by;
        if (i < 0 || i >= list.size() || j < 0 || j >= list.size()) return;
        Collections.swap(list, i, j);
        saveShortcuts(list);
    }

    private void removeShortcut(int i) {
        List<String> list = shortcuts();
        if (i < 0 || i >= list.size()) return;
        list.remove(i);
        saveShortcuts(list);
    }

    private void addShortcut(ComponentName cn) {
        List<String> list = shortcuts();
        String flat = cn.flattenToString();
        if (!list.contains(flat) && !list.contains(cn.getPackageName())) list.add(flat);
        saveShortcuts(list);
    }

    private void hidePanel() {
        if (!panelShown) return;
        try { wm.removeView(panel); } catch (RuntimeException ignored) { }
        panelShown = false;
    }

    private void showBrowser() { openBrowser(PICK_NONE); }

    /** The app browser in "pick" mode: tapping an app adds it to the shortcuts instead of opening it. */
    private void showPicker() { openBrowser(PICK_SHORTCUT); }

    /** Pick the main display app: any app can run in the big window (Google Maps, Waze, ...). */
    private void showNavPicker() { openBrowser(PICK_NAV); }

    /** Pick the app the assistant button opens (Google Assistant, Gemini, Home Assistant, ...). */
    private void showAssistantPicker() { openBrowser(PICK_ASSISTANT); }

    private void openBrowser(int pick) {
        if (browserShown || !Settings.canDrawOverlays(this)) return;
        browser = new BrowserView(this, pick);
        // In the main pane, beside the panel, like an app opened there.
        int width = wm.getMaximumWindowMetrics().getBounds().width() - panelWidth();
        wm.addView(browser, overlayParams(width, Gravity.TOP | (panelLeft ? Gravity.END : Gravity.START), true));
        browserShown = true;
    }

    private void hideBrowser() {
        if (!browserShown) return;
        browser.hideKeyboard();
        try { wm.removeView(browser); } catch (RuntimeException ignored) { }
        browserShown = false;
        browser = null;
    }

    // ---- layouts ----

    /** The activity itself: a slot under the Maps window (tap to reopen Maps) and empty space under the panel. */
    private View buildBackdrop() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        mapSlot = text(appName(navApp()) + "\nTap to open", 18, DIM);
        mapSlot.setGravity(Gravity.CENTER);
        mapSlot.setBackground(rounded(CARD, 16));
        mapSlot.setOnClickListener(v -> launchMaps());
        root.addView(mapSlot);
        main.post(this::updateMapSlot);
        return root;
    }

    private void updateMapSlot() {
        if (mapSlot == null) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                wm.getMaximumWindowMetrics().getBounds().width() - panelWidth() - dp(12), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(8), dp(8), dp(4), dp(8));
        mapSlot.setLayoutParams(lp);
        ((LinearLayout) mapSlot.getParent()).setGravity(panelLeft ? Gravity.END : Gravity.START);
    }

    private View buildPanel() {
        int n = columns;
        wifiBars = cellBars = null;   // recreated below only if the indicators are shown
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(panelBackground());
        // Extra padding on the screen-edge side keeps buttons clear of the edge Back-gesture strip.
        p.setPadding(dp(panelLeft ? PAD_EDGE : PAD_INNER), dp(4), dp(panelLeft ? PAD_INNER : PAD_EDGE), dp(4));

        // Clock and date shrink and shorten as the panel narrows; weather sits beside them when there's room.
        boolean showClock = prefs.getBoolean("show_clock", true);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout clockBox = new LinearLayout(this);
        // Wide panels: date beside the clock on one line, leaving the height for media and shortcuts.
        clockBox.setOrientation(n >= 3 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        clockBox.setBaselineAligned(true);
        // 12-hour with AM/PM unless 24-hour time is turned on in settings (independent of Android's setting).
        boolean h24 = prefs.getBoolean("clock_24h", false);
        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.HORIZONTAL);
        time.setBaselineAligned(true);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour(h24 ? "H:mm" : "h:mm");
        clock.setFormat24Hour(h24 ? "H:mm" : "h:mm");
        style(clock, n >= 3 ? 28 : 24, TEXT);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setSingleLine(true);
        time.addView(clock);
        time.setBaselineAlignedChildIndex(0);   // the date lines up with the clock digits
        if (!h24) {
            TextClock ampm = new TextClock(this);
            ampm.setFormat12Hour("a");
            ampm.setFormat24Hour("a");
            style(ampm, 13, TEXT);
            ampm.setSingleLine(true);
            ampm.setPadding(dp(4), 0, 0, 0);
            time.addView(ampm);
        }
        TextClock date = new TextClock(this);
        String dateFormat = n >= 4 ? "EEE, MMM d" : "MMM d";
        date.setFormat12Hour(dateFormat);
        date.setFormat24Hour(dateFormat);
        style(date, n >= 3 ? 13 : 12, 0xFFDCE4E7);   // near-white: DIM is hard to read over a wallpaper
        date.setSingleLine(true);
        if (n >= 3) date.setPadding(dp(6), 0, 0, 0);
        clockBox.addView(time);
        clockBox.addView(date);
        if (!showClock) clockBox.setVisibility(View.GONE);
        header.addView(clockBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        weatherTemp = null;
        weatherRange = null;
        if (n >= 3) {   // weather needs at least three icons' width beside the clock
            LinearLayout wx = new LinearLayout(this);
            wx.setOrientation(LinearLayout.HORIZONTAL);
            wx.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            wx.setPadding(dp(6), statusBarHeight(), dp(4), 0);   // keep clear of the status bar's icons
            weatherTemp = text("", 20, TEXT);
            weatherTemp.setSingleLine(true);
            wx.addView(weatherTemp);
            if (n >= 4) {
                weatherRange = text("", 13, DIM);
                weatherRange.setSingleLine(true);
                weatherRange.setPadding(dp(8), 0, 0, 0);
                wx.addView(weatherRange);
            }
            header.addView(wx);
        }
        if (showClock || weatherTemp != null) p.addView(header);

        if ("top".equals(signalPlace())) {
            View sig = n >= 3 ? signalsRow() : signalsStack();
            p.addView(sig, marginTop(LinearLayout.LayoutParams.MATCH_PARENT, 4));
        }
        if (prefs.getBoolean("show_media", true) && !settingsOpen) {   // settings get the room while open
            p.addView(buildMediaCard(n), marginTop(LinearLayout.LayoutParams.MATCH_PARENT, 4));
        } else {
            mediaTitle = new TextView(this);     // keep the now-playing code working with nothing shown
            mediaArtist = new TextView(this);
            mediaArt = new ImageView(this);
            playButton = new TextView(this);
            progressRow = null;
        }

        // Shortcuts (or the settings) scroll when they no longer fit, e.g. one icon per row.
        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(settingsOpen ? buildSettings(n) : buildShortcuts(n));
        p.addView(scroller, weighted(4));
        // The bottom bar holds only the voice button and bottom signal bars; settings open from the Options tile.
        if (prefs.getBoolean("show_mic", false) || "bottom".equals(signalPlace())) {
            p.addView(buildBottomBar(n), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return p;
    }

    private int statusBarHeight() {
        int top = wm.getMaximumWindowMetrics().getWindowInsets().getInsets(WindowInsets.Type.statusBars()).top;
        if (top == 0) {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id != 0) top = getResources().getDimensionPixelSize(id);
        }
        return top;
    }

    // ---- Display size ----
    // Android's display density, which sizes every other app (Maps, the music player...). The panel itself
    // keeps the same size at any density (see UI_SCALE). Needs WRITE_SECURE_SETTINGS, granted over ADB.
    private static final int DEFAULT_DENSITY = 160;
    private static final int[] DENSITIES = {120, 140, 160, 180, 200};

    private void setDensity(int dpi) {
        try {
            Object wms = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            wms.getClass().getMethod("setForcedDisplayDensityForUser", int.class, int.class, int.class)
                    .invoke(wms, 0 /* default display */, dpi, 0 /* the box has one user */);
            prefs.edit().putInt("density", dpi).apply();
        } catch (Exception e) {
            Log.w("MiniCarHome", "setDensity failed", e);
            Toast.makeText(this, "Display size needs setup: adb shell pm grant com.kynrek.minicarhome "
                    + "android.permission.WRITE_SECURE_SETTINGS", Toast.LENGTH_LONG).show();
        }
    }

    // ---- Wallpaper behind the panel ----
    // The picture lives in the app's external files folder so it can also be pushed over ADB:
    //   adb push picture.jpg /sdcard/Android/data/com.kynrek.minicarhome/files/wallpaper.jpg
    private static final int PICK_WALLPAPER = 41;
    private static final int WALLPAPER_SCRIM = 0x660E1416;   // darkens the picture a little so text stays readable
    private Bitmap wallpaperCache;
    private long wallpaperStamp;

    private File wallpaperFile() {
        return new File(getExternalFilesDir(null), "wallpaper.jpg");
    }

    private Drawable panelBackground() {
        File f = wallpaperFile();
        if (!f.exists()) return new ColorDrawable(BG);
        if (wallpaperCache == null || wallpaperStamp != f.lastModified()) {
            wallpaperCache = BitmapFactory.decodeFile(f.getPath());
            wallpaperStamp = f.lastModified();
        }
        if (wallpaperCache == null) return new ColorDrawable(BG);
        // Crop the middle of the picture to the panel's shape, so it fills without stretching.
        int w = panelWidth(), h = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds().height();
        int bw = wallpaperCache.getWidth(), bh = wallpaperCache.getHeight();
        int cw = bw, ch = bw * h / w;
        if (ch > bh) { ch = bh; cw = bh * w / h; }
        Bitmap crop = Bitmap.createBitmap(wallpaperCache, (bw - cw) / 2, (bh - ch) / 2, cw, ch);
        BitmapDrawable pic = new BitmapDrawable(getResources(), Bitmap.createScaledBitmap(crop, w, h, true));
        return new LayerDrawable(new Drawable[]{pic, new ColorDrawable(WALLPAPER_SCRIM)});
    }

    private void pickWallpaper() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        hidePanel();   // the panel floats above every app, so get it out of the picker's way
        try {
            startActivityForResult(i, PICK_WALLPAPER);
        } catch (Exception e) {
            showPanel();
            Toast.makeText(this, "No picture picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_WALLPAPER) return;
        if (result == RESULT_OK && data != null && data.getData() != null) {
            try {
                // Scale big photos down while copying; the panel is only a few hundred pixels wide.
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(data.getData())) { BitmapFactory.decodeStream(in, null, o); }
                o.inSampleSize = 1;
                while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= 1400) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                Bitmap b;
                try (InputStream in = getContentResolver().openInputStream(data.getData())) { b = BitmapFactory.decodeStream(in, null, o); }
                try (FileOutputStream out = new FileOutputStream(wallpaperFile())) { b.compress(Bitmap.CompressFormat.JPEG, 90, out); }
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't use that picture", Toast.LENGTH_SHORT).show();
            }
        }
        rebuildPanel();
        showPanel();
    }

    private void removeWallpaper() {
        wallpaperFile().delete();
        wallpaperCache = null;
        rebuildPanel();
    }

    private void rebuildPanel() {
        boolean shown = panelShown;
        hidePanel();
        panel = buildPanel();
        if (shown) showPanel();
        showMedia();
        updateSignals();
        showWeather();
    }

    // ---- weather ----

    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            Weather last = Weather.cached(prefs);
            if (last == null || System.currentTimeMillis() - last.fetchedAt > WEATHER_REFRESH_MS - 60_000) {
                Context app = getApplicationContext();
                new Thread(() -> {
                    Weather w = Weather.fetch(app);
                    if (w != null) {
                        w.save(prefs);
                        main.post(HomeActivity.this::showWeather);
                    }
                }, "weather").start();
            }
            main.postDelayed(this, WEATHER_REFRESH_MS);
        }
    };

    private void showWeather() {
        if (weatherTemp == null) return;
        Weather w = Weather.cached(prefs);
        if (w == null) {
            weatherTemp.setText("");
            if (weatherRange != null) weatherRange.setText("");
            return;
        }
        weatherTemp.setText(w.symbol() + " " + w.temp + "°");
        if (weatherRange != null) weatherRange.setText("H " + w.high + "°  L " + w.low + "°");
    }

    private LinearLayout.LayoutParams weighted(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private View buildSettings(int n) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackground(rounded(CARD, 16));
        s.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text("Options", 16, ACCENT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(choice("Done", true, v -> { settingsOpen = false; rebuildPanel(); }));
        s.addView(top);
        s.addView(text(n >= 2 ? "Icons per row" : "Icons", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        LinearLayout stepper = new LinearLayout(this);
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = control("−");
        TextView value = text(String.valueOf(n), 22, ACCENT);
        value.setGravity(Gravity.CENTER);
        TextView plus = control("+");
        minus.setMinWidth(dp(32));
        plus.setMinWidth(dp(32));
        minus.setOnClickListener(v -> setColumns(columns - 1));
        plus.setOnClickListener(v -> setColumns(columns + 1));
        stepper.addView(minus);
        stepper.addView(value, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        stepper.addView(plus);
        s.addView(stepper, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        s.addView(text("Side", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout sides = new LinearLayout(this);
        sides.setOrientation(LinearLayout.HORIZONTAL);
        sides.addView(choice("Left", panelLeft, v -> setPanelLeft(true)));
        sides.addView(choice("Right", !panelLeft, v -> setPanelLeft(false)));
        s.addView(sides, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        s.addView(text(n >= 3 ? "Display size (other apps)" : "Display size", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        HorizontalScrollView dsScroll = new HorizontalScrollView(this);
        dsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout ds = new LinearLayout(this);
        ds.setOrientation(LinearLayout.HORIZONTAL);
        int dpi = getResources().getConfiguration().densityDpi;
        for (int d : DENSITIES) ds.addView(choice(String.valueOf(d), d == dpi, v -> setDensity(d)));
        dsScroll.addView(ds);
        s.addView(dsScroll, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        s.addView(text("Wallpaper", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout wp = new LinearLayout(this);
        wp.setOrientation(LinearLayout.HORIZONTAL);
        wp.addView(choice("Choose", false, v -> pickWallpaper()));
        wp.addView(choice("None", !wallpaperFile().exists(), v -> removeWallpaper()));
        s.addView(wp, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        s.addView(text(n >= 3 ? "Main display app" : "Main app", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.addView(choice(appName(navApp()), true, v -> showNavPicker()));
        nav.addView(choice("Change", false, v -> showNavPicker()));
        s.addView(nav, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(toggleRow(n >= 3 ? "Open apps in main window" : "Apps in main", "apps_in_pane"),
                marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        s.addView(toggleRow("Clock & date", "show_clock"), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        s.addView(toggleRow(n >= 3 ? "24-hour time" : "24-hour", "clock_24h", false), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 6));
        s.addView(toggleRow("Media", "show_media"), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 6));
        s.addView(toggleRow("Assistant", "show_mic", false), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 6));
        if (prefs.getBoolean("show_mic", false)) {
            LinearLayout as = new LinearLayout(this);
            as.setOrientation(LinearLayout.HORIZONTAL);
            String app = assistantApp(), mode = assistantMode();
            as.addView(choice("Built-in", "builtin".equals(mode), v -> setString("assistant_mode", "builtin")));
            as.addView(choice("Android", "system".equals(mode), v -> setString("assistant_mode", "system")));
            as.addView(choice(app == null ? "App…" : appName(app), "app".equals(mode), v -> {
                if (app == null || "app".equals(mode)) showAssistantPicker();
                else setString("assistant_mode", "app");
            }));
            HorizontalScrollView asScroll = new HorizontalScrollView(this);
            asScroll.setHorizontalScrollBarEnabled(false);
            asScroll.addView(as);
            s.addView(asScroll, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        }

        s.addView(text("Media buttons", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout mb = new LinearLayout(this);
        mb.setOrientation(LinearLayout.HORIZONTAL);
        String buttons = prefs.getString("media_buttons", "both");
        mb.addView(choice("Both", "both".equals(buttons), v -> setString("media_buttons", "both")));
        mb.addView(choice("Track", "track".equals(buttons), v -> setString("media_buttons", "track")));
        mb.addView(choice("Seek", "seek".equals(buttons), v -> setString("media_buttons", "seek")));
        s.addView(mb, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(text("Seek seconds", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        HorizontalScrollView seekScroll = new HorizontalScrollView(this);
        seekScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sk = new LinearLayout(this);
        sk.setOrientation(LinearLayout.HORIZONTAL);
        for (int secs : new int[]{5, 10, 15, 30, 60}) {
            sk.addView(choice(String.valueOf(secs), secs == seekSeconds(), v -> {
                prefs.edit().putInt("seek_seconds", secs).apply();
                rebuildPanel();
            }));
        }
        seekScroll.addView(sk);
        s.addView(seekScroll, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(text("Signal", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout sig = new LinearLayout(this);
        sig.setOrientation(LinearLayout.HORIZONTAL);
        String place = signalPlace();
        sig.addView(choice("Off", "off".equals(place), v -> setSignalPlace("off")));
        sig.addView(choice("Top", "top".equals(place), v -> setSignalPlace("top")));
        sig.addView(choice("Bottom", "bottom".equals(place), v -> setSignalPlace("bottom")));
        s.addView(sig, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        TextView edit = choice(n >= 3 ? "Edit shortcuts" : "Edit apps", false, v -> {
            settingsOpen = false;
            editing = true;
            rebuildPanel();
        });
        s.addView(edit, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        TextView reset = choice(n >= 3 ? "Reset shortcuts" : "Reset apps", false, v -> {
            prefs.edit().remove("shortcuts").apply();   // back to the built-in default list
            settingsOpen = false;
            rebuildPanel();
        });
        s.addView(reset, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        TextView done = text("Done", 15, ACCENT);
        done.setPadding(0, dp(10), 0, dp(4));
        done.setOnClickListener(v -> { settingsOpen = false; rebuildPanel(); });
        s.addView(done);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(6), 0, 0);
        wrap.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** "Label  [On]" row; tapping the chip flips a saved on/off setting. */
    private View toggleRow(String label, String key) { return toggleRow(label, key, true); }

    private View toggleRow(String label, String key, boolean byDefault) {
        boolean on = prefs.getBoolean(key, byDefault);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, 14, TEXT);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(choice(on ? "On" : "Off", on, v -> setPref(key, !on)));
        return row;
    }

    private TextView choice(String label, boolean selected, View.OnClickListener click) {
        TextView c = text(label, 14, selected ? BG : TEXT);
        c.setGravity(Gravity.CENTER);
        c.setBackground(rounded(selected ? ACCENT : 0xFF26323A, 12));
        c.setPadding(dp(12), dp(6), dp(12), dp(6));
        c.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        c.setLayoutParams(lp);
        return c;
    }

    private View buildShortcuts(int n) {
        List<View> tiles = new ArrayList<>();
        // Always first: the main display app's icon, which goes back home with it in the main window.
        View home = appTile(navApp());
        home.setOnClickListener(v -> goHome());
        tiles.add(home);
        List<String> list = shortcuts();
        for (int i = 0; i < list.size(); i++) {
            String entry = list.get(i);
            if (navApp().equals(entry.split("/")[0])) continue;   // already the first tile
            View t = null;
            if (entry.contains("/")) t = activityTile(ComponentName.unflattenFromString(entry));
            else if (pm.getLaunchIntentForPackage(entry) != null) t = appTile(entry);
            if (t == null) continue;   // app was uninstalled
            tiles.add(editing ? editable(t, i, list.size()) : t);
        }
        if (editing) {
            tiles.add(iconTile("+", "Add", v -> showPicker()));
            tiles.add(iconTile("✓", "Done", v -> { editing = false; rebuildPanel(); }));
        } else {
            tiles.add(iconTile("☷", "All apps", v -> showBrowser()));
            tiles.add(optionsTile());
        }
        return rows(tiles, n);
    }

    /** Wrap a shortcut tile with move-left, remove and move-right buttons for edit mode. */
    private View editable(View tile, int index, int count) {
        tile.setOnClickListener(null);
        LinearLayout box = column();
        box.addView(tile);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.addView(editButton("◀", index > 0, v -> moveShortcut(index, -1)));
        bar.addView(editButton("✕", true, v -> removeShortcut(index)));
        bar.addView(editButton("▶", index < count - 1, v -> moveShortcut(index, 1)));
        box.addView(bar);
        return box;
    }

    private TextView editButton(String glyph, boolean enabled, View.OnClickListener click) {
        TextView b = text(glyph, 13, enabled ? ACCENT : 0xFF3A474D);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(24));
        b.setPadding(0, dp(4), 0, dp(4));
        if (enabled) b.setOnClickListener(click);
        return b;
    }

    /**
     * Bottom bar (only when needed): the voice button and (when placed at the bottom) the
     * Wi-Fi and cell indicators on the right. One row when it fits, stacked otherwise.
     * Kept clear of the bottom-edge Home gesture strip.
     */
    private View buildBottomBar(int n) {
        boolean mic = prefs.getBoolean("show_mic", false);
        boolean signals = "bottom".equals(signalPlace());
        boolean oneRow = n >= 4 || (n == 3 && !mic);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(0, dp(2), dp(4), dp(6));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        voiceStatus = text("", 13, TEXT);   // what built-in voice heard, left of the mic
        voiceStatus.setSingleLine(true);
        voiceStatus.setEllipsize(TextUtils.TruncateAt.START);
        voiceStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        voiceStatus.setPadding(dp(4), 0, dp(8), 0);
        buttons.addView(voiceStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (mic) {
            LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(36), dp(36));
            if (signals && oneRow) micLp.rightMargin = dp(16);
            buttons.addView(assistantButton(), micLp);
        }
        // One row when it fits (3 icons without the assistant, or 4); else Wi-Fi and cell under the buttons.
        if (signals && oneRow) {
            box.setOrientation(LinearLayout.HORIZONTAL);
            buttons.addView(signalsRow());
            box.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            box.setOrientation(LinearLayout.VERTICAL);
            box.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (signals) {
                View sig = n >= 3 ? signalsRow() : signalsStack();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(4);
                box.addView(sig, lp);
            }
        }
        return box;
    }

    private String signalPlace() { return prefs.getString("signals", "off"); }   // Android's status bar already shows them

    private View assistantButton() {
        ImageView assistant = new ImageView(this);
        // No generated R class in this Gradle-free build, so look the icon up by name.
        assistant.setImageResource(getResources().getIdentifier("ic_mic", "drawable", getPackageName()));
        assistant.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable dot = rounded(listening ? LISTENING : ACCENT, 0);
        dot.setShape(GradientDrawable.OVAL);
        assistant.setBackground(dot);
        assistant.setContentDescription("Assistant");
        assistant.setOnClickListener(v -> startAssistant());
        micDot = dot;
        return assistant;
    }

    private void newSignalViews() {
        wifiBars = new SignalBars(this, TEXT, 0xFF3A474D);
        wifiLabel = text("Wi-Fi", 13, DIM);
        cellBars = new SignalBars(this, TEXT, 0xFF3A474D);
        cellLabel = text("", 13, DIM);
    }

    /** Wi-Fi and cell side by side (wide panels). */
    private View signalsRow() {
        newSignalViews();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.addView(wifiLabel);
        row.addView(wifiBars, barParams());
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(20), 1));
        row.addView(cellLabel);
        row.addView(cellBars, barParams());
        return row;
    }

    /** Wi-Fi above cell (narrow panels). */
    private View signalsStack() {
        newSignalViews();
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.END);
        stack.addView(signalLine(wifiLabel, wifiBars));
        stack.addView(signalLine(cellLabel, cellBars));
        return stack;
    }

    private View signalLine(TextView label, SignalBars bars) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        line.setPadding(0, dp(2), 0, dp(2));
        label.setSingleLine(true);
        line.addView(label);
        line.addView(bars, barParams());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        line.setLayoutParams(lp);
        return line;
    }

    private LinearLayout.LayoutParams barParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(16));
        lp.leftMargin = dp(6);
        return lp;
    }

    /** Start the system assistant listening ("navigate to Walmart", "call Stacy", ...). */
    private void startAssistant() {
        String mode = assistantMode();
        if ("builtin".equals(mode)) { toggleListening(); return; }
        String app = "app".equals(mode) ? assistantApp() : null;
        String[] actions = {"android.intent.action.VOICE_ASSIST", Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST};
        for (String a : actions) {
            Intent i = new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (app != null) i.setPackage(app);   // the chosen app's voice screen, if it has one
            if (i.resolveActivity(pm) == null) continue;
            try {
                startActivity(i);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "assistant " + a + " failed", e);
            }
        }
        if (app != null) { open(app); return; }   // no voice screen: just open the app
        Toast.makeText(this, "No assistant available", Toast.LENGTH_SHORT).show();
    }

    // ---- built-in voice navigation ----
    // Listens with Android's speech recognizer while the map stays in view, shows what it heard on the panel,
    // and sends the destination straight to the main display app, which starts the route itself.

    private static final int LISTENING = 0xFFE5534B;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private TextView voiceStatus;
    private GradientDrawable micDot;
    private final Runnable clearVoiceStatus = () -> showVoiceStatus("");

    private void toggleListening() {
        if (listening) {
            recognizer.cancel();
            setListening(false);
            showVoiceStatus("");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showVoiceStatus("Voice isn't available");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(voiceListener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        setListening(true);
        showVoiceStatus("Where to?");
        recognizer.startListening(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) toggleListening();
    }

    private void setListening(boolean on) {
        listening = on;
        if (micDot != null) micDot.setColor(on ? LISTENING : ACCENT);
    }

    private void showVoiceStatus(String s) {
        main.removeCallbacks(clearVoiceStatus);
        if (voiceStatus != null) voiceStatus.setText(s);
        if (!s.isEmpty() && !listening) main.postDelayed(clearVoiceStatus, 6000);
    }

    private final RecognitionListener voiceListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { showVoiceStatus("Listening…"); }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onPartialResults(Bundle partial) {
            String heard = firstResult(partial);
            if (heard != null && !heard.isEmpty()) showVoiceStatus(heard);
        }

        @Override public void onResults(Bundle results) {
            setListening(false);
            String heard = firstResult(results);
            if (heard == null || heard.trim().isEmpty()) { showVoiceStatus("Didn't catch that"); return; }
            navigateTo(destination(heard));
        }

        @Override public void onError(int error) {
            setListening(false);
            showVoiceStatus(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ? "Didn't catch that" : "Voice error " + error);
        }
    };

    private static String firstResult(Bundle b) {
        List<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    /** "Navigate to In-N-Out", "take me to Costco", "directions to work" or just "In-N-Out": the place. */
    static String destination(String heard) {
        String s = heard.trim().replaceAll("[.!?]+$", "");
        String place = s.replaceFirst("(?i)^(please\\s+)?(navigate|navigation|directions|drive|take me|get me|go|head|route)"
                + "(\\s+me)?(\\s+to)?\\s+", "");
        return place.isEmpty() ? s : place;
    }

    /** Start the route in the main display app: Waze and Google Maps go straight to the best match. */
    private void navigateTo(String place) {
        String pkg = navApp(), q = Uri.encode(place);
        Intent i = new Intent(Intent.ACTION_VIEW).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if ("com.waze".equals(pkg)) i.setData(Uri.parse("https://waze.com/ul?q=" + q + "&navigate=yes"));
        else if (MAPS.equals(pkg)) i.setData(Uri.parse("google.navigation:q=" + q));
        else i.setData(Uri.parse("geo:0,0?q=" + q));
        if (i.resolveActivity(pm) == null) { showVoiceStatus("Can't navigate with " + appName(pkg)); return; }
        showVoiceStatus("→ " + place);
        paneApp = null;   // the main display app comes back over whatever was open
        paneTarget = pkg;
        if (openedAt.containsKey(pkg)) {
            startPane(i, WINDOWING_MODE_MULTI_WINDOW);   // its window is already open
        } else {
            launchMaps();   // open its window first, then send the destination once it's settled
            main.postDelayed(() -> startPane(i, WINDOWING_MODE_MULTI_WINDOW), 1500);
        }
    }

    // ---- signal indicators ----

    private final Runnable signalTick = new Runnable() {
        @Override public void run() {
            updateSignals();
            main.postDelayed(this, SIGNAL_REFRESH_MS);
        }
    };

    @SuppressWarnings("deprecation")
    private void updateSignals() {
        if (wifiBars == null) return;   // indicators turned off
        // WifiManager redacts the connection for apps without location access, so find the Wi-Fi
        // network through ConnectivityManager; its WifiInfo still carries the signal strength.
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        WifiInfo info = null;
        if (cm != null) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.getTransportInfo() instanceof WifiInfo) {
                    info = (WifiInfo) caps.getTransportInfo();
                    break;
                }
            }
        }
        boolean wifiUp = wifi != null && info != null && info.getRssi() > -127;
        if (wifiUp) {
            int max = wifi.getMaxSignalLevel();
            int lvl = wifi.calculateSignalLevel(info.getRssi());   // 0..max
            wifiBars.setLevel(Math.max(1, Math.round(4f * lvl / Math.max(1, max))));
            wifiLabel.setText("Wi-Fi");
            wifiLabel.setTextColor(TEXT);
        } else {
            wifiBars.setLevel(-1);
            wifiLabel.setText("No Wi-Fi");
            wifiLabel.setTextColor(DIM);
        }

        TelephonyManager tel = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        SignalStrength ss = tel == null ? null : tel.getSignalStrength();
        String operator = tel == null ? "" : tel.getNetworkOperatorName();
        if (ss != null && operator != null && !operator.isEmpty()) {
            cellBars.setLevel(ss.getLevel());   // 0..4
            String type = networkType(tel);
            if (columns <= 3) cellLabel.setText(type.isEmpty() ? "Cell" : type);   // narrower panels: just "LTE"
            else cellLabel.setText(type.isEmpty() ? operator.trim() : operator.trim() + " · " + type);
            cellLabel.setTextColor(TEXT);
        } else {
            cellBars.setLevel(-1);
            cellLabel.setText("No service");
            cellLabel.setTextColor(DIM);
        }
    }

    private static String networkType(TelephonyManager tel) {
        int t;
        try {
            t = tel.getDataNetworkType();   // needs READ_PHONE_STATE, granted over ADB
        } catch (SecurityException e) {
            return "";
        }
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
            case TelephonyManager.NETWORK_TYPE_HSPAP: case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA: case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_UMTS: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE: case TelephonyManager.NETWORK_TYPE_GPRS: return "2G";
            default: return "";
        }
    }

    private View rows(List<View> tiles, int perRow) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int n = 0; n < tiles.size(); n++) {
            if (n % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, marginTop(LinearLayout.LayoutParams.MATCH_PARENT, 4));
            }
            row.addView(tiles.get(n), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        for (int pad = tiles.size() % perRow; pad != 0 && pad < perRow; pad++) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        }
        return grid;
    }

    /**
     * Now-playing card. 3+ icons wide: artwork beside title, artist and controls.
     * 2 icons wide: small artwork beside the title, with the controls on their own row.
     */
    private View buildMediaCard(int n) {
        boolean wide = n >= 3;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(wallpaperFile().exists() ? 0xB31B2428 : CARD, 16));   // let a wallpaper show through
        card.setPadding(dp(8), dp(8), dp(8), dp(6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        mediaArt = new ImageView(this);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArt.setBackground(rounded(0xFF26323A, 10));
        mediaArt.setClipToOutline(true);
        int art = n >= 4 ? 56 : n == 3 ? 48 : 40;
        top.addView(mediaArt, new LinearLayout.LayoutParams(dp(art), dp(art)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10), 0, 0, 0);
        mediaTitle = text("Nothing playing", wide ? 16 : 14, TEXT);
        mediaTitle.setSingleLine(true);
        mediaTitle.setEllipsize(TextUtils.TruncateAt.END);
        mediaArtist = text("", wide ? 13 : 12, DIM);
        mediaArtist.setSingleLine(true);
        mediaArtist.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(mediaTitle);
        info.addView(mediaArtist);

        // Buttons: skip track (|◀ ▶|) and/or seek by the chosen seconds (↺15 15↻), per settings.
        String buttons = prefs.getString("media_buttons", "both");
        boolean track = !"seek".equals(buttons), seek = !"track".equals(buttons);
        int secs = seekSeconds();
        boolean five = track && seek;
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        playButton = mediaButton("▶", wide, five);
        playButton.setOnClickListener(v -> togglePlay());
        if (track) {
            TextView prev = mediaButton("|◀", wide, five);
            prev.setContentDescription("Previous track");
            prev.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().skipToPrevious(); });
            controls.addView(prev);
        }
        if (seek) {
            TextView back = mediaButton("↺", wide, five);
            back.setContentDescription("Back " + secs + " seconds");
            back.setOnClickListener(v -> seekBy(-secs * 1000L));
            controls.addView(back);
        }
        controls.addView(playButton);
        if (seek) {
            TextView fwd = mediaButton("↻", wide, five);
            fwd.setContentDescription("Forward " + secs + " seconds");
            fwd.setOnClickListener(v -> seekBy(secs * 1000L));
            controls.addView(fwd);
        }
        if (track) {
            TextView next = mediaButton("▶|", wide, five);
            next.setContentDescription("Next track");
            next.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().skipToNext(); });
            controls.addView(next);
        }

        // Three buttons fit beside the artwork on wide panels; five get their own row.
        if (wide && !five) info.addView(controls);
        top.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(top);
        if (!wide || five) card.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Progress bar with time remaining, shown when the app reports a track length.
        progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(0, dp(2), 0, 0);
        mediaProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mediaProgress.setMax(1000);
        mediaProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        mediaProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A474D));
        progressRow.addView(mediaProgress, new LinearLayout.LayoutParams(0, dp(6), 1));
        mediaRemaining = text("", 12, DIM);
        mediaRemaining.setPadding(dp(8), 0, 0, 0);
        mediaRemaining.setSingleLine(true);
        progressRow.addView(mediaRemaining);
        progressRow.setVisibility(View.GONE);
        card.addView(progressRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(v -> { if (controller != null) open(controller.getPackageName()); });
        return card;
    }

    /** Full-screen app browser: search, recently opened apps, then every app A-Z. */
    private class BrowserView extends FrameLayout {
        private final EditText search;
        private final LinearLayout recentRow;
        private final View recentSection;
        private final List<ResolveInfo> all = new ArrayList<>();
        private final List<ResolveInfo> shown = new ArrayList<>();
        private final BaseAdapter adapter;

        private final int pick;

        BrowserView(Context c, int pick) {
            super(c);
            this.pick = pick;
            setBackgroundColor(BG);
            setFocusableInTouchMode(true);
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(12), dp(20), dp(12));
            addView(box);

            LinearLayout header = new LinearLayout(c);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView close = text("✕", 22, TEXT);
            close.setPadding(dp(6), dp(4), dp(18), dp(4));
            close.setOnClickListener(v -> hideBrowser());
            header.addView(close);
            search = new EditText(c);
            search.setHint(pick == PICK_SHORTCUT ? "Add a shortcut: search apps"
                    : pick == PICK_NAV ? "Main display app: search apps"
                    : pick == PICK_ASSISTANT ? "Assistant app: search apps" : "Search apps");
            search.setSingleLine(true);
            style(search, 17, TEXT);
            search.setHintTextColor(DIM);
            search.setBackground(rounded(CARD, 12));
            search.setPadding(dp(14), dp(8), dp(14), dp(8));
            header.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(header);

            LinearLayout recent = new LinearLayout(c);
            recent.setOrientation(LinearLayout.VERTICAL);
            recent.addView(sectionLabel("RECENT"));
            HorizontalScrollView scroller = new HorizontalScrollView(c);
            scroller.setHorizontalScrollBarEnabled(false);
            recentRow = new LinearLayout(c);
            recentRow.setOrientation(LinearLayout.HORIZONTAL);
            scroller.addView(recentRow);
            recent.addView(scroller);
            recentSection = recent;
            box.addView(recent);
            box.addView(sectionLabel("ALL APPS"));

            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                if (!r.activityInfo.packageName.equals(getPackageName())) all.add(r);
            }
            Collections.sort(all, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
            shown.addAll(all);

            GridView grid = new GridView(c);
            grid.setNumColumns(GridView.AUTO_FIT);
            grid.setColumnWidth(dp(96));
            grid.setVerticalSpacing(dp(8));
            adapter = new BaseAdapter() {
                @Override public int getCount() { return shown.size(); }
                @Override public Object getItem(int i) { return shown.get(i); }
                @Override public long getItemId(int i) { return i; }
                @Override public View getView(int i, View convert, ViewGroup parent) {
                    ResolveInfo r = shown.get(i);
                    return tile(r.loadIcon(pm), r.loadLabel(pm).toString(), v -> choose(component(r)));
                }
            };
            grid.setAdapter(adapter);
            box.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int d) { filter(s.toString()); }
                @Override public void afterTextChanged(Editable s) { }
            });
            fillRecent();
        }

        private void filter(String q) {
            String needle = q.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (ResolveInfo r : all) {
                if (needle.isEmpty() || r.loadLabel(pm).toString().toLowerCase(Locale.ROOT).contains(needle)) shown.add(r);
            }
            recentSection.setVisibility(needle.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.notifyDataSetChanged();
        }

        private void fillRecent() {
            recentRow.removeAllViews();
            for (String flat : recentList()) {
                ComponentName cn = ComponentName.unflattenFromString(flat);
                if (cn == null) continue;
                try {
                    Drawable icon = pm.getActivityIcon(cn);
                    CharSequence label = pm.getActivityInfo(cn, 0).loadLabel(pm);
                    recentRow.addView(tile(icon, label.toString(), v -> choose(cn)), new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
                } catch (PackageManager.NameNotFoundException ignored) { }
            }
            recentSection.setVisibility(recentRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        }

        private void choose(ComponentName cn) {
            if (pick == PICK_SHORTCUT) {
                hideBrowser();
                addShortcut(cn);
            } else if (pick == PICK_NAV) {
                hideBrowser();
                setNavApp(cn.getPackageName());
            } else if (pick == PICK_ASSISTANT) {
                hideBrowser();
                prefs.edit().putString("assistant_app", cn.getPackageName()).putString("assistant_mode", "app").apply();
                rebuildPanel();
            } else {
                launch(cn);
            }
        }

        void hideKeyboard() {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) hideBrowser();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    private static ComponentName component(ResolveInfo r) {
        return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
    }

    // ---- launching ----

    private void open(String pkg) {
        if (navApp().equals(pkg)) { launchMaps(); return; }
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) launch(i.getComponent());
    }

    private void launch(ComponentName cn) {
        hideBrowser();
        paneLaunchedAt = 0;   // a full-screen launch (Box Tools' icons) is meant to cover the home screen
        if (cn == null) return;
        remember(cn);
        if (navApp().equals(cn.getPackageName())) { launchMaps(); return; }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (opensInPane(cn)) {
            String real = TRAMPOLINES.get(cn.flattenToString());
            // The real screen has no MAIN/LAUNCHER filter, and Android 14 blocks explicit intents that don't
            // match a component's filters, so open it with a plain intent.
            Intent target = real == null ? i : new Intent().setComponent(ComponentName.unflattenFromString(real))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchInPane(target, cn.getPackageName());
            return;
        }
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Can't open: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private List<String> recentList() {
        List<String> out = new ArrayList<>();
        String saved = prefs.getString("recent", "");
        if (!saved.isEmpty()) Collections.addAll(out, saved.split("\\|"));
        return out;
    }

    private void remember(ComponentName cn) {
        List<String> list = recentList();
        String flat = cn.flattenToString();
        list.remove(flat);
        list.add(0, flat);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        prefs.edit().putString("recent", TextUtils.join("|", list)).apply();
    }

    // ---- now playing ----

    private ComponentName listenerName() { return new ComponentName(this, MediaListener.class); }

    private void pickController() {
        List<MediaController> list;
        try {
            list = sessions.getActiveSessions(listenerName());
        } catch (SecurityException e) {
            return;
        }
        MediaController best = null;
        for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            if (best == null) best = c;
        }
        setController(best);
    }

    private void setController(MediaController c) {
        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = c;
        if (controller != null) controller.registerCallback(mediaCallback, main);
        showMedia();
    }

    private void showMedia() {
        if (mediaTitle == null) return;
        if (controller == null) {
            mediaTitle.setText("Nothing playing");
            mediaArtist.setText("");
            mediaArt.setImageDrawable(null);
            playButton.setText("▶");
            return;
        }
        MediaMetadata m = controller.getMetadata();
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null && m != null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        mediaTitle.setText(title != null ? title : appName(controller.getPackageName()));
        mediaArtist.setText(artist != null ? artist : appName(controller.getPackageName()));
        Bitmap art = null;
        if (m != null) {
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        if (art != null) {
            mediaArt.setImageBitmap(art);
        } else {
            try { mediaArt.setImageDrawable(pm.getApplicationIcon(controller.getPackageName())); }
            catch (PackageManager.NameNotFoundException e) { mediaArt.setImageDrawable(null); }
        }
        PlaybackState s = controller.getPlaybackState();
        playButton.setText(s != null && s.getState() == PlaybackState.STATE_PLAYING ? "❚❚" : "▶");
        showProgress();
    }

    private int seekSeconds() { return prefs.getInt("seek_seconds", 15); }

    /** Where playback is now, extrapolated from the app's last position report. */
    private long currentPosition(PlaybackState s) {
        long position = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, position);
    }

    /** Jump forward or back; uses seekTo when the app supports it, otherwise its own fast-forward/rewind. */
    private void seekBy(long deltaMs) {
        if (controller == null) return;
        PlaybackState s = controller.getPlaybackState();
        MediaMetadata m = controller.getMetadata();
        if (s != null && (s.getActions() & PlaybackState.ACTION_SEEK_TO) != 0 && s.getPosition() >= 0) {
            long target = Math.max(0, currentPosition(s) + deltaMs);
            long duration = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (duration > 0) target = Math.min(duration, target);
            controller.getTransportControls().seekTo(target);
        } else if (deltaMs < 0) {
            controller.getTransportControls().rewind();
        } else {
            controller.getTransportControls().fastForward();
        }
    }

    /** Refresh the progress bar and time remaining; repeats every second while something plays. */
    private final Runnable progressTick = this::showProgress;

    private void showProgress() {
        main.removeCallbacks(progressTick);
        if (progressRow == null) return;
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        long duration = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration <= 0 || s == null || s.getPosition() < 0) {
            progressRow.setVisibility(View.GONE);
            return;
        }
        boolean playing = s.getState() == PlaybackState.STATE_PLAYING;
        long position = Math.min(duration, currentPosition(s));   // apps report position only occasionally
        progressRow.setVisibility(View.VISIBLE);
        mediaProgress.setProgress((int) (1000 * position / duration));
        mediaRemaining.setText("\u2212" + clockTime(duration - position));
        if (playing) main.postDelayed(progressTick, 1000);
    }

    private static String clockTime(long ms) {
        long sec = ms / 1000, h = sec / 3600, m = (sec / 60) % 60, s = sec % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    private void togglePlay() {
        if (controller == null) return;
        PlaybackState s = controller.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    private String appName(String pkg) {
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }

    // ---- small view helpers ----

    private View appTile(String pkg) {
        try {
            Drawable icon = pm.getApplicationIcon(pkg);
            String label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            return tile(icon, label, v -> open(pkg));
        } catch (PackageManager.NameNotFoundException e) {
            return new View(this);
        }
    }

    private View activityTile(ComponentName cn) {
        try {
            Drawable icon = pm.getActivityIcon(cn);
            String label = pm.getActivityInfo(cn, 0).loadLabel(pm).toString();
            return tile(icon, label, v -> launch(cn));
        } catch (PackageManager.NameNotFoundException | NullPointerException e) {
            return null;
        }
    }

    private View iconTile(String glyphText, String name, View.OnClickListener click) {
        TextView glyph = text(glyphText, 30, ACCENT);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(rounded(CARD, 12));
        LinearLayout t = column();
        t.addView(glyph, new LinearLayout.LayoutParams(dp(48), dp(48)));
        t.addView(label(name));
        t.setOnClickListener(click);
        return t;
    }

    /** Opens MiniCarHome's settings; sits beside All apps so nothing covers the shortcuts. */
    private View optionsTile() {
        ImageView gear = new ImageView(this);
        gear.setImageResource(getResources().getIdentifier("ic_settings", "drawable", getPackageName()));
        gear.setImageTintList(ColorStateList.valueOf(ACCENT));
        gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gear.setBackground(rounded(CARD, 12));
        gear.setPadding(dp(11), dp(11), dp(11), dp(11));
        LinearLayout t = column();
        t.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        t.addView(label("Options"));
        t.setContentDescription("MiniCarHome settings");
        t.setOnClickListener(v -> { settingsOpen = true; editing = false; rebuildPanel(); });
        return t;
    }

    private View tile(Drawable icon, String name, View.OnClickListener click) {
        LinearLayout t = column();
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        t.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));
        t.addView(label(name));
        t.setOnClickListener(click);
        return t;
    }

    private TextView sectionLabel(String s) {
        TextView t = text(s, 13, DIM);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(14), 0, dp(4));
        return t;
    }

    private LinearLayout column() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView label(String s) {
        TextView l = text(s, 12, TEXT);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(2), dp(3), dp(2), 0);
        return l;
    }

    /** Media transport button: bigger text and touch target than the settings controls. */
    private TextView mediaButton(String s, boolean wide, boolean five) {
        TextView b = text(s, five ? (wide ? 20 : 16) : (wide ? 24 : 22), TEXT);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinWidth(dp(five ? (wide ? 44 : 38) : (wide ? 56 : 50)));
        b.setMinHeight(dp(34));
        b.setPadding(0, dp(2), 0, dp(2));
        return b;
    }

    private TextView control(String s) {
        TextView b = text(s, 18, TEXT);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(52));
        b.setPadding(0, dp(6), 0, dp(2));
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        style(t, sp, color);
        return t;
    }

    private void style(TextView t, int sp, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * TEXT_SCALE);
        t.setTextColor(color);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams marginTop(int width, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    // The panel is laid out at a fixed scale (1.275 px per unit, 1.7x the box's original 120 dpi), so raising
    // Android's display size or font size (to enlarge other apps) leaves this layout unchanged.
    private static final float UI_SCALE = 1.275f;
    private static final float TEXT_SCALE = UI_SCALE * 1.3f;   // text was tuned with Android's font size at 1.3

    private int dp(int v) {
        return Math.round(v * UI_SCALE);
    }

    /** Real dp at the current display density, for things Android draws itself (the freeform caption bar). */
    private int sysDp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
