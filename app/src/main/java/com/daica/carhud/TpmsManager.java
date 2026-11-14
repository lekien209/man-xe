package com.daica.carhud;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.LinkedList;
import java.util.List;

/**
 * Đầu thu áp suất lốp SY02 qua USB (19200 8N1).
 * Khung: 55 AA len ... xor (xor các byte 0..len-2).
 * len=8: [3] vị trí, [4]*3.44 = kPa, [5]-50 = °C, [6] cờ (0x08 xì hơi, 0x10 pin yếu, 0x20 mất sóng).
 * Đầu thu gửi 55 AA 06 00 88 xx -> phải trả lời 55 AA 06 00 00 F9.
 * Ghép: gửi 06 01 <vị trí> -> đầu thu trả 06 10 <vị trí>; ghép xong trả 06 18 <vị trí>.
 * Dừng ghép: 06 06 00 -> trả 06 16 00. Đổi vị trí: 07 03 a b -> trả 07 30 a b.
 */
public class TpmsManager {
    public interface Listener {
        void onTire(int idx, float bar, int temp, int flags);
        void onStatus(boolean connected);
        /** Ghép xong cảm biến cho vị trí idx (0 TT, 1 TP, 2 ST, 3 SP, 4 dự phòng). */
        void onPaired(int idx);
        /** Đầu thu xác nhận đã đổi vị trí 2 lốp. */
        void onSwapped();
        /** ID cảm biến đang ghép ở vị trí idx. */
        void onSensorId(int idx, String id);
    }

    /** Mã vị trí theo đầu thu: TT, TP, ST, SP, dự phòng. */
    public static final int[] POS_CODE = {0x00, 0x01, 0x10, 0x11, 0x05};

    private static int idxOf(int code) {
        for (int i = 0; i < POS_CODE.length; i++) if (POS_CODE[i] == code) return i;
        return -1;
    }

    private static byte[] make(int... body) {
        byte[] b = new byte[body.length + 4];
        b[0] = 0x55; b[1] = (byte) 0xAA; b[2] = (byte) b.length;
        for (int i = 0; i < body.length; i++) b[3 + i] = (byte) body[i];
        byte x = 0;
        for (int i = 0; i < b.length - 1; i++) x ^= b[i];
        b[b.length - 1] = x;
        return b;
    }

    /** Bắt đầu học cảm biến cho 1 vị trí. Sau đó vặn cảm biến của bánh đó ra rồi vặn lại. */
    public void startPair(int idx) {
        addLog("Bắt đầu ghép vị trí " + idx);
        write(make(0x01, POS_CODE[idx]));
    }

    public void stopPair() {
        write(make(0x06, 0x00));
    }

    /** Đổi vị trí 2 lốp (sau khi đảo lốp) mà không cần ghép lại. */
    public void swap(int a, int b) {
        int pa = POS_CODE[a], pb = POS_CODE[b];
        if (pa > pb) { int t = pa; pa = pb; pb = t; }
        addLog("Đổi vị trí " + a + " <-> " + b);
        write(make(0x03, pa, pb));
    }

    /** Xoá toàn bộ ghép nối trong đầu thu (lệnh reset của app hãng: 06 58 55). */
    public void resetDevice() {
        addLog("Xoá toàn bộ ghép nối (reset đầu thu)");
        write(make(0x58, 0x55));
    }

    public void queryIds() {
        write(QUERY_ID);
    }

    private static final String ACTION_PERM = "com.daica.carhud.USB_PERMISSION";
    private static final byte[] ACK = {0x55, (byte) 0xAA, 0x06, 0x00, 0x00, (byte) 0xF9};
    private static final byte[] HEARTBEAT = frame(0x19, 0x00);
    private static final byte[] QUERY_ID = frame(0x07, 0x00);

    private final Context ctx;
    private final Listener listener;
    private final UsbManager um;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile UsbSerialPort port;
    private volatile boolean stop = false;
    private Thread reader;
    public volatile boolean connected = false;
    private boolean asked = false;
    public final LinkedList<String> log = new LinkedList<>();

