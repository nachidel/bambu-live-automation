package com.nachidel.bambu.live.obs

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Socket

class ObsMonitor(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 2000
) {

    private val logger =
        LoggerFactory.getLogger("OBS")

    fun isReachable(): Boolean {

        return try {

            Socket().use { socket ->

                socket.connect(
                    InetSocketAddress(
                        host,
                        port
                    ),
                    connectTimeoutMs
                )
            }

            true

        } catch (_: Exception) {

            false
        }
    }

    suspend fun waitUntilReachable(
        startupTimeoutSeconds: Long = 120,
        intervalMs: Long = 2000
    ): Boolean {

        logger.info(
            "Waiting for OBS WebSocket on {}:{}",
            host,
            port
        )

        val deadline =
            System.currentTimeMillis() +
                    startupTimeoutSeconds * 1000

        var attempt = 0

        while (
            System.currentTimeMillis() < deadline
        ) {

            attempt++

            if (isReachable()) {

                logger.info(
                    "OBS WebSocket is reachable on {}:{} after {} attempt(s)",
                    host,
                    port,
                    attempt
                )

                return true
            }

            logger.debug(
                "OBS WebSocket not ready (attempt {})",
                attempt
            )

            delay(intervalMs)
        }

        logger.error(
            "OBS WebSocket did not become reachable within {} seconds",
            startupTimeoutSeconds
        )

        return false
    }
}