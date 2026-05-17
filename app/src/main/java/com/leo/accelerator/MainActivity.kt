package com.leo.accelerator

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var nodeText: TextView

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
        nodeText = findViewById(R.id.nodeText)

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
                    nodeText.text = "当前节点: ${currentNode?.name}"
                    statusText.text = "已加载 ${nodes.size} 个节点"
                    Toast.makeText(this@MainActivity, "订阅加载成功", Toast.LENGTH_SHORT).show()
                } else {
                    statusText.text = "加载失败"
                }
            } catch (e: Exception) {
                statusText.text = "订阅解析失败: ${e.message}"
            }
        }
    }

    private fun connectVPN() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 1)
        } else {
            startVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private fun startVpnService() {
        currentNode?.let { node ->
            val intent = Intent(this, LeoVpnService::class.java).apply {
                putExtra("host", node.server)
                putExtra("port", node.port)
                putExtra("username", node.username)
                putExtra("password", node.password)
                putExtra("protocol", node.type)
            }
            startForegroundService(intent)
            isConnected = true
            connectBtn.text = "断开连接"
            statusText.text = "正在连接..."
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