    private final BroadcastReceiver usbRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                close();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                asked = false;
                main.postDelayed(new Runnable() { public void run() { connect(); } }, 800);
            } else if (ACTION_PERM.equals(a)) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect();
            }
        }
    };

    public TpmsManager(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(ACTION_PERM);
        ctx.registerReceiver(usbRx, f);
    }

    private static byte[] frame(int cmd, int arg) {
        byte[] b = {0x55, (byte) 0xAA, 0x06, (byte) cmd, (byte) arg, 0};
        byte x = 0;
        for (int i = 0; i < 5; i++) x ^= b[i];
        b[5] = x;
        return b;
    }

    public synchronized void connect() {
        if (port != null || um == null) return;
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um);
        if (drivers.isEmpty()) { addLog("Không thấy đầu thu USB"); status(false); return; }
        UsbSerialDriver d = drivers.get(0);
        UsbDevice dev = d.getDevice();
        if (!um.hasPermission(dev)) {
            if (!asked) {
                asked = true;
                PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ACTION_PERM), 0);
                um.requestPermission(dev, pi);
            }
            return;
        }
        UsbDeviceConnection conn = um.openDevice(dev);
        if (conn == null) { addLog("Không mở được cổng USB (app khác đang giữ?)"); return; }
        UsbSerialPort p = d.getPorts().get(0);
        try {
            p.open(conn);
            p.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try { p.setDTR(true); p.setRTS(true); } catch (Exception ignored) {}
        } catch (Exception e) {
            addLog("Lỗi mở cổng: " + e.getMessage());
            try { p.close(); } catch (Exception ignored) {}
            return;
        }
        port = p;
        stop = false;
        addLog("Đã nối đầu thu " + String.format("%04X:%04X", dev.getVendorId(), dev.getProductId()));
        status(true);
        final UsbSerialPort mine = p;
        reader = new Thread(new Runnable() { public void run() { loop(mine); } }, "tpms");
        reader.start();
    }

    private void loop(UsbSerialPort mine) {
        byte[] buf = new byte[256];
        byte[] acc = new byte[512];
        int n = 0;
        long lastHb = 0;
        write(QUERY_ID);
        while (!stop && port == mine) {
            UsbSerialPort p = mine;
            long now = System.currentTimeMillis();
            if (now - lastHb > 10000) { write(HEARTBEAT); lastHb = now; }
            int r;
            try {
                r = p.read(buf, 300);
            } catch (Exception e) {
                addLog("Mất kết nối: " + e.getMessage());
                break;
            }
            if (r <= 0) continue;
            if (n + r > acc.length) n = 0;
            System.arraycopy(buf, 0, acc, n, r);
            n += r;
            // tách khung
            int i = 0;
            while (n - i >= 4) {
                if ((acc[i] & 0xFF) != 0x55 || (acc[i + 1] & 0xFF) != 0xAA) { i++; continue; }
                int len = acc[i + 2] & 0xFF;
                if (len < 5 || len > 32) { i++; continue; }
                if (n - i < len) break;
                byte x = 0;
                for (int k = 0; k < len - 1; k++) x ^= acc[i + k];
                if (x == acc[i + len - 1]) {
                    handle(acc, i, len);
                    i += len;
                } else i++;
            }
            if (i > 0) { System.arraycopy(acc, i, acc, 0, n - i); n -= i; }
        }
        final boolean wasStopped = stop || port != mine;
        synchronized (this) { if (port == mine) port = null; }
        try { mine.close(); } catch (Exception ignored) {}
        if (wasStopped) return;
        status(false);
        if (!wasStopped) main.postDelayed(new Runnable() { public void run() { connect(); } }, 3000);
    }

    private void handle(byte[] a, int o, int len) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < len; k++) sb.append(String.format("%02X ", a[o + k] & 0xFF));
        if (len == 6 && (a[o + 3] & 0xFF) == 0x00 && (a[o + 4] & 0xFF) == 0x88) {
            write(ACK);
            return;
        }
        addLog(sb.toString().trim());
        if (len == 6 && (a[o + 3] & 0xFF) == 0x18) {
            final int idx = idxOf(a[o + 4] & 0xFF);
            if (idx >= 0) main.post(new Runnable() { public void run() { if (listener != null) listener.onPaired(idx); } });
            return;
        }
        if (len == 7 && (a[o + 3] & 0xFF) == 0x30) {
            main.post(new Runnable() { public void run() { if (listener != null) listener.onSwapped(); } });
            return;
        }
        if (len == 9) {
            // gói ID đánh số vị trí 1..5 (1 TT, 2 TP, 3 ST, 4 SP, 5 dự phòng) - đã kiểm tra trên đầu thu thật
            int q = a[o + 3] & 0xFF;
            final int idx = (q >= 1 && q <= 5) ? q - 1 : -1;
            final String id = String.format("%02X%02X%02X%02X", a[o + 4] & 0xFF, a[o + 5] & 0xFF, a[o + 6] & 0xFF, a[o + 7] & 0xFF);
            if (idx >= 0) main.post(new Runnable() { public void run() { if (listener != null) listener.onSensorId(idx, id); } });
            return;
        }
        if (len == 8) {
            int pos = a[o + 3] & 0xFF;
            final int idx = pos == 0x00 ? 0 : pos == 0x01 ? 1 : pos == 0x10 ? 2 : pos == 0x11 ? 3 : -1;
            if (idx < 0) return;
            final float bar = (a[o + 4] & 0xFF) * 3.44f / 100f;
            final int temp = (a[o + 5] & 0xFF) - 50;
            final int flags = a[o + 6] & 0xFF;
            main.post(new Runnable() { public void run() { if (listener != null) listener.onTire(idx, bar, temp, flags); } });
        }
    }

    private void write(byte[] b) {
        UsbSerialPort p = port;
        if (p == null) return;
        try { p.write(b, 300); } catch (Exception ignored) {}
    }

    private void status(final boolean c) {
        connected = c;
        main.post(new Runnable() { public void run() { if (listener != null) listener.onStatus(c); } });
    }

    private synchronized void closePort() {
        UsbSerialPort p = port;
        port = null;
        if (p != null) try { p.close(); } catch (Exception ignored) {}
    }

    public void close() {
        stop = true;
        closePort();
        status(false);
    }

    public void release() {
        close();
        try { ctx.unregisterReceiver(usbRx); } catch (Exception ignored) {}
    }

    private void addLog(String s) {
        synchronized (log) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
            log.addFirst(f.format(new java.util.Date()) + "  " + s);
            while (log.size() > 40) log.removeLast();
        }
    }

    public String logText() {
        StringBuilder sb = new StringBuilder();
        synchronized (log) { for (String s : log) sb.append(s).append('\n'); }
        return sb.length() == 0 ? "(chưa có dữ liệu)" : sb.toString();
    }
}
