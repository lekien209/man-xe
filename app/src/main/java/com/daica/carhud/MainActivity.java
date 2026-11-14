package com.daica.carhud;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.TilesOverlay;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements LocationListener, TpmsManager.Listener, MusicController.Listener {

    private SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private DashLayout root;
    private TopBarView top;
    private MapView map;
    private NavOverlayView nav;
    private CarSceneView scene;
    private GaugeView gauge;
    private WeatherView weather;
    private FlipClockView clock;
    private MusicView music;

    private TpmsManager tpms;
    private MusicController mc;
    private LocationManager lm;

    private double lat = Double.NaN, lon = Double.NaN;
    private float bearing = 0, speed = 0;
    private long lastFix = 0, lastSpeedT = 0, brakeUntil = 0;
    private boolean following = true;
    private long followResumeAt = 0;
    private final GeoPoint here = new GeoPoint(21.0285, 105.8542);

    private NavHelper.Route route;
    private Polyline routeLine;
    private boolean routing = false;
    private JSONObject pendingDest; // tuyến đang đi dở lúc tắt máy -> tìm lại khi có GPS

    private double wLat = Double.NaN, wLon = Double.NaN;
    private long wTime = 0;
    private boolean lastDay = false;
    private int themeMode = 0; // 0 tự động, 1 ngày, 2 đêm
    private int mapStyle = 0;  // 0 OSM chuẩn, 1 OSM HOT
    private boolean osmBlocked = false;
    private ToneGenerator tone;
    private boolean isResumed = false;
    private boolean btSourceDone = false; // đã chuyển nguồn nhạc Bluetooth trong chuyến này
    private boolean switching = false;
    private final boolean[] wasLow = new boolean[4];

    // ======================================================================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("carhud", 0);
        Net.installTrust(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

        Theme.setCar(prefs.getInt("car", 0));
        themeMode = prefs.getInt("theme", 0);
        mapStyle = prefs.getInt("mapStyle", 0);
        lat = Double.longBitsToDouble(prefs.getLong("lat", Double.doubleToLongBits(Double.NaN)));
        lon = Double.longBitsToDouble(prefs.getLong("lon", Double.doubleToLongBits(Double.NaN)));
        if (!Double.isNaN(lat)) here.setCoords(lat, lon);
        Theme.day = computeDay();
        lastDay = Theme.day;

        // osmdroid: lưu ô bản đồ trong bộ nhớ app, không cần quyền thẻ nhớ
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.load(this, prefs);
        cfg.setUserAgentValue(Net.UA);
        File base = new File(getFilesDir(), "osmdroid");
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(new File(base, "tiles"));
        cfg.setTileFileSystemCacheMaxBytes(400L * 1024 * 1024);
        cfg.setTileFileSystemCacheTrimBytes(300L * 1024 * 1024);
        cfg.setTileDownloadThreads((short) 2);

        buildUi();
        setContentView(root);

        tone = makeTone();
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        tpms = new TpmsManager(this, this);
        mc = new MusicController(this, this);
        music.mc = mc;
        loadTires();
        if (prefs.getBoolean("autoBt", true)) h.postDelayed(new Runnable() {
            public void run() { if (!btSourceDone && isResumed) switchBtSource(false); } }, 15000);
        try {
            String d = prefs.getString("dest", null);
            if (d != null && d.length() > 2) pendingDest = new JSONObject(d);
        } catch (Exception ignored) {}
    }

    private ToneGenerator makeTone() {
        try { return new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90); } catch (Exception e) { return null; }
    }

    private void buildUi() {
        root = new DashLayout(this, new float[]{342, 44, 682, 588});

        top = new TopBarView(this, null);
        root.add(top, 0, 0, 1024, 40, 0);

        FrameLayout mapBox = new FrameLayout(this);
        map = new MapView(this);
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setTilesScaledToDpi(false);
        map.setMinZoomLevel(4.0);
        map.setMaxZoomLevel(19.0);
        map.getController().setZoom(16.0);
        map.getController().setCenter(here);
        map.getOverlays().add(new PosOverlay());
        map.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    following = false;
                    followResumeAt = SystemClock.elapsedRealtime() + 15000;
                    nav.following = false;
                    nav.invalidate();
                }
                return false;
            }
        });
        applyTiles();
        mapBox.addView(map, new FrameLayout.LayoutParams(-1, -1));
        nav = new NavOverlayView(this, null);
        mapBox.addView(nav, new FrameLayout.LayoutParams(-1, -1));
        root.add(mapBox, 12, 50, 330, 588, 16);

        scene = new CarSceneView(this, null);
        root.add(scene, 342, 50, 682, 476, 16);
        gauge = new GaugeView(this, null);
        root.add(gauge, 342, 478, 682, 588, 0);

        weather = new WeatherView(this, null);
        root.add(weather, 694, 50, 1012, 176, 16);
        clock = new FlipClockView(this, null);
        root.add(clock, 694, 186, 1012, 312, 16);
        music = new MusicView(this, null);
        root.add(music, 694, 322, 1012, 588, 16);

        scene.threshold = prefs.getFloat("threshold", 2.0f);

        top.actions = new TopBarView.Actions() {
            public void onSettings() { showSettings(); }
            public void onApps() { showApps(); }
        };
        nav.actions = new NavOverlayView.Actions() {
            public void onSearch() { showSearch(); }
            public void onCancelRoute() { confirmCancelRoute(); }
            public void onRecenter() { following = true; nav.following = true; nav.invalidate(); centerMap(true); }
            public void onZoom(boolean in) { if (in) map.getController().zoomIn(); else map.getController().zoomOut(); }
        };
        music.actions = new MusicView.Actions() {
            public void onPrev() { mc.prev(); }
            public void onPlayPause() {
                // lần đầu bấm phát trong chuyến: chuyển màn sang nguồn nhạc Bluetooth trước
                if (!btSourceDone && !mc.playing && prefs.getBoolean("autoBt", true)) switchBtSource(true);
                else mc.playPause();
            }
            public void onNext() { mc.next(); }
            public void onNeedPermission() { openNotifAccess(); }
        };
    }

    private void applyTiles() {
        ITileSource src;
        if (mapStyle == 1 || osmBlocked) {
            src = new XYTileSource("OSMHot", 1, 19, 256, ".png", new String[]{
                    "https://a.tile.openstreetmap.fr/hot/",
                    "https://b.tile.openstreetmap.fr/hot/"}, "© OpenStreetMap, HOT");
        } else {
            src = TileSourceFactory.MAPNIK;
        }
        if (nav != null) nav.attrib = "© OpenStreetMap";
        if (map.getTileProvider().getTileSource() != src) map.setTileSource(src);
        // ban đêm: đảo màu bản đồ cho tối, đỡ chói
        map.getOverlayManager().getTilesOverlay().setColorFilter(Theme.day ? null : TilesOverlay.INVERT_COLORS);
        map.getOverlayManager().getTilesOverlay().setLoadingBackgroundColor(Theme.bg());
        map.getOverlayManager().getTilesOverlay().setLoadingLineColor(Theme.panel2());
        map.setBackgroundColor(Theme.bg());
        map.invalidate();
    }

    // ======================================================================
    @Override
    protected void onResume() {
        super.onResume();
        isResumed = true;
        AnimView.resumeAll();
        map.onResume();
        startLocation();
        music.perm = MusicController.hasPermission(this);
        mc.start();
        tpms.connect();
        refreshAll();
        checkOsmDns();
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    @Override
    protected void onPause() {
        isResumed = false;
        AnimView.running = false;
        map.onPause();
        h.removeCallbacks(ticker);
        try { lm.removeUpdates(this); } catch (Exception ignored) {}
        saveState();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        tpms.release();
        mc.release();
        bg.shutdownNow();
        if (tone != null) tone.release();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && "android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(i.getAction())) tpms.connect();
    }

    @Override
    public void onBackPressed() {
        // là màn hình chính: nút Back không thoát app
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Một số mạng chặn openstreetmap.org (trỏ về 127.0.0.1) -> dùng máy chủ bản đồ dự phòng. */
    private void checkOsmDns() {
        bg.execute(new Runnable() {
            public void run() {
                boolean blocked;
                try {
                    java.net.InetAddress ad = java.net.InetAddress.getByName("tile.openstreetmap.org");
                    blocked = ad.isLoopbackAddress() || ad.isAnyLocalAddress();
                } catch (Exception e) {
                    return; // chưa có mạng: giữ nguyên
                }
                final boolean b = blocked;
                h.post(new Runnable() { public void run() {
                    if (b != osmBlocked) { osmBlocked = b; applyTiles(); }
                } });
            }
        });
    }

    private void refreshAll() {
        root.invalidate();
        top.invalidate(); scene.invalidate(); gauge.invalidate();
        weather.invalidate(); clock.invalidate(); music.invalidate(); nav.invalidate();
    }

    /** Nhịp 5 giây: ngày/đêm, mạng, Bluetooth, GPS, thời tiết, quay lại bám vị trí. */
    private final Runnable ticker = new Runnable() {
        int n = 0;
        public void run() {
            boolean d = computeDay();
            if (d != lastDay) {
                lastDay = d;
                Theme.day = d;
                applyTiles();
                refreshAll();
            }
            updateStatus();
            if (!following && SystemClock.elapsedRealtime() > followResumeAt) {
                following = true;
                nav.following = true;
                nav.invalidate();
                centerMap(true);
            }
            if (SystemClock.elapsedRealtime() - lastFix > 4000 && speed > 0) {
                speed = 0;
                scene.setSpeed(0, true);
                gauge.setSpeed(0);
            }
            maybeWeather();
            if (++n % 12 == 0) saveState();
            h.postDelayed(this, 5000);
        }
    };

    private boolean computeDay() {
        if (themeMode == 1) return true;
        if (themeMode == 2) return false;
        return Theme.isDayNow(lat, lon);
    }

    private void updateStatus() {
        boolean gps = SystemClock.elapsedRealtime() - lastFix < 5000;
        boolean net = false;
        String nn = "4G";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) {
                net = true;
                if (ni.getType() == ConnectivityManager.TYPE_WIFI) nn = "Wi-Fi";
                else {
                    int st = ni.getSubtype();
                    nn = st == TelephonyManager.NETWORK_TYPE_LTE ? "4G"
                            : (st == TelephonyManager.NETWORK_TYPE_HSPAP || st == TelephonyManager.NETWORK_TYPE_HSPA
                            || st == TelephonyManager.NETWORK_TYPE_UMTS || st == TelephonyManager.NETWORK_TYPE_HSDPA) ? "3G" : "Mạng";
                }
            }
        } catch (Exception ignored) {}
        boolean bt = btConnected() || (mc != null && (mc.hasSession || mc.title.length() > 0));
        boolean ch = gps != top.gps || net != top.net || bt != top.bt || !nn.equals(top.netName) || tpms.connected != top.tpms;
        top.gps = gps; top.net = net; top.netName = nn; top.bt = bt; top.tpms = tpms.connected;
        if (ch) top.invalidate();
        if (bt != music.btConnected) {
            // iPhone vừa nối -> tự chuyển nguồn sang nhạc Bluetooth (mỗi chuyến 1 lần)
            if (bt && !btSourceDone && isResumed && prefs.getBoolean("autoBt", true)) h.postDelayed(new Runnable() {
                public void run() { if (!btSourceDone && isResumed) switchBtSource(false); } }, 3000);
            music.btConnected = bt; music.invalidate();
        }
    }

    private boolean btConnected() {
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba == null || !ba.isEnabled()) return false;
            int[] profiles = {2, 1, 11, 16}; // A2DP, HEADSET, A2DP_SINK, HEADSET_CLIENT
            for (int pr : profiles) {
                try { if (ba.getProfileConnectionState(pr) == BluetoothAdapter.STATE_CONNECTED) return true; } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ======================================================================
    // GPS
    private void startLocation() {
        try {
            lm.removeUpdates(this);
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000, 50, this);
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null && Double.isNaN(lat)) { lat = l.getLatitude(); lon = l.getLongitude(); here.setCoords(lat, lon); centerMap(false); }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {}
    }

    @Override
    public void onLocationChanged(Location l) {
        boolean isGps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        if (!isGps && SystemClock.elapsedRealtime() - lastFix < 10000) return; // đã có GPS thì bỏ mạng
        double pLat = lat, pLon = lon;
        lat = l.getLatitude();
        lon = l.getLongitude();
        here.setCoords(lat, lon);
        if (isGps) {
            long now = SystemClock.elapsedRealtime();
            float ns;
            if (l.hasSpeed() && l.getSpeed() > 0) ns = l.getSpeed() * 3.6f;
            else if (!Double.isNaN(pLat) && lastSpeedT > 0 && now - lastSpeedT < 5000) {
                ns = (float) (NavHelper.dist(pLat, pLon, lat, lon) / Math.max(0.5, (now - lastSpeedT) / 1000.0) * 3.6);
                if (!Double.isNaN(pLat) && NavHelper.dist(pLat, pLon, lat, lon) > 3)
                    bearing = (float) Math.toDegrees(Math.atan2((lon - pLon) * Math.cos(Math.toRadians(lat)), lat - pLat));
            } else ns = 0;
            if (ns < 2) ns = 0; // lọc nhiễu khi đứng yên
            float dt = lastSpeedT == 0 ? 1 : Math.max(0.5f, (now - lastSpeedT) / 1000f);
            float decel = (speed - ns) / dt; // km/h mỗi giây
            if (decel > 4.5f) brakeUntil = now + 1500;
            speed = ns;
            lastSpeedT = now;
            lastFix = now;
            if (l.hasBearing() && speed > 4) bearing = l.getBearing();
            boolean braking = speed < 3 || now < brakeUntil;
            scene.setSpeed(speed, braking);
            gauge.setSpeed(speed);
        }
        if (following) centerMap(false); // nhảy theo bước 1 giây, không trượt liên tục
        map.invalidate();
        if (route != null) updateRoute();
        else if (pendingDest != null && isGps && !routing) {
            JSONObject d = pendingDest;
            pendingDest = null;
            try { startRoute(d.getDouble("la"), d.getDouble("lo"), d.optString("n", "")); } catch (Exception ignored) {}
        }
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) { startLocation(); }
    @Override public void onProviderDisabled(String p) {}

    private void centerMap(boolean animate) {
        if (animate) map.getController().animateTo(here);
        else map.getController().setCenter(here);
    }

    /** Vẽ vị trí xe: chấm xanh có mũi tên hướng đi. */
    private class PosOverlay extends Overlay {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final Point pt = new Point();

        @Override
        public void draw(Canvas c, MapView mv, boolean shadow) {
            if (shadow || Double.isNaN(lat)) return;
            mv.getProjection().toPixels(here, pt);
            float s = getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x332BB3A2);
            c.drawCircle(pt.x, pt.y, 22 * s, p);
            c.save();
            c.rotate(bearing, pt.x, pt.y);
            arrow.reset();
            arrow.moveTo(pt.x, pt.y - 14 * s);
            arrow.lineTo(pt.x + 10 * s, pt.y + 10 * s);
            arrow.lineTo(pt.x, pt.y + 5 * s);
            arrow.lineTo(pt.x - 10 * s, pt.y + 10 * s);
            arrow.close();
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4 * s);
            p.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(arrow, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.ACCENT);
            c.drawPath(arrow, p);
            c.restore();
        }
    }

    // ======================================================================
    // Thời tiết (Open-Meteo, miễn phí, không cần khoá)
    private void maybeWeather() {
        if (Double.isNaN(lat)) return;
        boolean moved = Double.isNaN(wLat) || NavHelper.dist(lat, lon, wLat, wLon) > 8000;
        if (!moved && System.currentTimeMillis() - wTime < 15 * 60000) return;
        wLat = lat; wLon = lon; wTime = System.currentTimeMillis();
        final double la = lat, lo = lon;
        bg.execute(new Runnable() {
            public void run() {
                String q = String.format(Locale.US, "://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code&timezone=auto", la, lo);
                try {
                    String body;
                    try { body = Net.get("https" + q); } catch (Exception e) { body = Net.get("http" + q); }
                    JSONObject cur = new JSONObject(body).getJSONObject("current");
                    final double t = cur.getDouble("temperature_2m");
                    final int code = cur.getInt("weather_code");
                    h.post(new Runnable() { public void run() { weather.set(t, code); } });
                } catch (final Exception e) {
                    wTime = System.currentTimeMillis() - 13 * 60000; // thử lại sau ~2 phút
                    h.post(new Runnable() { public void run() { weather.setError("Chưa lấy được thời tiết"); } });
                }
            }
        });
    }

    // ======================================================================
    // Áp suất lốp
    @Override
    public void onTire(int idx, float bar, int temp, int flags) {
        CarSceneView.Tire t = scene.tires[idx];
        t.noSignal = (flags & 0x20) != 0;
        t.leak = (flags & 0x08) != 0;
        t.lowBatt = (flags & 0x10) != 0;
        if (!t.noSignal) { t.bar = bar; t.temp = temp; }
        t.lastUpdate = System.currentTimeMillis();
        boolean low = (!Float.isNaN(t.bar) && t.bar < scene.threshold) || t.leak;
        if (low && !wasLow[idx] && tone != null) {
            try { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600); } catch (Exception ignored) {}
        }
        wasLow[idx] = low;
        prefs.edit().putFloat("tb" + idx, t.bar).putInt("tt" + idx, t.temp).putBoolean("tl" + idx, t.lowBatt).apply();
        scene.invalidate();
    }

    @Override
    public void onStatus(boolean connected) {
        top.tpms = connected;
        top.invalidate();
    }

    // ---------- ghép cảm biến ----------
    private static final String[] POS_NAMES = {"Trước trái", "Trước phải", "Sau trái", "Sau phải", "Lốp dự phòng"};
    private final String[] sensorIds = new String[5];
    private AlertDialog pairDlg, waitDlg;
    private int pairingIdx = -1;
    private boolean wizard = false; // đang ghép lần lượt cả 4 bánh
    private int wizardOk = 0;
    private final Runnable pairTimeout = new Runnable() {
        public void run() {
            if (pairingIdx < 0) return;
            tpms.stopPair();
            int was = pairingIdx;
            pairingIdx = -1;
            if (waitDlg != null) waitDlg.dismiss();
            if (wizard) { toast("Bánh " + POS_NAMES[was] + " chưa ghép được, chuyển bánh tiếp"); wizardNext(was); }
            else toast("Hết thời gian chờ, chưa ghép được. Thử lại nhé.");
        }
    };

    @Override
    public void onPaired(int idx) {
        h.removeCallbacks(pairTimeout);
        pairingIdx = -1;
        if (waitDlg != null) waitDlg.dismiss();
        if (tone != null) try { tone.startTone(ToneGenerator.TONE_PROP_ACK, 300); } catch (Exception ignored) {}
        toast("Đã ghép xong cảm biến " + POS_NAMES[idx]);
        tpms.queryIds();
        if (idx < 4) { scene.tires[idx].bar = Float.NaN; scene.tires[idx].temp = Integer.MIN_VALUE; scene.invalidate(); }
        if (wizard) { wizardOk++; final int i = idx; h.postDelayed(new Runnable() { public void run() { wizardNext(i); } }, 1500); return; }
        h.postDelayed(new Runnable() { public void run() { showPairing(); } }, 800);
    }

    @Override
    public void onSwapped() {
        toast("Đã đổi vị trí lốp");
        for (int i = 0; i < 4; i++) { scene.tires[i].bar = Float.NaN; scene.tires[i].temp = Integer.MIN_VALUE; }
        scene.invalidate();
        tpms.queryIds();
    }

    @Override
    public void onSensorId(int idx, String id) {
        sensorIds[idx] = id;
        prefs.edit().putString("sid" + idx, id).apply();
    }

    private void showPairing() {
        if (!tpms.connected) {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setMessage("Chưa nối được đầu thu áp suất lốp. Kiểm tra dây USB và tắt app áp suất lốp của hãng, rồi thử lại.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        tpms.queryIds();
        String[] items = new String[8];
        for (int i = 0; i < 5; i++) {
            String id = sensorIds[i] != null ? sensorIds[i] : prefs.getString("sid" + i, null);
            boolean empty = id == null || id.equals("00000000") || id.equals("FFFFFFFF");
            items[i] = "Ghép " + POS_NAMES[i] + (empty ? "   (chưa có)" : "   ID " + id);
        }
        items[5] = "⟳ Xoá hết & ghép lại cả 4 bánh từ đầu";
        items[6] = "Đổi vị trí 2 lốp (sau khi đảo lốp)";
        items[7] = "Xem nhật ký đầu thu";
        if (pairDlg != null) pairDlg.dismiss();
        pairDlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Ghép cảm biến lốp")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w < 5) startPairing(w);
                        else if (w == 5) confirmResetAll();
                        else if (w == 6) pickSwap();
                        else showText("Nhật ký cảm biến", tpms.logText());
                    }
                })
                .setNegativeButton("Đóng", null).show();
    }

    private void confirmResetAll() {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Ghép lại từ đầu?")
                .setMessage("Đầu thu sẽ quên cả 4 cảm biến, sau đó app dẫn đại ca ghép lần lượt:\n"
                        + "Trước trái → Trước phải → Sau trái → Sau phải.\n\n"
                        + "Chuẩn bị sẵn để vặn ra/vặn vào từng cảm biến theo hướng dẫn.")
                .setPositiveButton("Bắt đầu", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        tpms.resetDevice();
                        wizard = true;
                        wizardOk = 0;
                        for (int i = 0; i < 5; i++) { sensorIds[i] = null; prefs.edit().remove("sid" + i).apply(); }
                        for (int i = 0; i < 4; i++) { scene.tires[i].bar = Float.NaN; scene.tires[i].temp = Integer.MIN_VALUE; wasLow[i] = false; }
                        scene.invalidate();
                        toast("Đã xoá ghép nối cũ");
                        h.postDelayed(new Runnable() { public void run() { startPairing(0); } }, 2000);
                    }
                })
                .setNegativeButton("Thôi", null).show();
    }

    /** Sang bánh tiếp theo trong trình ghép lần lượt, hết 4 bánh thì báo kết quả. */
    private void wizardNext(int done) {
        if (!wizard) return;
        int nx = done + 1;
        if (nx < 4) { startPairing(nx); return; }
        wizard = false;
        tpms.queryIds();
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Xong")
                .setMessage("Đã ghép được " + wizardOk + "/4 bánh."
                        + (wizardOk < 4 ? "\nBánh nào chưa được thì vào lại mục Ghép cảm biến để ghép riêng bánh đó." : ""))
                .setPositiveButton("OK", null).show();
    }

    private void startPairing(final int idx) {
        pairingIdx = idx;
        tpms.startPair(idx);
        h.removeCallbacks(pairTimeout);
        h.postDelayed(pairTimeout, 120000);
        waitDlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle((wizard ? "Bước " + (idx + 1) + "/4 · " : "") + "Đang chờ cảm biến " + POS_NAMES[idx])
                .setMessage("1. Vặn cảm biến của bánh " + POS_NAMES[idx].toLowerCase(Locale.ROOT) + " ra khỏi van.\n"
                        + "2. Đợi 5 giây rồi vặn lại vào van (nghe xì hơi nhẹ là được).\n"
                        + "3. Đầu thu nhận được sẽ kêu bíp và tự đóng bảng này.\n\n"
                        + "Chỉ làm với ĐÚNG bánh này, các bánh khác để yên. Chờ tối đa 2 phút.")
                .setCancelable(false)
                .setNegativeButton(wizard ? "Dừng ghép" : "Huỷ", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        h.removeCallbacks(pairTimeout);
                        pairingIdx = -1;
                        wizard = false;
                        tpms.stopPair();
                    }
                })
                .setPositiveButton(wizard ? "Bỏ qua bánh này" : null, wizard ? new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        h.removeCallbacks(pairTimeout);
                        pairingIdx = -1;
                        tpms.stopPair();
                        final int i = idx;
                        h.postDelayed(new Runnable() { public void run() { wizardNext(i); } }, 600);
                    }
                } : null).show();
    }

    private void pickSwap() {
        final String[] opts = {
                "Trước trái ⇄ Trước phải", "Sau trái ⇄ Sau phải",
                "Trước trái ⇄ Sau trái", "Trước phải ⇄ Sau phải",
                "Trước trái ⇄ Sau phải", "Trước phải ⇄ Sau trái"};
        final int[][] pairs = {{0, 1}, {2, 3}, {0, 2}, {1, 3}, {0, 3}, {1, 2}};
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Đổi vị trí lốp")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { tpms.swap(pairs[w][0], pairs[w][1]); toast("Đang đổi vị trí…"); }
                }).setNegativeButton("Đóng", null).show();
    }

    private void loadTires() {
        for (int i = 0; i < 4; i++) {
            CarSceneView.Tire t = scene.tires[i];
            t.bar = prefs.getFloat("tb" + i, Float.NaN);
            t.temp = prefs.getInt("tt" + i, Integer.MIN_VALUE);
            t.lowBatt = prefs.getBoolean("tl" + i, false);
            wasLow[i] = !Float.isNaN(t.bar) && t.bar < scene.threshold;
        }
    }

    // ======================================================================
    // Nhạc
    @Override
    public void onMusicChanged() {
        music.invalidate();
        updateStatus();
    }

    /** Mở nhanh màn hình nhạc của app Bluetooth gốc để màn chuyển sang nguồn nhạc Bluetooth, rồi quay về. */
    private void switchBtSource(final boolean thenPlay) {
        if (switching) return;
        Intent t = BtAudio.targetIntent(this, prefs);
        if (t == null) {
            toast("Không tìm thấy app Bluetooth. Vào Cài đặt > Nhạc Bluetooth để chọn.");
            if (thenPlay) mc.playPause();
            return;
        }
        switching = true;
        btSourceDone = true;
        try {
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(t);
        } catch (Exception e) {
            switching = false;
            prefs.edit().remove("btPkg").remove("btCls").apply();
            toast("Không mở được màn hình nhạc Bluetooth");
            if (thenPlay) mc.playPause();
            return;
        }
        h.postDelayed(new Runnable() {
            public void run() {
                // nếu app Bluetooth chưa tự phát thì gửi lệnh phát
                if (thenPlay && !mc.playing) mc.playPause();
                Intent back = new Intent(MainActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(back);
                switching = false;
            }
        }, prefs.getInt("btDelay", 1800));
    }

    private void showMusicSettings() {
        boolean auto = prefs.getBoolean("autoBt", true);
        String[] items = {
                "Thử chuyển sang nhạc Bluetooth ngay",
                "Tự chuyển nguồn khi nổ máy / khi bấm phát: " + (auto ? "Bật" : "Tắt"),
                "Chọn màn hình nhạc của app Bluetooth (đang dùng: " + BtAudio.describe(this, prefs) + ")",
                "Thời gian chờ chuyển nguồn: " + String.format(Locale.US, "%.1f", prefs.getInt("btDelay", 1800) / 1000f).replace('.', ',') + " giây",
                "Cho phép đọc tên bài hát" + (MusicController.hasPermission(this) ? " (đã bật)" : " (chưa bật)")};
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Nhạc Bluetooth")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        switch (w) {
                            case 0: btSourceDone = false; switchBtSource(true); break;
                            case 1: prefs.edit().putBoolean("autoBt", !prefs.getBoolean("autoBt", true)).apply();
                                toast(prefs.getBoolean("autoBt", true) ? "Đã bật tự chuyển nguồn nhạc" : "Đã tắt tự chuyển nguồn nhạc"); break;
                            case 2: pickBtScreen(); break;
                            case 3: {
                                final String[] o = {"1 giây", "1,8 giây (mặc định)", "3 giây", "5 giây"};
                                final int[] v = {1000, 1800, 3000, 5000};
                                pick("Thời gian chờ", o, 1, new Pick() { public void on(int i) { prefs.edit().putInt("btDelay", v[i]).apply(); } });
                                break;
                            }
                            case 4: openNotifAccess(); break;
                        }
                    }
                }).setNegativeButton("Đóng", null).show();
    }

    /** Cho đại ca chọn đúng màn hình nhạc nếu app tự tìm sai. Liệt kê mọi màn hình mở được của app Bluetooth. */
    private void pickBtScreen() {
        final List<BtAudio.Target> all = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (ResolveInfo r : BtAudio.findBtApps(this)) {
            String app = String.valueOf(r.loadLabel(pm));
            for (BtAudio.Target t : BtAudio.listActivities(this, r.activityInfo.packageName)) {
                t.label = app + " › " + t.label;
                all.add(t);
            }
        }
        if (all.isEmpty()) {
            showText("Dò app Bluetooth", "Không thấy app nào tên có chữ Bluetooth.\nChụp màn hình mục Ứng dụng (9 chấm) gửi em để em thêm vào.");
            return;
        }
        String[] labels = new String[all.size() + 1];
        labels[0] = "Tự tìm (mặc định)";
        for (int i = 0; i < all.size(); i++) labels[i + 1] = all.get(i).label;
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Chọn màn hình có nút phát nhạc (♪ là gợi ý)")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) prefs.edit().remove("btPkg").remove("btCls").apply();
                        else prefs.edit().putString("btPkg", all.get(w - 1).pkg).putString("btCls", all.get(w - 1).cls).apply();
                        btSourceDone = false;
                        switchBtSource(true);
                    }
                }).setNegativeButton("Đóng", null).show();
    }

    private void openNotifAccess() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            toast("Bật \"Màn xe\" trong danh sách rồi quay lại");
        } catch (Exception e) {
            toast("Máy không mở được mục Truy cập thông báo");
        }
    }

    // ======================================================================
    // Dẫn đường
    private void showSearch() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        box.setPadding(pad, pad, pad, 0);
        final EditText ed = new EditText(this);
        ed.setHint("Nhập địa chỉ, tên địa điểm…");
        ed.setSingleLine(true);
        ed.setInputType(InputType.TYPE_CLASS_TEXT);
        ed.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        ed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        box.addView(ed, new LinearLayout.LayoutParams(-1, -2));
        final TextView info = new TextView(this);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setPadding(0, dp(6), 0, dp(6));
        box.addView(info);
        final ListView lv = new ListView(this);
        box.addView(lv, new LinearLayout.LayoutParams(-1, dp(300)));

        final List<NavHelper.Place> items = new ArrayList<>(recentPlaces());
        final PlaceAdapter ad = new PlaceAdapter(items);
        lv.setAdapter(ad);
        info.setText(items.isEmpty() ? "" : "Điểm đến gần đây");

        final AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Tìm điểm đến").setView(box).setNegativeButton("Đóng", null).create();
        final Runnable doSearch = new Runnable() {
            public void run() {
                final String q = ed.getText().toString().trim();
                if (q.length() < 2) return;
                info.setText("Đang tìm…");
                final double la = lat, lo = lon;
                bg.execute(new Runnable() {
                    public void run() {
                        try {
                            final List<NavHelper.Place> res = NavHelper.search(q, la, lo);
                            h.post(new Runnable() {
                                public void run() {
                                    items.clear(); items.addAll(res); ad.notifyDataSetChanged();
                                    info.setText(res.isEmpty() ? "Không thấy kết quả, thử ghi rõ hơn (thêm quận, tỉnh)" : "Chọn một kết quả:");
                                }
                            });
                        } catch (final Exception e) {
                            h.post(new Runnable() { public void run() { info.setText("Lỗi mạng: " + e.getMessage()); } });
                        }
                    }
                });
            }
        };
        ed.setOnEditorActionListener(new OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) { doSearch.run(); return true; }
        });
        dlg.setButton(DialogInterface.BUTTON_POSITIVE, "Tìm", (DialogInterface.OnClickListener) null);
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { doSearch.run(); }
                });
            }
        });
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                NavHelper.Place p = items.get(pos);
                dlg.dismiss();
                saveRecent(p);
                startRoute(p.lat, p.lon, p.name);
            }
        });
        dlg.show();
    }

    private class PlaceAdapter extends BaseAdapter {
        final List<NavHelper.Place> l;
        PlaceAdapter(List<NavHelper.Place> l) { this.l = l; }
        public int getCount() { return l.size(); }
        public Object getItem(int i) { return l.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View v, ViewGroup parent) {
            LinearLayout row;
            if (v instanceof LinearLayout) row = (LinearLayout) v;
            else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(4), dp(10), dp(4), dp(10));
                TextView a = new TextView(MainActivity.this); a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18); a.setTextColor(0xFFFFFFFF);
                TextView b = new TextView(MainActivity.this); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); b.setTextColor(0xFF9AA4B2);
                b.setMaxLines(2);
                row.addView(a); row.addView(b);
            }
            NavHelper.Place p = l.get(i);
            ((TextView) row.getChildAt(0)).setText(p.name);
            ((TextView) row.getChildAt(1)).setText(p.detail);
            return row;
        }
    }

    private List<NavHelper.Place> recentPlaces() {
        List<NavHelper.Place> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                NavHelper.Place p = new NavHelper.Place();
                p.name = o.getString("n"); p.detail = o.optString("d", ""); p.lat = o.getDouble("la"); p.lon = o.getDouble("lo");
                out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void saveRecent(NavHelper.Place p) {
        try {
            List<NavHelper.Place> l = recentPlaces();
            JSONArray a = new JSONArray();
            a.put(new JSONObject().put("n", p.name).put("d", p.detail).put("la", p.lat).put("lo", p.lon));
            for (NavHelper.Place q : l) {
                if (a.length() >= 8) break;
                if (Math.abs(q.lat - p.lat) < 1e-5 && Math.abs(q.lon - p.lon) < 1e-5) continue;
                a.put(new JSONObject().put("n", q.name).put("d", q.detail).put("la", q.lat).put("lo", q.lon));
            }
            prefs.edit().putString("recent", a.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void startRoute(final double toLat, final double toLon, final String name) {
        if (Double.isNaN(lat)) { toast("Chưa có vị trí GPS, đợi chút rồi thử lại"); return; }
        if (routing) return;
        routing = true;
        toast("Đang tìm đường…");
        final double la = lat, lo = lon;
        bg.execute(new Runnable() {
            public void run() {
                try {
                    final NavHelper.Route r = NavHelper.route(la, lo, toLat, toLon);
                    r.destName = name;
                    h.post(new Runnable() { public void run() { routing = false; setRoute(r); } });
                } catch (final Exception e) {
                    h.post(new Runnable() { public void run() { routing = false; toast("Không tìm được đường: " + e.getMessage()); } });
                }
            }
        });
    }

    private void setRoute(NavHelper.Route r) {
        route = r;
        prefs.edit().putString("dest", name(r)).apply();
        if (routeLine != null) map.getOverlays().remove(routeLine);
        routeLine = new Polyline(map);
        List<GeoPoint> pts = new ArrayList<>(r.lat.length);
        for (int i = 0; i < r.lat.length; i++) pts.add(new GeoPoint(r.lat[i], r.lon[i]));
        routeLine.setPoints(pts);
        routeLine.getOutlinePaint().setColor(Theme.ACCENT);
        routeLine.getOutlinePaint().setStrokeWidth(dp(6));
        routeLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        routeLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        routeLine.getOutlinePaint().setAntiAlias(true);
        map.getOverlays().add(0, routeLine);
        nav.hasRoute = true;
        following = true;
        nav.following = true;
        centerMap(true);
        updateRoute();
        map.invalidate();
    }

    private String name(NavHelper.Route r) {
        try {
            return new JSONObject().put("n", r.destName).put("la", r.destLat).put("lo", r.destLon).toString();
        } catch (Exception e) { return ""; }
    }

    private void updateRoute() {
        NavHelper.Route r = route;
        if (r == null) return;
        double off = NavHelper.update(r, lat, lon);
        double rem = NavHelper.remaining(r);
        if (rem < 30 || NavHelper.dist(lat, lon, r.destLat, r.destLon) < 30) {
            toast("Đã đến " + r.destName);
            clearRoute();
            return;
        }
        if (off > 70) {
            if (++r.offCount >= 4 && !routing) {
                r.offCount = 0;
                toast("Đi chệch tuyến, đang tìm đường mới…");
                final String nm = r.destName;
                startRoute(r.destLat, r.destLon, nm);
            }
        } else r.offCount = 0;

        NavHelper.Step st = NavHelper.nextStep(r);
        if (st != null) {
            double d = NavHelper.dist(lat, lon, st.lat, st.lon);
            nav.step = st;
            nav.instr = NavHelper.text(st) + " sau " + NavHelper.fmtDist(d);
            nav.road = st.name.length() > 0 ? st.name : r.destName;
        }
        double secs = r.distance > 0 ? r.duration * rem / r.distance : 0;
        int mins = (int) Math.round(secs / 60);
        nav.etaMain = mins >= 60 ? (mins / 60) + " giờ " + (mins % 60) + " phút" : Math.max(1, mins) + " phút";
        Calendar c = Calendar.getInstance();
        c.add(Calendar.SECOND, (int) secs);
        String eta = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        String km = String.format(Locale.US, "%.1f", rem / 1000).replace('.', ',');
        nav.etaSub = km + " km · Đến " + eta;
        nav.invalidate();
        gauge.setDest(true, km, "Đến " + eta, (float) (1 - rem / Math.max(1, r.distance)));
    }

    private void confirmCancelRoute() {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setMessage("Huỷ dẫn đường?")
                .setPositiveButton("Huỷ đường", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { clearRoute(); }
                })
                .setNegativeButton("Tiếp tục đi", null).show();
    }

    private void clearRoute() {
        route = null;
        pendingDest = null;
        prefs.edit().remove("dest").apply();
        if (routeLine != null) map.getOverlays().remove(routeLine);
        routeLine = null;
        nav.hasRoute = false;
        nav.step = null;
        nav.invalidate();
        gauge.setDest(false, "", "", 0);
        map.invalidate();
    }

    // ======================================================================
    // Cài đặt
    // ======================================================================
    // Bảng cài đặt: 4 nhóm thẻ chia 2 cột, nút to dễ bấm khi ngồi trong xe
    private android.app.Dialog settingsDlg;
    private LinearLayout settingsBody;

    private void showSettings() {
        if (settingsDlg == null) {
            settingsDlg = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
            FrameLayout dim = new FrameLayout(this);
            dim.setBackgroundColor(0xB3000000);
            dim.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { settingsDlg.dismiss(); } });
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setClickable(true);
            panel.setPadding(dp(22), dp(16), dp(22), dp(16));
            settingsBody = panel;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(dm.widthPixels * 0.88f), Math.round(dm.heightPixels * 0.9f), Gravity.CENTER);
            dim.addView(panel, lp);
            settingsDlg.setContentView(dim);
        }
        buildSettings();
        settingsDlg.show();
        if (settingsDlg.getWindow() != null) settingsDlg.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void buildSettings() {
        LinearLayout p = settingsBody;
        p.removeAllViews();
        p.setBackground(round(Theme.panel(), 18, 0));

        // tiêu đề
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = txt("Cài đặt", 24, Theme.text(), true);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = txt("✕", 22, Theme.sub(), false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(Theme.panel2(), 20, 0));
        close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { settingsDlg.dismiss(); } });
        head.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        p.addView(head);
        space(p, 12);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout cols = new LinearLayout(this);
        LinearLayout left = new LinearLayout(this), right = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
        cols.addView(left, cl);
        View gap = new View(this);
        cols.addView(gap, new LinearLayout.LayoutParams(dp(14), 1));
        cols.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        sv.addView(cols);
        p.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- Giao diện ----
        LinearLayout c1 = card(left, "🎨", "Giao diện");
        label(c1, "Màu xe · " + Theme.CAR_NAMES[prefs.getInt("car", 0)]);
        LinearLayout dots = new LinearLayout(this);
        for (int i = 0; i < Theme.CAR_COLORS.length; i++) {
            final int idx = i;
            View d = new View(this);
            boolean sel = prefs.getInt("car", 0) == i;
            d.setBackground(oval(Theme.CAR_COLORS[i][0], sel ? Theme.ACCENT : 0x33FFFFFF, sel ? dp(3) : dp(1)));
            d.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                prefs.edit().putInt("car", idx).apply(); Theme.setCar(idx); scene.invalidate(); buildSettings(); } });
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(30), dp(30));
            dl.rightMargin = dp(7);
            dots.addView(d, dl);
        }
        c1.addView(dots);
        space(c1, 12);
        label(c1, "Ngày / đêm");
        c1.addView(seg(new String[]{"Tự động", "Ngày", "Đêm"}, themeMode, new Pick() { public void on(int i) {
            themeMode = i; prefs.edit().putInt("theme", i).apply(); lastDay = !computeDay(); h.removeCallbacks(ticker); h.post(ticker);
            h.postDelayed(new Runnable() { public void run() { buildSettings(); } }, 150); } }));
        space(c1, 12);
        label(c1, "Bản đồ");
        c1.addView(seg(new String[]{"OSM chuẩn", "OSM màu nhẹ"}, mapStyle, new Pick() { public void on(int i) {
            mapStyle = i; prefs.edit().putInt("mapStyle", i).apply(); applyTiles(); nav.invalidate(); buildSettings(); } }));

        // ---- Áp suất lốp ----
        LinearLayout c2 = card(left, "◉", "Áp suất lốp");
        LinearLayout th = new LinearLayout(this);
        th.setGravity(Gravity.CENTER_VERTICAL);
        th.addView(txt("Cảnh báo khi dưới", 16, Theme.text(), false), new LinearLayout.LayoutParams(0, -2, 1));
        th.addView(pill("−", false, new View.OnClickListener() { public void onClick(View v) { setThreshold(scene.threshold - 0.1f); } }), new LinearLayout.LayoutParams(dp(44), dp(40)));
        TextView tv = txt(String.format(Locale.US, "%.1f bar", scene.threshold).replace('.', ','), 18, Theme.ACCENT, true);
        tv.setGravity(Gravity.CENTER);
        th.addView(tv, new LinearLayout.LayoutParams(dp(86), -2));
        th.addView(pill("+", false, new View.OnClickListener() { public void onClick(View v) { setThreshold(scene.threshold + 0.1f); } }), new LinearLayout.LayoutParams(dp(44), dp(40)));
        c2.addView(th);
        space(c2, 10);
        TextView st = txt(tpms.connected ? "● Đầu thu đã kết nối" : "● Chưa thấy đầu thu", 14, tpms.connected ? Theme.ACCENT : Theme.RED, false);
        c2.addView(st);
        space(c2, 10);
        c2.addView(wide("Ghép cảm biến / đổi vị trí lốp", true, new View.OnClickListener() { public void onClick(View v) { settingsDlg.dismiss(); showPairing(); } }));
        space(c2, 8);
        c2.addView(wide("Kết nối lại đầu thu", false, new View.OnClickListener() { public void onClick(View v) {
            tpms.close(); tpms.connect(); toast("Đang kết nối lại đầu thu…"); h.postDelayed(new Runnable() { public void run() { if (settingsDlg.isShowing()) buildSettings(); } }, 1500); } }));

        // ---- Nhạc Bluetooth ----
        LinearLayout c3 = card(right, "🎵", "Nhạc Bluetooth");
        toggleRow(c3, "Tự chuyển nguồn nhạc khi nổ máy", prefs.getBoolean("autoBt", true), new Pick() { public void on(int i) {
            prefs.edit().putBoolean("autoBt", i == 1).apply(); buildSettings(); } });
        space(c3, 6);
        c3.addView(txt("App nhạc: " + BtAudio.describe(this, prefs), 13, Theme.sub(), false));
        space(c3, 10);
        c3.addView(wide("▶  Chuyển sang nhạc Bluetooth ngay", true, new View.OnClickListener() { public void onClick(View v) {
            settingsDlg.dismiss(); btSourceDone = false; switchBtSource(true); } }));
        space(c3, 8);
        c3.addView(wide("Chọn màn hình nhạc khác", false, new View.OnClickListener() { public void onClick(View v) { settingsDlg.dismiss(); pickBtScreen(); } }));
        space(c3, 8);
        boolean perm = MusicController.hasPermission(this);
        c3.addView(wide(perm ? "✓ Đã cho phép đọc tên bài" : "⚠ Cho phép đọc tên bài hát", false, new View.OnClickListener() { public void onClick(View v) {
            settingsDlg.dismiss(); openNotifAccess(); } }));

        // ---- Hệ thống ----
        LinearLayout c4 = card(right, "⚙", "Hệ thống");
        toggleRow(c4, "Tự mở app khi khởi động xe", prefs.getBoolean("autostart", true), new Pick() { public void on(int i) {
            prefs.edit().putBoolean("autostart", i == 1).apply(); buildSettings(); } });
        space(c4, 10);
        LinearLayout r1 = new LinearLayout(this);
        r1.addView(wide("Đặt làm màn hình chính", false, new View.OnClickListener() { public void onClick(View v) { settingsDlg.dismiss(); setAsHome(); } }), new LinearLayout.LayoutParams(0, -2, 1));
        space(r1, 8);
        r1.addView(wide("Cài đặt máy", false, new View.OnClickListener() { public void onClick(View v) {
            settingsDlg.dismiss(); try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} } }), new LinearLayout.LayoutParams(0, -2, 1));
        c4.addView(r1);
        space(c4, 8);
        c4.addView(wide("Thoát app", false, new View.OnClickListener() { public void onClick(View v) { settingsDlg.dismiss(); confirmExit(); } }));
        space(c4, 10);
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        c4.addView(txt("Màn xe " + ver, 12, Theme.sub(), false));
    }

    private void setThreshold(float v) {
        v = Math.round(Math.max(1.4f, Math.min(3.0f, v)) * 10) / 10f;
        scene.threshold = v;
        prefs.edit().putFloat("threshold", v).apply();
        scene.invalidate();
        buildSettings();
    }

    // ---- các khối giao diện dùng chung ----
    private android.graphics.drawable.GradientDrawable round(int fill, float r, int stroke) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }

    private android.graphics.drawable.GradientDrawable oval(int fill, int stroke, int sw) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(sw, stroke);
        return g;
    }

    private TextView txt(String s, float sp, int col, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(col);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private void space(LinearLayout l, int d) {
        View v = new View(this);
        l.addView(v, l.getOrientation() == LinearLayout.VERTICAL ? new LinearLayout.LayoutParams(1, dp(d)) : new LinearLayout.LayoutParams(dp(d), 1));
    }

    private void label(LinearLayout l, String s) {
        TextView t = txt(s, 14, Theme.sub(), false);
        t.setPadding(0, 0, 0, dp(6));
        l.addView(t);
    }

    private LinearLayout card(LinearLayout col, String icon, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(Theme.panel2(), 14, 0));
        c.setPadding(dp(16), dp(12), dp(16), dp(14));
        TextView h = txt(icon + "  " + title, 17, Theme.text(), true);
        h.setPadding(0, 0, 0, dp(10));
        c.addView(h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        col.addView(c, lp);
        return c;
    }

    private TextView pill(String s, boolean on, View.OnClickListener cl) {
        TextView t = txt(s, 16, on ? 0xFF0E1116 : Theme.text(), on);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(on ? Theme.ACCENT : Theme.panel(), 10, on ? 0 : Theme.alpha(Theme.sub(), 0.35f)));
        t.setOnClickListener(cl);
        return t;
    }

    private TextView wide(String s, boolean primary, View.OnClickListener cl) {
        TextView t = txt(s, 15, primary ? 0xFF0E1116 : Theme.text(), primary);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(11), dp(10), dp(11));
        t.setBackground(round(primary ? Theme.ACCENT : Theme.panel(), 10, primary ? 0 : Theme.alpha(Theme.sub(), 0.35f)));
        t.setOnClickListener(cl);
        return t;
    }

    private LinearLayout seg(String[] opts, int sel, final Pick cb) {
        LinearLayout l = new LinearLayout(this);
        l.setBackground(round(Theme.panel(), 10, 0));
        l.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            TextView t = txt(opts[i], 15, i == sel ? 0xFF0E1116 : Theme.text(), i == sel);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            if (i == sel) t.setBackground(round(Theme.ACCENT, 8, 0));
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { cb.on(idx); } });
            l.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return l;
    }

    private void toggleRow(LinearLayout c, String s, boolean on, final Pick cb) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(txt(s, 16, Theme.text(), false), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(seg(new String[]{"Tắt", "Bật"}, on ? 1 : 0, cb), new LinearLayout.LayoutParams(dp(120), -2));
        c.addView(r);
    }

    private interface Pick { void on(int i); }

    private void pick(String title, String[] opts, int cur, final Pick cb) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(title)
                .setSingleChoiceItems(opts, cur, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { d.dismiss(); cb.on(w); }
                }).show();
    }

    private void pickCarColor() {
        pick("Màu xe", Theme.CAR_NAMES, prefs.getInt("car", 0), new Pick() {
            public void on(int i) { prefs.edit().putInt("car", i).apply(); Theme.setCar(i); scene.invalidate(); }
        });
    }

    private void pickThreshold() {
        final String[] opts = new String[11];
        int cur = 0;
        for (int i = 0; i < opts.length; i++) {
            float v = 1.6f + i * 0.1f;
            opts[i] = String.format(Locale.US, "%.1f bar", v).replace('.', ',');
            if (Math.abs(v - scene.threshold) < 0.01f) cur = i;
        }
        pick("Cảnh báo khi áp suất dưới", opts, cur, new Pick() {
            public void on(int i) {
                scene.threshold = 1.6f + i * 0.1f;
                prefs.edit().putFloat("threshold", scene.threshold).apply();
                scene.invalidate();
            }
        });
    }

    private void showText(String title, String txt) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        tv.setTextIsSelectable(true);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(title).setView(sv)
                .setPositiveButton("Đóng", null).show();
    }

    private void setAsHome() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            toast("Chọn \"Màn xe\" làm màn hình chính");
        } catch (Exception e) {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setMessage("Bấm nút Home (hình ngôi nhà) trên màn, chọn \"Màn xe\" rồi chọn \"Luôn luôn\".")
                    .setPositiveButton("OK", null).show();
        }
    }

    private void confirmExit() {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setMessage("Thoát app Màn xe?")
                .setPositiveButton("Thoát", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { saveState(); finishAndRemoveTask(); }
                })
                .setNegativeButton("Không", null).show();
    }

    // ======================================================================
    // Danh sách ứng dụng (vì app là màn hình chính)
    private void showApps() {
        final PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0))
            if (!r.activityInfo.packageName.equals(getPackageName())) apps.add(r);
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        GridView g = new GridView(this);
        g.setNumColumns(6);
        g.setVerticalSpacing(dp(12));
        g.setPadding(dp(10), dp(14), dp(10), dp(14));
        g.setAdapter(new BaseAdapter() {
            public int getCount() { return apps.size(); }
            public Object getItem(int p) { return apps.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View v, ViewGroup parent) {
                LinearLayout cell;
                if (v instanceof LinearLayout) cell = (LinearLayout) v;
                else {
                    cell = new LinearLayout(MainActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    ImageView iv = new ImageView(MainActivity.this);
                    cell.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));
                    TextView tv = new TextView(MainActivity.this);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    tv.setTextColor(0xFFEAEEF3);
                    tv.setGravity(Gravity.CENTER);
                    tv.setMaxLines(2);
                    cell.addView(tv);
                }
                ResolveInfo r = apps.get(p);
                Drawable icon = r.loadIcon(pm);
                ((ImageView) cell.getChildAt(0)).setImageDrawable(icon);
                ((TextView) cell.getChildAt(1)).setText(r.loadLabel(pm));
                return cell;
            }
        });
        final AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Ứng dụng").setView(g).setNegativeButton("Đóng", null).create();
        g.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int p, long id) {
                ResolveInfo r = apps.get(p);
                Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(r.activityInfo.packageName, r.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                dlg.dismiss();
                try { startActivity(li); } catch (Exception e) { toast("Không mở được ứng dụng"); }
            }
        });
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setLayout(dp(860), dp(520));
    }

    // ======================================================================
    private void saveState() {
        SharedPreferences.Editor e = prefs.edit();
        if (!Double.isNaN(lat)) {
            e.putLong("lat", Double.doubleToLongBits(lat));
            e.putLong("lon", Double.doubleToLongBits(lon));
        }
        e.apply();
    }

    private final Runnable hideMsg = new Runnable() { public void run() { nav.msg = null; nav.invalidate(); } };

    private void toast(String s) {
        nav.msg = s;
        nav.invalidate();
        h.removeCallbacks(hideMsg);
        h.postDelayed(hideMsg, 4000);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
