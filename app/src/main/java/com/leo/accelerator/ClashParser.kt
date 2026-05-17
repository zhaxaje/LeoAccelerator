package com.leo.accelerator

import okhttp3.OkHttpClient
import okhttp3.Request
import org.yaml.snakeyaml.Yaml
import java.util.concurrent.TimeUnit

class ClashParser {

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()

        fun parseSubscription(url: String): List<ProxyNode> {
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            val content = response.body?.string() ?: return emptyList()

            return parseClashConfig(content)
        }

        private fun parseClashConfig(config: String): List<ProxyNode> {
            val nodes = mutableListOf<ProxyNode>()
            val yaml = Yaml()
            val data = yaml.load<Map<String, Any>>(config)

            val proxies = data["proxies"] as? List<Map<String, Any>> ?: return emptyList()

            for (proxy in proxies) {
                val type = proxy["type"] as? String ?: continue
                val name = proxy["name"] as? String ?: "Unknown"
                val server = proxy["server"] as? String ?: continue
                val port = (proxy["port"] as? Int) ?: 0

                val username = proxy["username"] as? String ?: ""
                val password = proxy["password"] as? String ?: ""

                nodes.add(ProxyNode(
                    name = name,
                    type = type,
                    server = server,
                    port = port,
                    username = username,
                    password = password
                ))
            }

            return nodes
        }
    }
}

data class ProxyNode(
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
)