package com.nachidel.bambu.live.studio

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class StudioPcMonitor(
    private val ipAddress: String,
    private val pingTimeoutMs: Long = 3000
) {

    private val logger =
        LoggerFactory.getLogger("StudioPc")

    fun isReachable(): Boolean {

        val windows =
            System.getProperty("os.name")
                .lowercase()
                .contains("win")

        val command =
            if (windows) {

                listOf(
                    "ping",
                    "-n", "1",
                    "-w", pingTimeoutMs.toString(),
                    ipAddress
                )

            } else {

                val timeoutSeconds =
                    ceil(
                        pingTimeoutMs / 1000.0
                    )
                        .toInt()
                        .coerceAtLeast(1)

                listOf(
                    "ping",
                    "-c", "1",
                    "-W", timeoutSeconds.toString(),
                    ipAddress
                )
            }

        return try {

            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

            val completed =
                process.waitFor(
                    pingTimeoutMs + 2000,
                    TimeUnit.MILLISECONDS
                )

            if (!completed) {

                process.destroyForcibly()

                logger.debug(
                    "Ping timeout for {}",
                    ipAddress
                )

                false

            } else {

                process.exitValue() == 0
            }

        } catch (exception: Exception) {

            logger.error(
                "Unable to ping {}",
                ipAddress,
                exception
            )

            false
        }
    }

    suspend fun waitUntilReachable(
        startupTimeoutSeconds: Long = 120,
        intervalMs: Long = 2000
    ): Boolean {

        logger.info(
            "Waiting for studio PC {}",
            ipAddress
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
                    "Studio PC {} is online after {} ping attempt(s)",
                    ipAddress,
                    attempt
                )

                return true
            }

            logger.debug(
                "Studio PC still offline (attempt {})",
                attempt
            )

            delay(intervalMs)
        }

        logger.error(
            "Studio PC {} did not become reachable within {} seconds",
            ipAddress,
            startupTimeoutSeconds
        )

        return false
    }
}