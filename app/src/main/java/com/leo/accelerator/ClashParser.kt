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
                val uuid = proxy["uuid"] as? String ?: password
                val flow = proxy["flow"] as? String ?: ""
                val tls = proxy["tls"] as? String ?: ""
                val network = proxy["network"] as? String ?: "tcp"
                val serverName = proxy["sni"] as? String ?: proxy["servername"] as? String ?: ""

                nodes.add(ProxyNode(
                    name = name,
                    type = type,
                    server = server,
                    port = port,
                    username = username,
                    password = password,
                    uuid = uuid,
                    flow = flow,
                    tls = tls,
                    network = network,
                    serverName = serverName
                ))
            }

            return nodes
        }

        fun generateV2RayConfig(node: ProxyNode): String {
            val outbound = mutableMapOf<String, Any>(
                "tag" to "proxy",
                "protocol" to node.type,
                "settings" to mutableMapOf<String, Any>()
            )

            when (node.type) {
                "vless" -> {
                    (outbound["settings"] as MutableMap<String, Any>)["vlessSettings"] = mutableMapOf(
                        "vnext" to listOf(mapOf(
                            "address" to node.server,
                            "port" to node.port,
                            "users" to listOf(mapOf(
                                "id" to node.uuid,
                                "flow" to node.flow
                            ))
                        ))
                    )
                    outbound["streamSettings"] = buildStreamSettings(node)
                }
                "vmess" -> {
                    (outbound["settings"] as MutableMap<String, Any>)["vmessSettings"] = mutableMapOf(
                        "vnext" to listOf(mapOf(
                            "address" to node.server,
                            "port" to node.port,
                            "users" to listOf(mapOf(
                                "id" to node.uuid,
                                "alterId" to 0
                            ))
                        ))
                    )
                    outbound["streamSettings"] = buildStreamSettings(node)
                }
                "shadowsocks" -> {
                    (outbound["settings"] as MutableMap<String, Any>)["servers"] = listOf(mapOf(
                        "address" to node.server,
                        "port" to node.port,
                        "method" to "aes-256-gcm",
                        "password" to node.password
                    ))
                }
            }

            val config = mapOf(
                "log" to mapOf(
                    "access" to "",
                    "error" to "",
                    "loglevel" to "warning"
                ),
                "inbounds" to listOf(mapOf(
                    "tag" to "tun-in",
                    "port" to 10808,
                    "protocol" to "socks",
                    "settings" to mapOf(
                        "accounts" to listOf(mapOf(
                            "user" to "test",
                            "pass" to "test"
                        ))
                    )
                )),
                "outbounds" to listOf(outbound)
            )

            return com.google.gson.Gson().toJson(config)
        }

        private fun buildStreamSettings(node: ProxyNode): Map<String, Any> {
            val streamSettings = mutableMapOf<String, Any>(
                "network" to node.network
            )

            if (node.tls.isNotEmpty()) {
                streamSettings["security"] = node.tls
                if (node.serverName.isNotEmpty()) {
                    streamSettings["tlsSettings"] = mapOf(
                        "serverName" to node.serverName
                    )
                }
            }

            return streamSettings
        }
    }
}

data class ProxyNode(
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val uuid: String = "",
    val flow: String = "",
    val tls: String = "",
    val network: String = "tcp",
    val serverName: String = ""
)