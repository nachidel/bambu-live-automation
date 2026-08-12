package com.nachidel.bambu.live

import com.nachidel.bambu.event.BambuEvent
import com.nachidel.bambu.live.automation.AutomationAction
import com.nachidel.bambu.live.automation.PrintAutomationController
import com.nachidel.bambu.live.bambu.BambuPrinterService
import com.nachidel.bambu.live.camera.BambuCameraService
import com.nachidel.bambu.live.live.LiveStreamingService
import com.nachidel.bambu.live.obs.ObsMonitor
import com.nachidel.bambu.live.obs.ObsOverlayServer
import com.nachidel.bambu.live.obs.ObsWebSocketClient
import com.nachidel.bambu.live.simulator.BambuEventSimulator
import com.nachidel.bambu.live.studio.StudioPcMonitor
import com.nachidel.bambu.live.studio.WakeOnLanService
import com.nachidel.bambu.live.youtube.YouTubeOAuthClient
import com.nachidel.bambu.live.youtube.YouTubeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private val logger = LoggerFactory.getLogger("Application")
private val bambuLogger = LoggerFactory.getLogger("Bambu")
private val automationLogger = LoggerFactory.getLogger("Automation")

private fun envFlag(
    name: String,
    defaultValue: Boolean = false
): Boolean =
    System.getenv(name)
        ?.trim()
        ?.toBooleanStrictOrNull()
        ?: defaultValue

