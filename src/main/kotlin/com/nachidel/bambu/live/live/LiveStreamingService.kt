package com.nachidel.bambu.live.live

import com.nachidel.bambu.live.obs.ObsWebSocketClient
import com.nachidel.bambu.live.youtube.YouTubeLiveBroadcastInfo
import com.nachidel.bambu.live.youtube.YouTubeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

class LiveStreamingService(
    private val youtube: YouTubeService,
    private val obsHost: String,
    private val obsPort: Int,
    private val obsPassword: String?,
    private val privacyStatus: String = "private",
    private val sessionStore: LiveSessionStore = LiveSessionStore()
) {
    private val logger = LoggerFactory.getLogger("LiveStreaming")
    private val mutex = Mutex()

    @Volatile
    private var state = LiveStreamingState.IDLE

    private var obsClient: ObsWebSocketClient? = null
    private var broadcast: YouTubeLiveBroadcastInfo? = null

    /*
     * True as soon as WE request StartStream.
     *
     * This is intentionally set before the OBS request so cleanup
     * can still stop the stream if the request partially succeeds.
     */
    private var streamStartRequestedByAutomation = false

    /*
     * True once OBS confirms that the stream is actually active.
     */
    private var streamStartedByAutomation = false

    init {
        require(privacyStatus in setOf("private", "unlisted", "public")) {
            "YouTube privacyStatus must be private, unlisted or public"
        }
    }

    suspend fun start(
        printName: String,
        jobId: String? = null,
        thumbnailBytes: ByteArray? = null,
        thumbnailContentType: String? = null
    ): Boolean =
        mutex.withLock {
            if (state == LiveStreamingState.LIVE) {
                logger.info("Live streaming is already running")
                return@withLock true
            }

            if (state != LiveStreamingState.IDLE) {
                logger.warn("Unable to start live streaming because current state is {}", state)
                return@withLock false
            }

            val normalizedJobId =
                jobId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            state = LiveStreamingState.STARTING
            var startupCompleted = false

            try {
                /*
                 * ====================================================
                 * CONNECT OBS
                 * ====================================================
                 */

                logger.info("Connecting to OBS")

                val obs =
                    ObsWebSocketClient(
                        host = obsHost,
                        port = obsPort,
                        password = obsPassword
                    )

                obs.connect()
                obsClient = obs

                val initialObsStatus = obs.getStreamStatus()

                /*
                 * ====================================================
                 * PREPARE REUSABLE YOUTUBE STREAM
                 * ====================================================
                 */

                logger.info("Preparing reusable YouTube stream")

                val youtubeStream = youtube.ensureAutomationLiveStream()

                val server =
                    youtubeStream.rtmpsIngestionAddress
                        ?: youtubeStream.ingestionAddress
                        ?: error("YouTube ingestion address missing")

                val streamKey =
                    youtubeStream.streamName
                        ?: error("YouTube stream key missing")

                /*
                 * ====================================================
                 * RESUME EXISTING SESSION WHEN POSSIBLE
                 * ====================================================
                 */

                val resumedBroadcast =
                    findResumableBroadcast(
                        jobId = normalizedJobId,
                        streamId = youtubeStream.id
                    )

                if (
                    initialObsStatus.active &&
                    resumedBroadcast == null
                ) {
                    logger.warn(
                        "OBS stream was already active but no matching Bambu/YouTube session was found - refusing to take ownership"
                    )

                    return@withLock false
                }

                if (resumedBroadcast != null) {
                    broadcast = resumedBroadcast
                    state = LiveStreamingState.BROADCAST_READY

                    logger.info(
                        "Resuming YouTube broadcast {} for Bambu job {} lifecycle={}",
                        resumedBroadcast.id,
                        normalizedJobId,
                        resumedBroadcast.lifeCycleStatus
                    )

                    if (
                        initialObsStatus.active &&
                        resumedBroadcast.lifeCycleStatus == "live"
                    ) {
                        /*
                         * OBS is already streaming and YouTube confirms that the
                         * persisted broadcast is still LIVE. This is the exact
                         * crash/restart recovery case, so ownership can safely
                         * be adopted for this same Bambu job.
                         */
                        streamStartRequestedByAutomation = true
                        streamStartedByAutomation = true
                        state = LiveStreamingState.LIVE
                        startupCompleted = true

                        logger.info(
                            "Existing OBS/YouTube live session successfully adopted"
                        )

                        return@withLock true
                    }
                }

                /*
                 * ====================================================
                 * CONFIGURE OBS
                 * ====================================================
                 *
                 * Never change OBS stream settings while it is already
                 * streaming. If it is active here, it was validated above as
                 * belonging to the persisted session for this same Bambu job.
                 */

                if (!initialObsStatus.active) {
                    logger.info("Configuring OBS YouTube destination")

                    obs.setCustomStreamService(
                        server = server,
                        key = streamKey
                    )

                    val obsSettings = obs.getStreamServiceSettings()

                    logger.info(
                        "OBS stream destination ready: type={} server={}",
                        obsSettings.type,
                        obsSettings.server
                    )
                }

                /*
                 * ====================================================
                 * CREATE OR REUSE YOUTUBE BROADCAST
                 * ====================================================
                 */

                val targetBroadcast =
                    resumedBroadcast
                        ?: run {
                            val title =
                                if (printName.isBlank()) {
                                    "Impression 3D"
                                } else {
                                    "Impression 3D - ${printName.trim()}"
                                }

                            logger.info(
                                "Creating YouTube broadcast: '{}' privacy={}",
                                title,
                                privacyStatus
                            )

                            youtube.createBroadcastAndBind(
                                title = title,
                                description = "Diffusion automatique de l'impression 3D",
                                privacyStatus = privacyStatus,
                                streamId = youtubeStream.id
                            )
                                .also { created ->
                                    broadcast = created
                                    state = LiveStreamingState.BROADCAST_READY

                                    if (normalizedJobId != null) {
                                        sessionStore.save(
                                            PersistedLiveSession(
                                                jobId = normalizedJobId,
                                                broadcastId = created.id,
                                                streamId = youtubeStream.id
                                            )
                                        )

                                        logger.info(
                                            "YouTube live session persisted for Bambu job {}",
                                            normalizedJobId
                                        )
                                    }
                                }
                        }

                broadcast = targetBroadcast
                state = LiveStreamingState.BROADCAST_READY

                logger.info(
                    "YouTube broadcast ready: id={} lifecycle={} privacy={}",
                    targetBroadcast.id,
                    targetBroadcast.lifeCycleStatus,
                    privacyStatus
                )

                if (
                    thumbnailBytes != null &&
                    thumbnailContentType != null
                ) {
                    try {
                        youtube.setThumbnail(
                            videoId = targetBroadcast.id,
                            image = thumbnailBytes,
                            contentType = thumbnailContentType
                        )

                        logger.info(
                            "YouTube thumbnail set from Bambu Cloud cover"
                        )
                    } catch (exception: Exception) {
                        logger.warn(
                            "Unable to set YouTube thumbnail: {}",
                            exception.message
                        )
                    }
                } else {
                    logger.warn(
                        "No Bambu Cloud thumbnail available for YouTube broadcast"
                    )
                }

                /*
                 * ====================================================
                 * START OR ADOPT OBS
                 * ====================================================
                 */

                if (initialObsStatus.active) {
                    logger.info(
                        "OBS stream is already active for the resumed session"
                    )

                    streamStartRequestedByAutomation = true
                    streamStartedByAutomation = true
                    state = LiveStreamingState.OBS_STREAMING
                } else {
                    logger.info("Starting OBS stream")

                    streamStartRequestedByAutomation = true
                    obs.startStream()

                    if (!waitForObsStreamStart(obs)) {
                        error("OBS stream did not become active")
                    }

                    streamStartedByAutomation = true
                    state = LiveStreamingState.OBS_STREAMING

                    logger.info("OBS stream is active")
                }

                /*
                 * ====================================================
                 * WAIT FOR YOUTUBE INGEST
                 * ====================================================
                 */

                logger.info("Waiting for YouTube ingest")

                if (!waitForYouTubeIngest(youtubeStream.id)) {
                    error("YouTube did not receive OBS video")
                }

                state = LiveStreamingState.YOUTUBE_INGEST_ACTIVE

                logger.info("YouTube is receiving OBS video")

                /*
                 * ====================================================
                 * ENSURE BROADCAST IS LIVE
                 * ====================================================
                 */

                val currentBroadcast =
                    youtube.getBroadcast(targetBroadcast.id)

                when (currentBroadcast.lifeCycleStatus) {
                    "live" -> {
                        logger.info(
                            "YouTube broadcast {} is already LIVE",
                            currentBroadcast.id
                        )
                    }

                    "liveStarting" -> {
                        state = LiveStreamingState.LIVE_STARTING

                        if (!waitForBroadcastLive(currentBroadcast.id)) {
                            error("YouTube broadcast did not reach LIVE")
                        }
                    }

                    "ready",
                    "testing" -> {
                        logger.info(
                            "Transitioning YouTube broadcast {} to LIVE",
                            currentBroadcast.id
                        )

                        state = LiveStreamingState.LIVE_STARTING

                        val transition =
                            youtube.transitionBroadcast(
                                broadcastId = currentBroadcast.id,
                                status = "live"
                            )

                        logger.info(
                            "YouTube LIVE transition response: lifecycle={}",
                            transition.lifeCycleStatus
                        )

                        val liveReached =
                            if (transition.lifeCycleStatus == "live") {
                                true
                            } else {
                                waitForBroadcastLive(currentBroadcast.id)
                            }

                        if (!liveReached) {
                            error("YouTube broadcast did not reach LIVE")
                        }
                    }

                    else ->
                        error(
                            "YouTube broadcast cannot be resumed from lifecycle=${currentBroadcast.lifeCycleStatus}"
                        )
                }

                state = LiveStreamingState.LIVE
                startupCompleted = true

                logger.info(
                    "YouTube broadcast is LIVE: {}",
                    targetBroadcast.id
                )

                true
            } catch (exception: CancellationException) {
                logger.warn("Live streaming startup cancelled")
                throw exception
            } catch (exception: Exception) {
                logger.error(
                    "Unable to start live streaming: {}",
                    exception.message
                )

                false
            } finally {
                if (!startupCompleted) {
                    cleanupAfterFailedStart()
                }
            }
        }

    /**
     * Disconnect this process from OBS without stopping OBS or completing the
     * YouTube broadcast. The persisted session remains on disk so a new
     * process can adopt it if Bambu reports the same jobId.
     */
    suspend fun detach() =
        mutex.withLock {
            logger.info(
                "Detaching from live session without stopping OBS/YouTube"
            )

            closeObsConnection()
            resetState()
        }

    suspend fun stop() =
        mutex.withLock {
            if (
                state == LiveStreamingState.IDLE &&
                broadcast == null &&
                !streamStartRequestedByAutomation
            ) {
                sessionStore.clear()
                logger.info("Live streaming is already stopped")
                return@withLock
            }

            logger.info("Stopping live streaming")
            state = LiveStreamingState.STOPPING

            try {
                /*
                 * Complete YouTube first while OBS is still sending.
                 */
                completeOwnedBroadcast()
            } catch (exception: Exception) {
                logger.error(
                    "Unable to complete YouTube broadcast: {}",
                    exception.message
                )
            }

            try {
                /*
                 * Stop OBS only if the stream belongs to us.
                 */
                stopOwnedObsStream()
            } catch (exception: Exception) {
                logger.error(
                    "Unable to stop OBS stream: {}",
                    exception.message
                )
            }

            closeObsConnection()
            sessionStore.clear()
            resetState()

            logger.info("Live streaming stopped")
        }

    fun isLive(): Boolean =
        state == LiveStreamingState.LIVE

    fun currentState(): LiveStreamingState =
        state

    private suspend fun findResumableBroadcast(
        jobId: String?,
        streamId: String
    ): YouTubeLiveBroadcastInfo? {
        val resolvedJobId =
            jobId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

        val session =
            sessionStore.load()
                ?: return null

        if (session.jobId != resolvedJobId) {
            logger.info(
                "Persisted YouTube session belongs to Bambu job {}, current job is {}",
                session.jobId,
                resolvedJobId
            )

            return null
        }

        if (session.streamId != streamId) {
            logger.warn(
                "Persisted YouTube session uses a different reusable stream - it will not be resumed"
            )

            return null
        }

        val current =
            try {
                youtube.getBroadcast(session.broadcastId)
            } catch (exception: Exception) {
                logger.warn(
                    "Unable to validate persisted YouTube broadcast {}: {}",
                    session.broadcastId,
                    exception.message
                )

                return null
            }

        if (current.boundStreamId != streamId) {
            logger.warn(
                "Persisted YouTube broadcast {} is no longer bound to the expected stream",
                current.id
            )

            return null
        }

        if (
            current.lifeCycleStatus !in
            setOf(
                "ready",
                "testing",
                "liveStarting",
                "live"
            )
        ) {
            logger.info(
                "Persisted YouTube broadcast {} cannot be resumed because lifecycle={}",
                current.id,
                current.lifeCycleStatus
            )

            sessionStore.clear()
            return null
        }

        return current
    }

    private suspend fun waitForObsStreamStart(obs: ObsWebSocketClient): Boolean {
        for (attempt in 1..15) {
            delay(1000.milliseconds)

            val status = obs.getStreamStatus()

            if (status.active) {
                return true
            }

            logger.debug(
                "Waiting for OBS stream ({}/15)",
                attempt
            )
        }

        return false
    }

    private suspend fun waitForYouTubeIngest(streamId: String): Boolean {
        for (attempt in 1..30) {
            delay(2000.milliseconds)

            val stream = youtube.getLiveStream(streamId)

            logger.debug(
                "YouTube stream status: {} ({}/30)",
                stream.status,
                attempt
            )

            when (stream.status) {
                "active" ->
                    return true

                "error" ->
                    error("YouTube live stream entered ERROR state")
            }
        }

        return false
    }

    private suspend fun waitForBroadcastLive(broadcastId: String): Boolean {
        for (attempt in 1..30) {
            delay(1000.milliseconds)

            val current = youtube.getBroadcast(broadcastId)

            logger.debug(
                "YouTube broadcast lifecycle: {} ({}/30)",
                current.lifeCycleStatus,
                attempt
            )

            when (current.lifeCycleStatus) {
                "live" ->
                    return true

                "complete",
                "revoked" ->
                    error(
                        "YouTube broadcast entered unexpected state: ${current.lifeCycleStatus}"
                    )
            }
        }

        return false
    }

    private suspend fun completeOwnedBroadcast() {
        val currentBroadcast = broadcast ?: return

        var current =
            youtube.getBroadcast(
                currentBroadcast.id
            )

        /*
         * If stop/cancellation happens while YouTube is still
         * moving from liveStarting -> live, wait briefly.
         */
        if (current.lifeCycleStatus == "liveStarting") {
            for (attempt in 1..15) {
                delay(1000.milliseconds)

                current =
                    youtube.getBroadcast(
                        currentBroadcast.id
                    )

                logger.debug(
                    "Waiting for YouTube LIVE transition during cleanup: {} ({}/15)",
                    current.lifeCycleStatus,
                    attempt
                )

                if (current.lifeCycleStatus != "liveStarting") {
                    break
                }
            }
        }

        when (current.lifeCycleStatus) {
            "live" -> {
                logger.info(
                    "Completing YouTube broadcast {}",
                    currentBroadcast.id
                )

                val completion =
                    youtube.transitionBroadcast(
                        broadcastId = currentBroadcast.id,
                        status = "complete"
                    )

                if (completion.lifeCycleStatus == "complete") {
                    logger.info("YouTube broadcast completed")
                    return
                }

                waitForBroadcastComplete(currentBroadcast.id)
            }

            "complete" -> {
                logger.info("YouTube broadcast is already complete")
            }

            else -> {
                logger.warn(
                    "YouTube broadcast completion skipped because lifecycle={}",
                    current.lifeCycleStatus
                )
            }
        }
    }

    private suspend fun waitForBroadcastComplete(broadcastId: String): Boolean {
        for (attempt in 1..15) {
            delay(1000.milliseconds)

            val current = youtube.getBroadcast(broadcastId)

            logger.debug(
                "Waiting for YouTube COMPLETE: {} ({}/15)",
                current.lifeCycleStatus,
                attempt
            )

            if (current.lifeCycleStatus == "complete") {
                logger.info("YouTube broadcast completed")
                return true
            }
        }

        logger.error("YouTube broadcast did not reach COMPLETE")

        return false
    }

    private suspend fun stopOwnedObsStream() {
        /*
         * If WE never requested StartStream,
         * OBS is not ours to stop.
         */
        if (!streamStartRequestedByAutomation) {
            return
        }

        val obs = obsClient ?: return
        val status = obs.getStreamStatus()

        if (!status.active) {
            logger.info("OBS stream is already stopped")
            return
        }

        logger.info("Stopping OBS stream")

        obs.stopStream()

        for (attempt in 1..15) {
            delay(1000.milliseconds)

            val current = obs.getStreamStatus()

            if (!current.active) {
                logger.info("OBS stream stopped")
                return
            }

            logger.debug(
                "Waiting for OBS stream to stop ({}/15)",
                attempt
            )
        }

        logger.error("OBS stream did not stop")
    }

    private suspend fun cleanupAfterFailedStart() {
        try {
            completeOwnedBroadcast()
        } catch (exception: Exception) {
            logger.error(
                "Unable to clean up YouTube after failed startup: {}",
                exception.message
            )
        }

        try {
            stopOwnedObsStream()
        } catch (exception: Exception) {
            logger.error(
                "Unable to clean up OBS after failed startup: {}",
                exception.message
            )
        }

        closeObsConnection()
        resetState()
    }

    private fun closeObsConnection() {
        try {
            obsClient?.close()
        } catch (_: Exception) {
        }

        obsClient = null
    }

    private fun resetState() {
        broadcast = null
        streamStartRequestedByAutomation = false
        streamStartedByAutomation = false
        state = LiveStreamingState.IDLE
    }
}

enum class LiveStreamingState {
    IDLE,
    STARTING,
    BROADCAST_READY,
    OBS_STREAMING,
    YOUTUBE_INGEST_ACTIVE,
    LIVE_STARTING,
    LIVE,
    STOPPING
}