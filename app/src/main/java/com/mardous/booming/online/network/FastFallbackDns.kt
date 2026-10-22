package com.mardous.booming.online.data.network

import okhttp3.Dns
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance DNS resolver that:
 * 1. Caches DNS resolutions in-memory (5 min TTL) to eliminate cellular lookup latency.
 * 2. Prioritizes IPv4 to prevent mobile carrier IPv6 black-hole connection hangs (Jio/Airtel/Vi).
 * 3. Falls back to direct IP DoH (Cloudflare 1.1.1.1 and Google 8.8.8.8) if system DNS fails.
 */
object FastFallbackDns : Dns {

    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private val dnsCache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        dnsCache[hostname]?.let { (cachedAddresses, expiresAt) ->
            if (now < expiresAt) {
                return cachedAddresses
            } else {
                dnsCache.remove(hostname)
            }
        }

        val resolved = try {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.size <= 1) addresses else addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
        } catch (e: Exception) {
            // Fallback to DoH when ISP DNS blocks or fails
            resolveViaDoh(hostname) ?: throw e
        }

        if (resolved.isNotEmpty()) {
            dnsCache[hostname] = resolved to (now + CACHE_TTL_MS)
        }

        return resolved
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress>? {
        // Try Cloudflare direct IP first, then Google DoH direct IP
        return queryDoh("https://1.1.1.1/dns-query?name=$hostname&type=A", "application/dns-json")
            ?: queryDoh("https://8.8.8.8/resolve?name=$hostname&type=A", "application/json")
    }

    private fun queryDoh(endpoint: String, acceptHeader: String): List<InetAddress>? {
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.setRequestProperty("Accept", acceptHeader)
            conn.setRequestProperty("User-Agent", "BoomingFlow/1.4.2")
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val regex = """"data":\s*"([0-9.]+)"""".toRegex()
                val ips = regex.findAll(body).map { it.groupValues[1] }.toList()
                if (ips.isNotEmpty()) {
                    ips.map { InetAddress.getByName(it) }
                } else null
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    fun clearCache() {
        dnsCache.clear()
    }
}
