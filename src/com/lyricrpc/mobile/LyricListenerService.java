package com.lyricrpc.mobile;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * The app needs notification access for exactly one thing: {@code MediaSessionManager.getActiveSessions}
 * only answers for a caller that holds it, which is how Android lets a lyric display see what the music
 * app is playing. This service reads no notification content and keeps none.
 */
public final class LyricListenerService extends NotificationListenerService {
    private static volatile LyricListenerService instance;

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        // The system re-binds on its own after a disconnect, but asking is what Android recommends.
        try {
            requestRebind(new android.content.ComponentName(this, LyricListenerService.class));
        } catch (Exception ignored) {
            // Nothing to do if the system refuses; access can be re-granted from the app.
        }
    }

    /** Null until the user has granted access and the system has bound us. */
    public static LyricListenerService current() {
        return instance;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        // Deliberately empty: the lyric comes from the media session, not from anyone's notifications.
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
    }
}
