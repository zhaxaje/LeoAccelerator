package com.leo.accelerator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class LeoVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var v2rayProcess: Process? = null
    private val gson = Gson()

    companion object {
        private const val CHANNEL_ID = "leo_vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "LeoVpn"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        val nodeJson = intent.getStringExtra("node")
        if (nodeJson.isNullOrEmpty()) {
            return START_NOT_STICKY
        }

        val node = gson.fromJson(nodeJson, ProxyNode::class.java)

        try {
            startForeground(NOTIFICATION_ID, createNotification("正在启动..."))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                startV2Ray(node)
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                e.printStackTrace()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private suspend fun startV2Ray(node: ProxyNode) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting V2Ray connection to ${node.server}:${node.port}")

            // Create config file
            val config = createV2RayConfig(node)
            val configFile = File(filesDir, "v2ray_config.json")
            configFile.writeText(config)
            Log.d(TAG, "Config written to ${configFile.absolutePath}")

            // Try to start V2Ray core from assets or download
            val v2rayFile = File(filesDir, "v2ray")
            
            if (!v2rayFile.exists()) {
                // Copy from assets if available, otherwise download
                copyV2RayFromAssets(v2rayFile)
            }

            if (v2rayFile.exists() && v2rayFile.canExecute()) {
                Log.d(TAG, "Starting V2Ray core...")
                val processBuilder = ProcessBuilder(
                    v2rayFile.absolutePath,
                    "-config", configFile.absolutePath
                )
                v2rayProcess = processBuilder.redirectErrorStream(true).start()
                
                // Wait for V2Ray to start
                delay(2000)
                
                if (v2rayProcess?.isAlive == true) {
                    Log.d(TAG, "V2Ray started successfully")
                }
            } else {
                Log.w(TAG, "V2Ray binary not available, using proxy only mode")
            }

            // Create VPN interface
            createVpnInterface()
            
            // Update notification
            updateNotification("VPN已连接")
            
            // Wait while running
            while (isRunning && v2rayProcess?.isAlive == true) {
                delay(1000)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "V2Ray error: ${e.message}")
            e.printStackTrace()
        } finally {
            stopVpn()
        }
    }

    private fun copyV2RayFromAssets(v2rayFile: File) {
        try {
            val assets = assets.list("")
            val v2rayAssets = assets?.find { it.startsWith("v2ray") || it.contains("v2ray") }
            
            if (v2rayAssets != null) {
                assets.open(v2rayAssets).use { input ->
                    FileOutputStream(v2rayFile).use { output ->
                        input.copyTo(output)
                    }
                }
                v2rayFile.setExecutable(true)
                Log.d(TAG, "Copied V2Ray from assets")
            } else {
                Log.w(TAG, "V2Ray not found in assets")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy V2Ray: ${e.message}")
        }
    }

    private fun createV2RayConfig(node: ProxyNode): String {
        val outbound = mutableMapOf<String, Any>()
        
        val uuid = if (node.password.isNotEmpty()) node.password else node.uuid
        
        when (node.type) {
            "vless" -> {
                outbound["protocol"] = "vless"
                outbound["settings"] = mapOf(
                    "vnext" to listOf(mapOf(
                        "address" to node.server,
                        "port" to node.port,
                        "users" to listOf(mapOf(
                            "id" to uuid,
                            "flow" to (node.flow ?: "")
                        ))
                    ))
                )
            }
            "vmess" -> {
                outbound["protocol"] = "vmess"
                outbound["settings"] = mapOf(
                    "vnext" to listOf(mapOf(
                        "address" to node.server,
                        "port" to node.port,
                        "users" to listOf(mapOf(
                            "id" to uuid,
                            "alterId" to 0
                        ))
                    ))
                )
            }
            "shadowsocks", "ss" -> {
                outbound["protocol"] = "shadowsocks"
                outbound["settings"] = mapOf(
                    "servers" to listOf(mapOf(
                        "address" to node.server,
                        "port" to node.port,
                        "method" to "aes-256-gcm",
                        "password" to node.password
                    ))
                )
            }
            "trojan" -> {
                outbound["protocol"] = "trojan"
                outbound["settings"] = mapOf(
                    "servers" to listOf(mapOf(
                        "address" to node.server,
                        "port" to node.port,
                        "password" to node.password,
                        "sni" to (node.serverName ?: "")
                    ))
                )
            }
        }
        
        val streamSettings = mutableMapOf<String, Any>()
        val network = node.network ?: "tcp"
        streamSettings["network"] = network
        
        if (node.tls?.isNotEmpty() == true) {
            streamSettings["security"] = node.tls
            if (!node.serverName.isNullOrEmpty()) {
                streamSettings["tlsSettings"] = mapOf(
                    "serverName" to node.serverName
                )
            }
        }
        
        outbound["streamSettings"] = streamSettings

        val config = mapOf(
            "log" to mapOf("loglevel" to "warning"),
            "inbounds" to listOf(mapOf(
                "tag" to "socks-in",
                "port" to 10808,
                "listen" to "127.0.0.1",
                "protocol" to "socks",
                "settings" to mapOf(
                    "accounts" to listOf(mapOf("user" to "leo", "pass" to "leo123")),
                    "udp" to true
                )
            )),
            "outbounds" to listOf(outbound)
        )

        return gson.toJson(config)
    }

    private fun createVpnInterface() {
        try {
            val builder = Builder()
                .setSession("Leo游戏加速器")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(1500)

            val vpn = builder.establish()
            if (vpn != null) {
                vpnInterface = vpn
                isRunning = true
                Log.d(TAG, "VPN interface established")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VPN: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun updateNotification(text: String) {
        try {
            val notification = createNotification(text)
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopVpn() {
        isRunning = false
        try {
            v2rayProcess?.destroy()
        } catch (e: Exception) {}
        v2rayProcess = null
        
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        
        try {
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {}
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

    private fun createNotification(text: String = "正在连接..."): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Leo游戏加速器")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Leo游戏加速器")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}