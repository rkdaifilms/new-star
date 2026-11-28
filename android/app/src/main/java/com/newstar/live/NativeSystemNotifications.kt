package com.newstar.live

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

object NativeSystemNotifications {
    const val ACTION_OPEN_GAMING = "com.newstar.live.action.OPEN_GAMING"
    const val EXTRA_LIVE_INVITE_ID = "com.newstar.live.extra.LIVE_INVITE_ID"
    const val ACTION_OPEN_CREATOR = "com.newstar.live.action.OPEN_CREATOR"
    const val EXTRA_CREATOR_ID = "com.newstar.live.extra.CREATOR_ID"
    const val EXTRA_NOTIFICATION_ID = "com.newstar.live.extra.NOTIFICATION_ID"
    const val EXTRA_NOTIFICATION_TYPE = "com.newstar.live.extra.NOTIFICATION_TYPE"
    private const val CHANNEL_ID = "new_star_activity"
    private const val CHANNEL_NAME = "New Star activity"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "New followers and creator activity"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun showLiveInvite(context: Context, invite: JSONObject) {
        if (!hasPermission(context)) return
        createChannel(context)
        val inviteId = invite.optString("id")
        if (inviteId.isBlank()) return
        val inviterName = invite.optString("inviter_name").ifBlank { "New Star Creator" }
        val roomTitle = invite.optString("room_title").ifBlank { "Gaming LIVE" }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_GAMING
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_LIVE_INVITE_ID, inviteId)
        }
        val notificationId = ("live-invite:" + inviteId).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = "$inviterName ने आपको $roomTitle में Gaming LIVE के लिए invite किया है।"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Gaming LIVE invitation")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun show(context: Context, notification: JSONObject, actorName: String) {
        if (!hasPermission(context)) return
        if (!NativeNotificationPreferences.isEnabled(context, notification)) return
        createChannel(context)
        val creatorId = notification.optString("actor_id")
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CREATOR
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (creatorId.isNotBlank()) putExtra(EXTRA_CREATOR_ID, creatorId)
            val rowId = notification.optString("id")
            if (rowId.isNotBlank()) putExtra(EXTRA_NOTIFICATION_ID, rowId)
            val type = notification.optString("type")
            if (type.isNotBlank()) putExtra(EXTRA_NOTIFICATION_TYPE, type)
        }
        val notificationId = notification.optString("id").hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = actorName.ifBlank {
            notification.optString("type").ifBlank { "New Star" }
        }
        val message = notification.optString("message").ifBlank { "आपके अकाउंट पर नई activity हुई है।" }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }
}
