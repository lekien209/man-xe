package com.daica.carhud;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Cần thiết để đọc phiên phát nhạc (MediaSession) của app Bluetooth trên màn.
 * Người dùng bật một lần trong Cài đặt > Truy cập thông báo.
 */
public class MediaListenerService extends NotificationListenerService {
    public static volatile boolean connected = false;

    @Override
    public void onListenerConnected() {
        connected = true;
        MusicController.onListenerReady();
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        MusicController.onNotification(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
    }
}
