package com.kynrek.minicarhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Current conditions from Open-Meteo (free, no account or key). Uses the box's last known location,
 * rounded to two decimals (~1 km); with no location fix, no weather is fetched. Results are cached in
 * prefs so the panel shows the last reading straight away after a reboot.
 */
final class Weather {
    final int temp, high, low, code;
    final long fetchedAt;

    private Weather(int temp, int high, int low, int code, long fetchedAt) {
        this.temp = temp; this.high = high; this.low = low; this.code = code; this.fetchedAt = fetchedAt;
    }

    static Weather cached(SharedPreferences p) {
        if (!p.contains("wx_temp")) return null;
        return new Weather(p.getInt("wx_temp", 0), p.getInt("wx_high", 0), p.getInt("wx_low", 0),
                p.getInt("wx_code", 0), p.getLong("wx_time", 0));
    }

    void save(SharedPreferences p) {
        p.edit().putInt("wx_temp", temp).putInt("wx_high", high).putInt("wx_low", low)
                .putInt("wx_code", code).putLong("wx_time", fetchedAt).apply();
    }

    /** Blocking network call; run off the main thread. Returns null on any failure. */
    static Weather fetch(Context c) {
        double[] ll = location(c);
        if (ll == null) return null;
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                        + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                        + "&temperature_unit=fahrenheit&timezone=auto&forecast_days=1", ll[0], ll[1]);
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(10000);
            http.setReadTimeout(10000);
            if (http.getResponseCode() != 200) return null;
            JSONObject json = new JSONObject(read(http.getInputStream()));
            JSONObject cur = json.getJSONObject("current");
            JSONObject day = json.getJSONObject("daily");
            return new Weather((int) Math.round(cur.getDouble("temperature_2m")),
                    (int) Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)),
                    (int) Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)),
                    cur.getInt("weather_code"), System.currentTimeMillis());
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    @SuppressLint("MissingPermission")   // coarse location is granted over ADB
    private static double[] location(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (SecurityException | NullPointerException ignored) { }
        return null;
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    /** WMO weather code to a symbol that the system font can draw. */
    String symbol() {
        if (code == 0) return "☀";                       // clear
        if (code <= 2) return "⛅";                       // partly cloudy
        if (code == 3 || code == 45 || code == 48) return "☁";   // overcast / fog
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄";   // snow
        if (code >= 95) return "⚡";                      // thunderstorm
        return "☔";                                      // drizzle / rain / showers
    }
}
