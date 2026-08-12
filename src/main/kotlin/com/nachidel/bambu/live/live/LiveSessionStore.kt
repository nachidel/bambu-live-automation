package com.nachidel.bambu.live.live

import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

data class PersistedLiveSession(
    val jobId: String,
    val broadcastId: String,
    val streamId: String
)

class LiveSessionStore(
    private val path: Path =
        Path.of("state", "youtube-live-session.properties")
) {
    private val logger = LoggerFactory.getLogger("LiveSessionStore")

    fun load(): PersistedLiveSession? {
        if (!Files.isRegularFile(path)) {
            return null
        }

        return try {
            val properties = Properties()

            Files.newInputStream(path).use {
                properties.load(it)
            }

            val jobId =
                properties.getProperty("jobId")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null

            val broadcastId =
                properties.getProperty("broadcastId")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null

            val streamId =
                properties.getProperty("streamId")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null

            PersistedLiveSession(
                jobId = jobId,
                broadcastId = broadcastId,
                streamId = streamId
            )
        } catch (exception: Exception) {
            logger.warn(
                "Unable to read persisted YouTube live session: {}",
                exception.message
            )

            null
        }
    }

    fun save(session: PersistedLiveSession) {
        val parent = path.parent

        if (parent != null) {
            Files.createDirectories(parent)
        }

        val properties =
            Properties().apply {
                setProperty("jobId", session.jobId)
                setProperty("broadcastId", session.broadcastId)
                setProperty("streamId", session.streamId)
            }

        val temporary =
            path.resolveSibling("${path.fileName}.tmp")

        Files.newOutputStream(temporary).use {
            properties.store(
                it,
                "bambu-live-automation persistent YouTube session"
            )
        }

        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary,
                path,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    fun clear() {
        try {
            Files.deleteIfExists(path)
        } catch (exception: Exception) {
            logger.warn(
                "Unable to clear persisted YouTube live session: {}",
                exception.message
            )
        }
    }
}
