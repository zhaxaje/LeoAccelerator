package com.leo.accelerator

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button

    private var vpnService: LeoVpnService? = null
    private var isConnected = false
    private var currentNode: ProxyNode? = null

    companion object {
        const val DEFAULT_SUBSCRIPTION = "http://t.leocok.cn:2096/clash/zibamzwco99t84mv"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        connectBtn = findViewById(R.id.connectBtn)

        connectBtn.setOnClickListener {
            if (isConnected) {
                disconnectVPN()
            } else {
                connectVPN()
            }
        }

        loadSubscription()
    }

    private fun loadSubscription() {
        statusText.text = "正在加载订阅..."
        lifecycleScope.launch {
            try {
                val nodes = withContext(Dispatchers.IO) {
                    ClashParser.parseSubscription(DEFAULT_SUBSCRIPTION)
                }
                if (nodes.isNotEmpty()) {
                    currentNode = nodes.first()
                    statusText.text = "已加载 ${nodes.size} 个节点: ${currentNode?.name}"
                    connectBtn.isEnabled = true
                    connectBtn.text = "连接加速"
                } else {
                    statusText.text = "加载失败：无节点"
                }
            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
            }
        }
    }

    private fun connectVPN() {
        val node = currentNode
        if (node == null) {
            Toast.makeText(this, "请先等待节点加载完成", Toast.LENGTH_SHORT).show()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, 1)
        } else {
            startVpnService(node)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            currentNode?.let { startVpnService(it) }
        }
    }

    private fun startVpnService(node: ProxyNode) {
        try {
            val nodeJson = Gson().toJson(node)
            val intent = Intent(this, LeoVpnService::class.java).apply {
                putExtra("node", nodeJson)
            }
            startForegroundService(intent)
            isConnected = true
            connectBtn.text = "断开连接"
            statusText.text = "正在连接..."
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            statusText.text = "连接失败"
        }
    }

    private fun disconnectVPN() {
        val intent = Intent(this, LeoVpnService::class.java)
        stopService(intent)
        isConnected = false
        connectBtn.text = "连接加速"
        statusText.text = "已断开"
    }
}