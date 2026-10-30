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
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
    private static final int PICK_NONE = 0, PICK_SHORTCUT = 1, PICK_NAV = 2;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int FREEFORM_CAPTION_DP = 24;   // measured: 18 px at this box's 0.75 density
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
    // Right/left panel: icons per row sets its width; 5 fills the original third of the screen.
    private static final int MIN_COLUMNS = 2, MAX_COLUMNS = 5, DEFAULT_COLUMNS = 3;
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
        panel = buildPanel();
        main.postDelayed(this::ensureMaps, 800);
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
        hideBrowser();
        hidePanel();
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsListener);
        setController(null);
    }

    @Override
    public void onBackPressed() {
        hideBrowser();
    }

    // ---- Maps window ----

    private Rect mapsBounds() {
        Rect screen = wm.getMaximumWindowMetrics().getBounds();
        int mapsWidth = screen.width() - panelWidth();
        int left = panelLeft ? screen.width() - mapsWidth : 0;
        // The freeform caption bar sits above the app and pushes it down by its height, so leave room for it
        // or the bottom of Maps (e.g. the Start button) runs off the screen.
        return new Rect(left, 0, left + mapsWidth, screen.height() - dp(FREEFORM_CAPTION_DP));
    }

    /**
     * After a width or side change, move Maps to its new place. Android won't resize an open window for
     * another app, so Box Tools' gesture service taps the old window's close button, then Maps reopens
     * with the new bounds.
     */
    private void resizeMaps(Rect old) { resizeMaps(old, navApp()); }

    private void resizeMaps(Rect old, String oldApp) {
        if (old == null) { ensureMaps(); return; }
        // Take the panel down first: in its new place it can sit over the old window's close button.
        hidePanel();
        main.postDelayed(() -> sendBroadcast(new Intent("local.boxtools.action.CLOSE_WINDOW")
                .setPackage("local.boxtools").putExtra("package", oldApp)), 150);   // Box Tools checks our CONTROL permission
        main.postDelayed(() -> {
            showPanel();
            ensureMaps();
        }, 1100);
    }

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

    private void launchMaps() {
        Intent i = pm.getLaunchIntentForPackage(navApp());
        if (i == null) {
            Log.i(TAG, "Maps not available yet");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i, paneOptions());
        } catch (RuntimeException e) {
            Log.w(TAG, "Maps launch failed", e);
        }
    }

    /** Launch options that put an app in a freeform window over the main pane. */
    private Bundle paneOptions() {
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchBounds(mapsBounds());
        try {
            Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            m.invoke(o, WINDOWING_MODE_FREEFORM);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "freeform launch unavailable", e);
        }
        return o.toBundle();
    }

    /**
     * With "Open apps in main window" on, apps open in the main pane over the main display app, which keeps
     * running underneath (Home brings it back). Opening another app closes the previous one's window.
     * Box Tools' own icons (Stock Home, Wireless Debug) still open normally.
     */
    private boolean opensInPane(ComponentName cn) {
        return prefs.getBoolean("apps_in_pane", true) && !"local.boxtools".equals(cn.getPackageName());
    }

    private void launchInPane(Intent i, String pkg) {
        if (paneApp != null && !paneApp.equals(pkg) && !paneApp.equals(navApp())) {
            sendBroadcast(new Intent("local.boxtools.action.CLOSE_WINDOW")
                    .setPackage("local.boxtools").putExtra("package", paneApp));   // Box Tools checks our CONTROL permission
        }
        paneApp = pkg;
        main.postDelayed(() -> {
            try {
                startActivity(i, paneOptions());
            } catch (RuntimeException e) {
                Toast.makeText(this, "Can't open: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, 400);
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

    /** Panel width for the chosen icons per row; 5 icons fill the original right third. */
    private int panelWidth() {
        int max = wm.getMaximumWindowMetrics().getBounds().width() / 3;
        int pad = dp(PAD_EDGE) + dp(PAD_INNER);
        return pad + columns * ((max - pad) / MAX_COLUMNS);
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

    private void setPref(String key, boolean on) {
        prefs.edit().putBoolean(key, on).apply();
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

    private void openBrowser(int pick) {
        if (browserShown || !Settings.canDrawOverlays(this)) return;
        browser = new BrowserView(this, pick);
        wm.addView(browser, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START, true));
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
        p.setBackgroundColor(BG);
        // Extra padding on the screen-edge side keeps buttons clear of the edge Back-gesture strip.
        p.setPadding(dp(panelLeft ? PAD_EDGE : PAD_INNER), dp(8), dp(panelLeft ? PAD_INNER : PAD_EDGE), dp(8));

        // Clock and date shrink and shorten as the panel narrows; weather sits beside them when there's room.
        boolean showClock = prefs.getBoolean("show_clock", true);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        style(clock, n >= 3 ? 44 : n == 2 ? 32 : 22, TEXT);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setSingleLine(true);
        TextClock date = new TextClock(this);
        String dateFormat = n >= 3 ? "EEEE, MMMM d" : n == 2 ? "EEE, MMM d" : "EEE d";
        date.setFormat12Hour(dateFormat);
        date.setFormat24Hour(dateFormat);
        style(date, n >= 3 ? 15 : 13, DIM);
        date.setSingleLine(true);
        clockBox.addView(clock);
        clockBox.addView(date);
        if (!showClock) clockBox.setVisibility(View.GONE);
        header.addView(clockBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        weatherTemp = null;
        weatherRange = null;
        if (n >= 3) {   // weather needs at least three icons' width beside the clock
            LinearLayout wx = new LinearLayout(this);
            wx.setOrientation(LinearLayout.VERTICAL);
            wx.setGravity(Gravity.END);
            wx.setPadding(dp(6), dp(4), dp(4), 0);
            weatherTemp = text("", n >= 4 ? 26 : 22, TEXT);
            weatherTemp.setSingleLine(true);
            wx.addView(weatherTemp);
            if (n >= 4) {
                weatherRange = text("", 13, DIM);
                weatherRange.setSingleLine(true);
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
            p.addView(buildMediaCard(n), marginTop(LinearLayout.LayoutParams.MATCH_PARENT, 8));
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

        p.addView(buildBottomBar(n), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return p;
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
        s.addView(text(n >= 2 ? "Icons per row" : "Icons", 14, TEXT));
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
        s.addView(text(n >= 3 ? "Main display app" : "Main app", 14, TEXT), marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.addView(choice(appName(navApp()), true, v -> showNavPicker()));
        nav.addView(choice("Change", false, v -> showNavPicker()));
        s.addView(nav, marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        s.addView(toggleRow(n >= 3 ? "Open apps in main window" : "Apps in main", "apps_in_pane"),
                marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        s.addView(toggleRow("Clock & date", "show_clock"), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        s.addView(toggleRow("Media", "show_media"), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 6));
        s.addView(toggleRow("Assistant", "show_mic"), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 6));

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
    private View toggleRow(String label, String key) {
        boolean on = prefs.getBoolean(key, true);
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
        List<String> list = shortcuts();
        for (int i = 0; i < list.size(); i++) {
            String entry = list.get(i);
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
     * Bottom bar: settings gear on the left; the assistant button and (when placed at the bottom) the
     * Wi-Fi and cell indicators on the right. One row when wide (4-5 icons), stacked when narrow.
     * Kept clear of the bottom-edge Home gesture strip.
     */
    private View buildBottomBar(int n) {
        boolean mic = prefs.getBoolean("show_mic", true);
        boolean signals = "bottom".equals(signalPlace());
        ImageView gear = new ImageView(this);
        gear.setImageResource(getResources().getIdentifier("ic_settings", "drawable", getPackageName()));
        gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gear.setPadding(dp(6), dp(6), dp(6), dp(6));
        gear.setContentDescription("MiniCarHome settings");
        gear.setOnClickListener(v -> { settingsOpen = !settingsOpen; editing = false; rebuildPanel(); });

        LinearLayout box = new LinearLayout(this);
        box.setPadding(0, dp(6), dp(4), dp(14));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        buttons.addView(gear, new LinearLayout.LayoutParams(dp(40), dp(40)));
        buttons.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (mic) {
            LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(40), dp(40));
            if (signals && n >= 4) micLp.rightMargin = dp(16);
            buttons.addView(assistantButton(), micLp);
        }
        // 4-5 icons: one row. 3: Wi-Fi and cell side by side under the buttons. 2: stacked.
        if (signals && n >= 4) {
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

    private String signalPlace() { return prefs.getString("signals", "bottom"); }

    private View assistantButton() {
        ImageView assistant = new ImageView(this);
        // No generated R class in this Gradle-free build, so look the icon up by name.
        assistant.setImageResource(getResources().getIdentifier("ic_mic", "drawable", getPackageName()));
        assistant.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable dot = rounded(ACCENT, 0);
        dot.setShape(GradientDrawable.OVAL);
        assistant.setBackground(dot);
        assistant.setContentDescription("Assistant");
        assistant.setOnClickListener(v -> startAssistant());
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
        String[] actions = {"android.intent.action.VOICE_ASSIST", Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST};
        for (String a : actions) {
            Intent i = new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) == null) continue;
            try {
                startActivity(i);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "assistant " + a + " failed", e);
            }
        }
        Toast.makeText(this, "No assistant available", Toast.LENGTH_SHORT).show();
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
            if (columns <= 2) cellLabel.setText(type.isEmpty() ? "Cell" : type);   // narrow panel: just "LTE"
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
                grid.addView(row, marginTop(LinearLayout.LayoutParams.MATCH_PARENT, 6));
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
        card.setBackground(rounded(CARD, 16));
        card.setPadding(dp(10), dp(10), dp(10), dp(wide ? 10 : 6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        mediaArt = new ImageView(this);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArt.setBackground(rounded(0xFF26323A, 10));
        mediaArt.setClipToOutline(true);
        int art = n >= 4 ? 64 : n == 3 ? 56 : 44;
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

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView prev = mediaButton("◀◀", wide);
        playButton = mediaButton("▶", wide);
        TextView next = mediaButton("▶▶", wide);
        prev.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().skipToPrevious(); });
        next.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().skipToNext(); });
        playButton.setOnClickListener(v -> togglePlay());
        controls.addView(prev);
        controls.addView(playButton);
        controls.addView(next);

        if (wide) info.addView(controls);
        top.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(top);
        if (!wide) card.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Progress bar with time remaining, shown when the app reports a track length.
        progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(0, dp(6), 0, 0);
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
                    : pick == PICK_NAV ? "Main display app: search apps" : "Search apps");
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
        long position = s.getPosition();
        if (playing) {   // the app reports position only occasionally; extrapolate from its last report
            position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        position = Math.max(0, Math.min(duration, position));
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
    private TextView mediaButton(String s, boolean wide) {
        TextView b = text(s, wide ? 24 : 22, TEXT);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(wide ? 64 : 54));
        b.setMinHeight(dp(44));
        b.setPadding(0, dp(4), 0, dp(4));
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
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
