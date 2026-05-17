package com.leo.accelerator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.nio.ByteBuffer

class LeoVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "leo_vpn_channel"
        private const val NOTIFICATION_ID = 1
    }

    private fun showNotification(message: String) {
        try {
            val notification = createNotification().apply {
                this.flags = notification.flags or Notification.FLAG_AUTO_CANCEL
                android.content.BigTextStyle(android.app.Notification.Builder(this@LeoVpnService, CHANNEL_ID))
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        val host = intent.getStringExtra("host")
        val port = intent.getIntExtra("port", 0)
        val username = intent.getStringExtra("username") ?: ""
        val password = intent.getStringExtra("password") ?: ""
        val protocol = intent.getStringExtra("protocol") ?: "ss"

        if (host.isNullOrEmpty() || port == 0) {
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        startVpnConnection(host, port, username, password, protocol)

        return START_STICKY
    }

    private fun startVpnConnection(host: String, port: Int, username: String, password: String, protocol: String) {
        isRunning = true

        Thread {
            try {
                val builder = Builder()
                    .setSession("Leo游戏加速器")
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(1500)

                val vpn = builder.establish() ?: run {
                    android.util.Log.e("LeoVpn", "VPN建立失败")
                    handler.post { showNotification("VPN连接失败") }
                    return@Thread
                }

                vpnInterface = vpn

                val fd = vpn.fileDescriptor
                val inputStream = FileInputStream(fd)
                val outputStream = FileOutputStream(fd)

                    val buffer = ByteBuffer.allocate(32767)

                    while (isRunning) {
                        try {
                            val length = inputStream.read(buffer.array())
                            if (length > 0) {
                                buffer.limit(length)
                                processPacket(buffer, host, port, username, password, protocol)
                                buffer.clear()
                            }
                        } catch (e: Exception) {
                            break
                        }
                    }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopVpn()
            }
        }.start()
    }

    private fun processPacket(buffer: ByteBuffer, host: String, port: Int, username: String, password: String, protocol: String) {
        try {
            val socket = Socket(host, port)
            socket.getOutputStream().write(buffer.array(), 0, buffer.limit())
            val response = ByteArray(32767)
            val read = socket.getInputStream().read(response)
            if (read > 0) {
                vpnInterface?.fileDescriptor?.let { fd ->
                    val outputStream = FileOutputStream(fd)
                    outputStream.write(response, 0, read)
                    outputStream.flush()
                }
            }
            socket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Leo游戏加速器",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN正在运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Leo游戏加速器")
                .setContentText("VPN已连接")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Leo游戏加速器")
                .setContentText("VPN已连接")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .build()
        }
    }
}