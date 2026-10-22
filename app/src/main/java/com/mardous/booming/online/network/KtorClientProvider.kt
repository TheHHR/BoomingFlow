package com.mardous.booming.online.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

/**
 * Centralized high-performance Ktor HttpClient Factory and Provider.
 * Enforces unified connection pooling, HTTP/2 multiplexing, JSON configuration,
 * request timeouts, exponential backoff retries, and compression across all network calls.
 */
object KtorClientProvider {

    val defaultJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Shared OkHttpClient connection pool across all Ktor instances for maximum TCP reuse.
     */
    private val sharedConnectionPool = ConnectionPool(32, 5, TimeUnit.MINUTES)

    /**
     * Reusable, thread-safe Ktor HttpClient instance with standard timeouts and content negotiation.
     */
    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    dns(FastFallbackDns)
                    connectionPool(sharedConnectionPool)
                    protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(25, TimeUnit.SECONDS)
                    writeTimeout(15, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }

            install(ContentNegotiation) {
                json(defaultJson)
            }

            install(ContentEncoding) {
                gzip()
                deflate()
            }

            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 25_000
                socketTimeoutMillis = 25_000
            }

            install(HttpRequestRetry) {
                maxRetries = 2
                retryIf { _, response ->
                    response.status.value in 500..599
                }
                exponentialDelay(base = 2.0, maxDelayMs = 4000)
            }

            expectSuccess = false
        }
    }
}
