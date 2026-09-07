package app.tellev.core.ldream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 本地生图引擎的前台保活服务。核心是 app 进程 exec 出来的子进程，应用一退
 * 后台就会被系统连进程组一起回收、模型随之卸载——本服务让 app 进程保持前台
 * 重要性，模型得以常驻内存，直到用户划掉应用（[onTaskRemoved] 停引擎）或
 * 主动卸载。生命周期由 [LocalDreamKeepAlive] 按引擎状态驱动，这里只负责
 * 展示通知与兜底清理。
 */
class LocalDreamEngineService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "本地生图引擎",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "本地生图引擎常驻内存运行时的保活通知" },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val desc = intent?.getStringExtra(EXTRA_DESC) ?: "运行中"
        startForegroundCompat(buildNotification(desc))
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉应用 = 退出应用：停掉引擎子进程再自灭。
        LocalDreamCore.stop()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        // manifest 只声明 specialUse 一种类型：34+ 的双参重载即取声明值；
        // 更早版本不认识该位，走平台默认（无类型 FGS），两种都合法。
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(desc: String): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stopIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, LocalDreamEngineStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(app.tellev.R.drawable.ic_local_dream)
            .setContentTitle("本地生图引擎")
            .setContentText(desc)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(0, "卸载模型并停止", stopIntent)
            .build()
    }

    companion object {
        const val EXTRA_DESC = "desc"
        const val ACTION_STOP = "app.tellev.core.ldream.STOP_ENGINE"
        private const val CHANNEL_ID = "local_dream_engine"
        private const val NOTIFICATION_ID = 4101
    }
}

/** 通知上的「卸载模型并停止」按钮：广播落地（通知动作不适用后台 startService 限制）。 */
class LocalDreamEngineStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocalDreamEngineService.ACTION_STOP) return
        LocalDreamCore.stop()
        context.stopService(Intent(context, LocalDreamEngineService::class.java))
    }
}