private fun envString(
    name: String,
    defaultValue: String? = null
): String? =
    System.getenv(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: defaultValue

private fun envInt(
    name: String,
    defaultValue: Int
): Int =
    System.getenv(name)
        ?.trim()
        ?.toIntOrNull()
        ?: defaultValue

fun main() = runBlocking {

    /*
     * ============================================================
     * COMMON CONFIGURATION
     * ============================================================
     */

    val youtubeCredentialsPath =
        envString(
            "YOUTUBE_CLIENT_SECRET",
            "config/client_secret.json"
        )!!

    val youtubeTokenPath =
        envString(
            "YOUTUBE_TOKEN_FILE",
            "youtube-token/token.json"
        )!!

    val youtubePrivacy =
        envString(
            "YOUTUBE_PRIVACY",
            "private"
        )!!
            .lowercase()

    require(
        youtubePrivacy in setOf(
            "private",
            "unlisted",
            "public"
        )
    ) {
        "YOUTUBE_PRIVACY must be private, unlisted or public"
    }

    val studioPcIp =
        envString(
            "STUDIO_PC_IP",
            "192.168.1.10"
        )!!

    val obsHost =
        envString(
            "OBS_HOST",
            studioPcIp
        )!!

    val obsPort =
        envInt(
            "OBS_PORT",
            4455
        )

    val obsPassword =
        envString("OBS_PASSWORD")

    val isWindows =
        System.getProperty("os.name")
            .startsWith(
                "Windows",
                ignoreCase = true
            )

    val bambuCameraEnabled =
        envFlag(
            "BAMBU_CAMERA_ENABLED",
            defaultValue = isWindows
        )

    val bambuCameraService =
        if (bambuCameraEnabled) {
            BambuCameraService()
        } else {
            null
        }

    fun createYouTubeService(): YouTubeService {
        val oauth =
            YouTubeOAuthClient(
                credentialsPath = Path.of(youtubeCredentialsPath),
                tokenPath = Path.of(youtubeTokenPath)
            )

        return YouTubeService(oauth)
    }

    /*
     * ============================================================
     * TEST MODES
     * ============================================================
     */

    val youtubeAuthTest = envFlag("YOUTUBE_AUTH_TEST")
    val youtubeStreamSetup = envFlag("YOUTUBE_STREAM_SETUP")
    val youtubeObsSetup = envFlag("YOUTUBE_OBS_SETUP")
    val youtubeBroadcastTest = envFlag("YOUTUBE_BROADCAST_TEST")
    val youtubeLiveTest = envFlag("YOUTUBE_LIVE_TEST")

    val enabledTestModes =
        listOf(
            "YOUTUBE_AUTH_TEST" to youtubeAuthTest,
            "YOUTUBE_STREAM_SETUP" to youtubeStreamSetup,
            "YOUTUBE_OBS_SETUP" to youtubeObsSetup,
            "YOUTUBE_BROADCAST_TEST" to youtubeBroadcastTest,
            "YOUTUBE_LIVE_TEST" to youtubeLiveTest
        )
            .filter { it.second }
            .map { it.first }

    require(enabledTestModes.size <= 1) {
        "Only one test mode can be enabled at a time: " +
                enabledTestModes.joinToString()
    }

    /*
     * ============================================================
     * YOUTUBE OAUTH TEST
     * ============================================================
     */

    if (youtubeAuthTest) {
        logger.warn("Running YouTube OAuth test")

        val youtube = createYouTubeService()
        val channels = youtube.myChannels()

        if (channels.isEmpty()) {
            logger.warn("No YouTube channel found for this Google account")
        } else {
            channels.forEach { channel ->
                logger.info(
                    "YouTube channel: {} ({})",
                    channel.title,
                    channel.id
                )
            }
        }

        return@runBlocking
    }

    /*
     * ============================================================
     * YOUTUBE REUSABLE STREAM SETUP
     * ============================================================
     */

    if (youtubeStreamSetup) {
        logger.warn("Running YouTube live stream setup")

        val youtube = createYouTubeService()
        val stream = youtube.ensureAutomationLiveStream()

        logger.info(
            "YouTube reusable stream ready: id={} status={}",
            stream.id,
            stream.status
        )

        logger.info(
            "YouTube ingestion URL available: {}",
            stream.rtmpsIngestionAddress != null ||
                    stream.ingestionAddress != null
        )

        /*
         * Never log stream.streamName.
         * It is the YouTube stream key.
         */
        logger.info(
            "YouTube stream key available: {}",
            stream.streamName != null
        )

        return@runBlocking
    }

    /*
     * ============================================================
     * YOUTUBE -> OBS SETUP
     * ============================================================
     */

    if (youtubeObsSetup) {
        logger.warn("Running YouTube -> OBS setup")

        val youtube = createYouTubeService()
        val stream = youtube.ensureAutomationLiveStream()

        val server =
            stream.rtmpsIngestionAddress
                ?: stream.ingestionAddress
                ?: error("YouTube ingestion address missing")

        val streamKey =
            stream.streamName
                ?: error("YouTube stream key missing")

        val obs =
            ObsWebSocketClient(
                host = obsHost,
                port = obsPort,
                password = obsPassword
            )

        try {
            obs.connect()

            val streamStatus = obs.getStreamStatus()

            if (streamStatus.active) {
                error(
                    "OBS stream is currently active - refusing to change stream settings"
                )
            }

            logger.info("Configuring OBS stream destination")

            obs.setCustomStreamService(
                server = server,
                key = streamKey
            )

            val settings = obs.getStreamServiceSettings()

            logger.info(
                "OBS stream service configured: type={} server={}",
                settings.type,
                settings.server
            )
        } finally {
            obs.close()
        }

        return@runBlocking
    }

    /*
     * ============================================================
     * YOUTUBE BROADCAST TEST
     * ============================================================
     */

    if (youtubeBroadcastTest) {
        logger.warn("Running YouTube broadcast test")

        val youtube = createYouTubeService()
        val broadcast = youtube.createTestBroadcastAndBind()

        logger.info(
            "YouTube test broadcast created: id={} title='{}'",
            broadcast.id,
            broadcast.title
        )

        logger.info(
            "YouTube broadcast status: lifecycle={} privacy={}",
            broadcast.lifeCycleStatus,
            broadcast.privacyStatus
        )

        logger.info(
            "YouTube broadcast bound to reusable stream: {}",
            broadcast.boundStreamId != null
        )

        return@runBlocking
    }

    /*
     * ============================================================
     * PRIVATE LIVE TEST
     * ============================================================
     *
     * IMPORTANT:
     *
     * YOUTUBE_LIVE_TEST is ALWAYS private.
     * YOUTUBE_PRIVACY is intentionally ignored here.
     */

    if (youtubeLiveTest) {
        logger.warn("Running PRIVATE YouTube live test")

        val liveStreamingService =
            LiveStreamingService(
                youtube = createYouTubeService(),
                obsHost = obsHost,
                obsPort = obsPort,
                obsPassword = obsPassword,
                privacyStatus = "private"
            )

        var cameraStarted = false

        try {
            if (bambuCameraService != null) {
                logger.info("Starting H2C camera")

                cameraStarted =
                    bambuCameraService.start()

                if (!cameraStarted) {
                    error("Unable to start H2C camera")
                }

                logger.info("H2C camera ready")
            }

            val started =
                liveStreamingService.start(
                    printName = "TEST bambu-live-automation"
                )

            if (!started) {
                error("Private live test failed to start")
            }

            logger.info("Private live test running for 10 seconds")

            delay(10_000.milliseconds)
        } finally {
            liveStreamingService.stop()

            if (cameraStarted) {
                bambuCameraService?.stop()
            }
        }

        return@runBlocking
    }

    /*
     * ============================================================
     * PRINT AUTOMATION
     * ============================================================
     */

    val controller = PrintAutomationController()

    /*
     * ============================================================
     * LIVE STREAM SAFETY SWITCH
     * ============================================================
     */

    val obsStreamEnabled =
        envFlag("OBS_STREAM_ENABLED")

    val liveStreamingService =
        if (obsStreamEnabled) {
            LiveStreamingService(
                youtube = createYouTubeService(),
                obsHost = obsHost,
                obsPort = obsPort,
                obsPassword = obsPassword,
                privacyStatus = youtubePrivacy
            )
        } else {
            null
        }

    logger.info(
        "YouTube production privacy: {}",
        youtubePrivacy
    )

    /*
     * ============================================================
     * WAKE-ON-LAN
     * ============================================================
     */

    val wolEnabled =
        envFlag("WOL_ENABLED")

    val wakeOnLan =
        if (wolEnabled) {
            WakeOnLanService(
                macAddress =
                    envString("STUDIO_PC_MAC")
                        ?: error("STUDIO_PC_MAC missing"),

                broadcastAddress =
                    envString("WOL_BROADCAST")
                        ?: error("WOL_BROADCAST missing"),

                port =
                    envInt(
                        "WOL_PORT",
                        7
                    )
            )
        } else {
            null
        }

    /*
     * ============================================================
     * STUDIO PC
     * ============================================================
     */

    val studioPcMonitor =
        StudioPcMonitor(
            ipAddress = studioPcIp,
            pingTimeoutMs = 3000
        )

    /*
     * ============================================================
     * OBS READINESS
     * ============================================================
     */

    val obsMonitor =
        ObsMonitor(
            host = obsHost,
            port = obsPort
        )

    /*
     * ============================================================
     * STARTUP JOB
     * ============================================================
     */

    var studioStartupJob: Job? = null

    /*
     * ============================================================
     * WAIT FOR STUDIO
     * ============================================================
     */

    suspend fun waitForStudio(): Boolean {
        if (studioPcMonitor.isReachable()) {
            automationLogger.info("Studio PC is already online")
        } else {
            automationLogger.info("Studio PC is offline")

            if (wakeOnLan == null) {
                automationLogger.warn(
                    "Wake-on-LAN disabled - cannot wake studio PC"
                )

                return false
            }

            automationLogger.info("Waking studio PC")

            wakeOnLan.wake()

            val pcReady =
                studioPcMonitor.waitUntilReachable(
                    startupTimeoutSeconds = 120,
                    intervalMs = 2000
                )

            if (!pcReady) {
                automationLogger.error("Unable to start studio PC")
                return false
            }
        }

        automationLogger.info("Studio PC ready")
        automationLogger.info("Waiting for OBS")

        val obsReady =
            obsMonitor.waitUntilReachable(
                startupTimeoutSeconds = 120,
                intervalMs = 2000
            )

        if (!obsReady) {
            automationLogger.error("OBS unavailable")
            return false
        }

        automationLogger.info("OBS WebSocket ready")

        return true
    }

    /*
     * ============================================================
     * START LIVE WORKFLOW
     * ============================================================
     */

    suspend fun startLive(printName: String) {
        var cameraStarted = false

        try {
            /*
             * ----------------------------------------------------
             * PC + OBS
             * ----------------------------------------------------
             */

            if (!waitForStudio()) {
                return
            }

            /*
             * ----------------------------------------------------
             * LIVE ENABLED?
             * ----------------------------------------------------
             */

            val liveService = liveStreamingService

            if (liveService == null) {
                automationLogger.warn(
                    "Automatic live streaming is disabled"
                )

                return
            }

            /*
             * ----------------------------------------------------
             * H2C CAMERA
             * ----------------------------------------------------
             */

            val cameraService = bambuCameraService

            if (cameraService != null) {
                automationLogger.info("Starting H2C camera")

                cameraStarted =
                    cameraService.start()

                if (!cameraStarted) {
                    automationLogger.error(
                        "Unable to start H2C camera"
                    )

                    return
                }

                automationLogger.info("H2C camera ready")
            } else {
                automationLogger.info(
                    "H2C camera bridge is disabled"
                )
            }

            /*
             * ----------------------------------------------------
             * OBS -> YOUTUBE
             * ----------------------------------------------------
             */

            ObsOverlayServer.start()

            automationLogger.info(
                "Starting live streaming for '{}' privacy={}",
                printName,
                youtubePrivacy
            )

            val started =
                liveService.start(
                    printName = printName
                )

            if (!started) {
                automationLogger.error(
                    "Unable to start live streaming"
                )

                if (cameraStarted) {
                    cameraService?.stop()
                }

                return
            }

            automationLogger.info(
                "Live streaming started successfully"
            )
        } catch (exception: CancellationException) {
            automationLogger.warn("Live startup cancelled")

            if (cameraStarted) {
                bambuCameraService?.stop()
            }

            throw exception
        } catch (exception: Exception) {
            automationLogger.error(
                "Live startup failed: {}",
                exception.message
            )

            liveStreamingService?.stop()

            if (cameraStarted) {
                bambuCameraService?.stop()
            }
        }
    }

    /*
     * ============================================================
     * STOP LIVE WORKFLOW
     * ============================================================
     */

    suspend fun stopLive() {
        val startupJob = studioStartupJob
        studioStartupJob = null

        /*
         * If FINISHED / FAILED arrives while Windows, OBS,
         * camera or YouTube are still starting, cancel that
         * workflow first.
         */
        startupJob?.cancelAndJoin()

        /*
         * Keep camera running until the broadcast and OBS
         * have actually stopped.
         */
        liveStreamingService?.stop()

        bambuCameraService?.stop()
    }

    /*
     * ============================================================
     * BAMBU EVENT HANDLER
     * ============================================================
     */

    suspend fun handleEvent(event: BambuEvent) {
        bambuLogger.debug("{}", event)

        val printName =
            (event as? BambuEvent.PrinterStatusEvent)
                ?.snapshot
                ?.subtaskName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Impression 3D"

        controller
            .handle(event)
            .forEach { action ->
                when (action) {
                    is AutomationAction.EnsurePrintAutomationStarted -> {
                        automationLogger.info(
                            "Print automation start requested ({})",
                            action.reason
                        )

                        if (studioStartupJob?.isActive == true) {
                            automationLogger.debug(
                                "Studio startup already in progress"
                            )

                            return@forEach
                        }

                        studioStartupJob =
                            this@runBlocking.launch(Dispatchers.IO) {
                                startLive(
                                    printName = printName
                                )
                            }
                    }

                    AutomationAction.PrintPaused -> {
                        automationLogger.warn(
                            "Print paused - live stream remains active"
                        )
                    }

                    AutomationAction.PrintResumed -> {
                        automationLogger.info("Print resumed")
                    }

                    AutomationAction.PrintFinished -> {
                        automationLogger.info("Print finished")
                        stopLive()
                    }

                    AutomationAction.PrintFailed -> {
                        automationLogger.error("Print failed")
                        stopLive()
                    }
                }
            }
    }

    /*
     * ============================================================
     * SIMULATION
     * ============================================================
     */

    val simulation =
        envFlag("BAMBU_SIMULATION")

    logger.info(
        "BAMBU_SIMULATION raw='{}' -> simulation={}",
        System.getenv("BAMBU_SIMULATION"),
        simulation
    )

    if (simulation) {
        logger.warn("Running in SIMULATION mode")

        if (wolEnabled) {
            logger.warn(
                "Wake-on-LAN is ENABLED during simulation"
            )
        } else {
            logger.info(
                "Wake-on-LAN is disabled during simulation"
            )
        }

        if (obsStreamEnabled) {
            logger.warn(
                "Automatic YouTube live streaming is ENABLED during simulation"
            )
        } else {
            logger.info(
                "Automatic YouTube live streaming is disabled during simulation"
            )
        }

        if (bambuCameraEnabled) {
            logger.warn(
                "H2C camera bridge is ENABLED during simulation"
            )
        } else {
            logger.info(
                "H2C camera bridge is disabled during simulation"
            )
        }

        logger.info(
            "YouTube privacy: {}",
            youtubePrivacy
        )

        try {
            BambuEventSimulator()
                .run(::handleEvent)
        } finally {
            stopLive()
        }

        return@runBlocking
    }

    /*
     * ============================================================
     * REAL BAMBU CLOUD
     * ============================================================
     */

    val token =
        envString("BAMBU_TOKEN")
            ?: error("BAMBU_TOKEN missing")

    val bambu =
        BambuPrinterService(
            token = token,
            scope = this
        )

    try {
        logger.info("Connecting to Bambu Cloud...")

        bambu.connect(
            ::handleEvent
        )

        logger.info(
            "Bambu Cloud connected. Waiting for events..."
        )

        awaitCancellation()
    } finally {
        stopLive()

        logger.info("Disconnecting from Bambu Cloud...")

        bambu.disconnect()
        bambu.close()
    }
}