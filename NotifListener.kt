package com.gabs.dynamicisland

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        IslandBus.service?.startMedia()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        IslandBus.service?.onNotif(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        IslandBus.service?.onNotifRemoved(sbn)
    }
}
