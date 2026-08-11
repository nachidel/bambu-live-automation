package com.nachidel.bambu.live.obs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ObsWebSocketClient(
    private val host: String,
    private val port: Int,
    private val password: String?
) : AutoCloseable {

    private val logger =
        LoggerFactory.getLogger("OBS")

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    private val httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var identified = false

    private var helloDeferred =
        CompletableDeferred<JsonObject>()

    private var identifiedDeferred =
        CompletableDeferred<Int>()

    private val pendingRequests =
        ConcurrentHashMap<
                String,
                CompletableDeferred<JsonObject>
                >()

    suspend fun connect() =
        withContext(Dispatchers.IO) {

            if (identified) {
                return@withContext
            }

            helloDeferred =
                CompletableDeferred()

            identifiedDeferred =
                CompletableDeferred()

            val listener =
                ObsWebSocketListener()

            val socket =
                httpClient
                    .newWebSocketBuilder()
                    .connectTimeout(
                        Duration.ofSeconds(5)
                    )
                    .subprotocols(
                        "obswebsocket.json"
                    )
                    .buildAsync(
                        URI.create(
                            "ws://$host:$port"
                        ),
                        listener
                    )
                    .get(
                        7,
                        TimeUnit.SECONDS
                    )

            webSocket = socket

            /*
             * OBS sends Hello (op 0)
             * immediately after connection.
             */
            val hello =
                withTimeout(5000) {
                    helloDeferred.await()
                }

            val rpcVersion =
                hello["rpcVersion"]
                    ?.jsonPrimitive
                    ?.int
                    ?: 1

            val authentication =
                hello["authentication"]
                    ?.jsonObject

            val identifyData =
                buildJsonObject {

                    /*
                     * Current obs-websocket RPC
                     * version is 1.
                     */
                    put(
                        "rpcVersion",
                        minOf(
                            rpcVersion,
                            1
                        )
                    )

                    /*
                     * We do not need OBS events
                     * yet.
                     */
                    put(
                        "eventSubscriptions",
                        0
                    )

                    if (
                        authentication != null
                    ) {

                        val obsPassword =
                            password
                                ?.takeIf {
                                    it.isNotEmpty()
                                }
                                ?: error(
                                    "OBS authentication required but OBS_PASSWORD is missing"
                                )

                        val challenge =
                            authentication[
                                "challenge"
                            ]
                                ?.jsonPrimitive
                                ?.content
                                ?: error(
                                    "OBS authentication challenge missing"
                                )

                        val salt =
                            authentication[
                                "salt"
                            ]
                                ?.jsonPrimitive
                                ?.content
                                ?: error(
                                    "OBS authentication salt missing"
                                )

                        put(
                            "authentication",
                            createAuthentication(
                                password =
                                    obsPassword,
                                salt =
                                    salt,
                                challenge =
                                    challenge
                            )
                        )
                    }
                }

            val identifyMessage =
                buildJsonObject {
                    put(
                        "op",
                        1
                    )

                    put(
                        "d",
                        identifyData
                    )
                }

            socket
                .sendText(
                    identifyMessage.toString(),
                    true
                )
                .get(
                    3,
                    TimeUnit.SECONDS
                )

            val negotiatedRpcVersion =
                withTimeout(5000) {
                    identifiedDeferred.await()
                }

            identified = true

            logger.info(
                "OBS WebSocket authenticated (RPC {})",
                negotiatedRpcVersion
            )
        }

    suspend fun getVersion(): ObsVersionInfo {

        val response =
            request(
                "GetVersion"
            )

        return ObsVersionInfo(
            obsVersion =
                response[
                    "obsVersion"
                ]
                    ?.jsonPrimitive
                    ?.content,

            obsWebSocketVersion =
                response[
                    "obsWebSocketVersion"
                ]
                    ?.jsonPrimitive
                    ?.content,

            rpcVersion =
                response[
                    "rpcVersion"
                ]
                    ?.jsonPrimitive
                    ?.int
        )
    }

    private suspend fun request(
        requestType: String,
        requestData: JsonObject? = null
    ): JsonObject =
        withContext(Dispatchers.IO) {

            check(identified) {
                "OBS WebSocket is not identified"
            }

            val socket =
                webSocket
                    ?: error(
                        "OBS WebSocket is not connected"
                    )

            val requestId =
                UUID.randomUUID()
                    .toString()

            val deferred =
                CompletableDeferred<JsonObject>()

            pendingRequests[
                requestId
            ] = deferred

            val message =
                buildJsonObject {

                    put(
                        "op",
                        6
                    )

                    put(
                        "d",
                        buildJsonObject {

                            put(
                                "requestType",
                                requestType
                            )

                            put(
                                "requestId",
                                requestId
                            )

                            if (
                                requestData != null
                            ) {

                                put(
                                    "requestData",
                                    requestData
                                )
                            }
                        }
                    )
                }

            try {

                socket
                    .sendText(
                        message.toString(),
                        true
                    )
                    .get(
                        3,
                        TimeUnit.SECONDS
                    )

                val response =
                    withTimeout(5000) {
                        deferred.await()
                    }

                val requestStatus =
                    response[
                        "requestStatus"
                    ]
                        ?.jsonObject
                        ?: error(
                            "OBS response has no requestStatus"
                        )

                val success =
                    requestStatus[
                        "result"
                    ]
                        ?.jsonPrimitive
                        ?.boolean
                        ?: false

                if (!success) {

                    val code =
                        requestStatus[
                            "code"
                        ]
                            ?.jsonPrimitive
                            ?.int

                    val comment =
                        requestStatus[
                            "comment"
                        ]
                            ?.jsonPrimitive
                            ?.content

                    error(
                        "OBS request $requestType failed: code=$code comment=$comment"
                    )
                }

                response[
                    "responseData"
                ] as? JsonObject
                    ?: JsonObject(
                        emptyMap()
                    )

            } finally {

                pendingRequests.remove(
                    requestId
                )
            }
        }

    private fun handleMessage(
        text: String
    ) {

        val message =
            json
                .parseToJsonElement(text)
                .jsonObject

        val op =
            message["op"]
                ?.jsonPrimitive
                ?.int
                ?: return

        val data =
            message["d"]
                ?.jsonObject
                ?: JsonObject(
                    emptyMap()
                )

        when (op) {

            /*
             * Hello
             */
            0 -> {

                helloDeferred.complete(
                    data
                )
            }

            /*
             * Identified
             */
            2 -> {

                val rpcVersion =
                    data[
                        "negotiatedRpcVersion"
                    ]
                        ?.jsonPrimitive
                        ?.int
                        ?: 1

                identifiedDeferred.complete(
                    rpcVersion
                )
            }

            /*
             * RequestResponse
             */
            7 -> {

                val requestId =
                    data[
                        "requestId"
                    ]
                        ?.jsonPrimitive
                        ?.content
                        ?: return

                pendingRequests
                    .remove(
                        requestId
                    )
                    ?.complete(
                        data
                    )
            }
        }
    }

    private fun createAuthentication(
        password: String,
        salt: String,
        challenge: String
    ): String {

        val secret =
            sha256Base64(
                password +
                        salt
            )

        return sha256Base64(
            secret +
                    challenge
        )
    }

    suspend fun getStreamStatus(): ObsStreamStatus {

        val response =
            request(
                "GetStreamStatus"
            )

        return ObsStreamStatus(
            active =
                response[
                    "outputActive"
                ]
                    ?.jsonPrimitive
                    ?.boolean
                    ?: false,

            reconnecting =
                response[
                    "outputReconnecting"
                ]
                    ?.jsonPrimitive
                    ?.boolean
                    ?: false
        )
    }

    suspend fun startStream() {
        request(
            "StartStream"
        )
    }

    suspend fun stopStream() {
        request(
            "StopStream"
        )
    }

    suspend fun getStreamServiceSettings():
            ObsStreamServiceSettings {

        val response =
            request(
                "GetStreamServiceSettings"
            )

        val settings =
            response[
                "streamServiceSettings"
            ]
                ?.jsonObject
                ?: JsonObject(
                    emptyMap()
                )

        return ObsStreamServiceSettings(
            type =
                response[
                    "streamServiceType"
                ]
                    ?.jsonPrimitive
                    ?.content
                    ?: "",

            server =
                settings[
                    "server"
                ]
                    ?.jsonPrimitive
                    ?.contentOrNull
        )
    }

    suspend fun setCustomStreamService(
        server: String,
        key: String
    ) {

        require(
            server.isNotBlank()
        ) {
            "OBS stream server is empty"
        }

        require(
            key.isNotBlank()
        ) {
            "OBS stream key is empty"
        }

        request(
            "SetStreamServiceSettings",
            buildJsonObject {

                put(
                    "streamServiceType",
                    "rtmp_custom"
                )

                put(
                    "streamServiceSettings",
                    buildJsonObject {

                        put(
                            "server",
                            server
                        )

                        put(
                            "key",
                            key
                        )
                    }
                )
            }
        )
    }

    private fun sha256Base64(
        value: String
    ): String {

        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(
                    value.toByteArray(
                        StandardCharsets.UTF_8
                    )
                )

        return Base64
            .getEncoder()
            .encodeToString(
                digest
            )
    }

    override fun close() {

        identified = false

        val socket =
            webSocket

        webSocket = null

        try {

            socket?.sendClose(
                WebSocket.NORMAL_CLOSURE,
                "Closing"
            )

        } catch (_: Exception) {
        }
    }

    private inner class ObsWebSocketListener :
        WebSocket.Listener {

        private val buffer =
            StringBuilder()

        override fun onOpen(
            webSocket: WebSocket
        ) {

            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean
        ): CompletionStage<*> {

            buffer.append(
                data
            )

            if (last) {

                val message =
                    buffer.toString()

                buffer.setLength(0)

                try {

                    handleMessage(
                        message
                    )

                } catch (
                    exception: Exception
                ) {

                    logger.error(
                        "Unable to process OBS WebSocket message: {}",
                        exception.message
                    )
                }
            }

            webSocket.request(1)

            return CompletableFuture
                .completedFuture(
                    null
                )
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String
        ): CompletionStage<*> {

            identified = false

            val exception =
                IllegalStateException(
                    "OBS WebSocket closed: code=$statusCode reason=$reason"
                )

            if (
                !helloDeferred.isCompleted
            ) {

                helloDeferred
                    .completeExceptionally(
                        exception
                    )
            }

            if (
                !identifiedDeferred.isCompleted
            ) {

                identifiedDeferred
                    .completeExceptionally(
                        exception
                    )
            }

            pendingRequests
                .values
                .forEach {
                    it.completeExceptionally(
                        exception
                    )
                }

            pendingRequests.clear()

            return CompletableFuture
                .completedFuture(
                    null
                )
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable
        ) {

            identified = false

            if (
                !helloDeferred.isCompleted
            ) {

                helloDeferred
                    .completeExceptionally(
                        error
                    )
            }

            if (
                !identifiedDeferred.isCompleted
            ) {

                identifiedDeferred
                    .completeExceptionally(
                        error
                    )
            }

            pendingRequests
                .values
                .forEach {
                    it.completeExceptionally(
                        error
                    )
                }

            pendingRequests.clear()
        }
    }
}

data class ObsVersionInfo(
    val obsVersion: String?,
    val obsWebSocketVersion: String?,
    val rpcVersion: Int?
)

data class ObsStreamStatus(
    val active: Boolean,
    val reconnecting: Boolean
)

data class ObsStreamServiceSettings(
    val type: String,
    val server: String?
)