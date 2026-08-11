package com.nachidel.bambu.live.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BambuCameraService(
    private val rtpUrl: String =
        System.getenv("BAMBU_CAMERA_RTP_URL")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "rtp://127.0.0.1:1234",

    private val configuredPluginUrl: String? =
        System.getenv("BAMBU_CAMERA_PLUGIN_URL")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
) {

    private val logger =
        LoggerFactory.getLogger("BambuCamera")

    private val mutex =
        Mutex()

    private val appData =
        Path.of(
            System.getenv("APPDATA")
                ?: error("APPDATA environment variable missing")
        )

    private val localAppData =
        Path.of(
            System.getenv("LOCALAPPDATA")
                ?: error("LOCALAPPDATA environment variable missing")
        )

    private val studioRoots =
        listOf(
            appData.resolve("BambuStudioBeta"),
            appData.resolve("BambuStudio")
        )

    private val applicationDirectory =
        localAppData
            .resolve("bambu-live-automation")

    private val managedToolsDirectory =
        applicationDirectory
            .resolve("camera-tools")

    private val logDirectory =
        applicationDirectory
            .resolve("logs")

    private val sourceLog =
        logDirectory.resolve("bambu-source.log")

    private val ffmpegLog =
        logDirectory.resolve("ffmpeg-camera.log")

    private var sourceProcess: Process? = null
    private var ffmpegProcess: Process? = null

    suspend fun start(): Boolean =
        mutex.withLock {

            if (isRunningInternal()) {
                logger.info(
                    "H2C camera bridge is already running"
                )

                return@withLock true
            }

            logger.info(
                "Preparing H2C camera bridge"
            )

            try {
                val tools =
                    ensureCameraTools()

                val urlFile =
                    findCameraUrlFile()
                        ?: error(
                            "Bambu camera url.txt not found"
                        )

                logger.info(
                    "Camera tools ready: {}",
                    tools.directory
                )

                logger.info(
                    "Camera URL file found: {}",
                    urlFile
                )

                startProcesses(
                    tools = tools,
                    urlFile = urlFile
                )

                logger.info(
                    "Waiting for H2C video"
                )

                if (!waitUntilVideoReceived()) {
                    logger.error(
                        "H2C camera processes started but no video was detected"
                    )

                    stopProcesses()

                    return@withLock false
                }

                logger.info(
                    "H2C camera video is active"
                )

                true

            } catch (exception: CancellationException) {
                withContext(
                    NonCancellable + Dispatchers.IO
                ) {
                    stopProcesses()
                }

                throw exception

            } catch (exception: Exception) {
                logger.error(
                    "Unable to start H2C camera bridge: {}",
                    exception.message
                )

                stopProcesses()

                false
            }
        }

    suspend fun stop() =
        mutex.withLock {

            if (!isRunningInternal()) {
                sourceProcess = null
                ffmpegProcess = null

                return@withLock
            }

            logger.info(
                "Stopping H2C camera bridge"
            )

            stopProcesses()

            logger.info(
                "H2C camera bridge stopped"
            )
        }

    fun isRunning(): Boolean =
        isRunningInternal()

    private suspend fun ensureCameraTools(): CameraTools {

        findInstalledCameraTools()
            ?.let {
                logger.info(
                    "Using existing Bambu camera tools"
                )

                return it
            }

        logger.warn(
            "bambu_source.exe not found"
        )

        logger.info(
            "Looking for Bambu Studio network plugin download information"
        )

        val pluginUrl =
            resolvePluginDownloadUrl()
                ?: error(
                    "Unable to find the Bambu camera tools download URL. " +
                            "Set BAMBU_CAMERA_PLUGIN_URL to an official Bambu Studio Windows plugin ZIP."
                )

        logger.info(
            "Downloading Bambu camera tools"
        )

        downloadAndInstallCameraTools(
            pluginUrl
        )

        return findToolsInDirectory(
            managedToolsDirectory
        )
            ?: error(
                "bambu_source.exe was not found in the downloaded Bambu package"
            )
    }

    private fun findInstalledCameraTools(): CameraTools? {

        val configuredDirectory =
            System.getenv("BAMBU_CAMERA_TOOLS_DIR")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(Path::of)

        val directories =
            buildList {
                configuredDirectory?.let(::add)

                add(
                    appData
                        .resolve("BambuStudioBeta")
                        .resolve("cameratools")
                )

                add(
                    appData
                        .resolve("BambuStudio")
                        .resolve("cameratools")
                )

                add(
                    managedToolsDirectory
                )
            }

        for (directory in directories) {
            findToolsInDirectory(directory)
                ?.let {
                    return it
                }
        }

        return null
    }

    private fun findToolsInDirectory(
        directory: Path
    ): CameraTools? {

        if (!Files.isDirectory(directory)) {
            return null
        }

        val directSource =
            directory.resolve(
                "bambu_source.exe"
            )

        val directFfmpeg =
            directory.resolve(
                "ffmpeg.exe"
            )

        if (
            Files.isRegularFile(directSource) &&
            Files.isRegularFile(directFfmpeg)
        ) {
            return CameraTools(
                directory = directory,
                bambuSource = directSource,
                ffmpeg = directFfmpeg
            )
        }

        val source =
            findFile(
                directory,
                "bambu_source.exe"
            )
                ?: return null

        val ffmpeg =
            source.parent
                .resolve("ffmpeg.exe")
                .takeIf(Files::isRegularFile)
                ?: findFile(
                    directory,
                    "ffmpeg.exe"
                )
                ?: return null

        return CameraTools(
            directory = source.parent,
            bambuSource = source,
            ffmpeg = ffmpeg
        )
    }

    private fun findFile(
        root: Path,
        fileName: String
    ): Path? {

        if (!Files.isDirectory(root)) {
            return null
        }

        Files.walk(root).use { paths ->
            return paths
                .filter(Files::isRegularFile)
                .filter {
                    it.fileName
                        .toString()
                        .equals(
                            fileName,
                            ignoreCase = true
                        )
                }
                .findFirst()
                .orElse(null)
        }
    }

    private fun findCameraUrlFile(): Path? {

        val configuredFile =
            System.getenv("BAMBU_CAMERA_URL_FILE")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(Path::of)

        val candidates =
            buildList {
                configuredFile?.let(::add)

                studioRoots.forEach { root ->
                    add(
                        root
                            .resolve("cameratools")
                            .resolve("url.txt")
                    )
                }

                add(
                    managedToolsDirectory
                        .resolve("url.txt")
                )
            }

        return candidates
            .firstOrNull(
                ::isValidCameraUrlFile
            )
    }

    private fun isValidCameraUrlFile(
        path: Path
    ): Boolean {

        if (!Files.isRegularFile(path)) {
            return false
        }

        return try {
            val content =
                Files.readString(path)
                    .trim()

            content.startsWith(
                "bambu:///"
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun resolvePluginDownloadUrl(): String? {

        configuredPluginUrl
            ?.let {
                validatePluginUrl(it)

                logger.info(
                    "Using BAMBU_CAMERA_PLUGIN_URL"
                )

                return it
            }

        studioRoots.forEach { root ->

            val otaDirectory =
                root.resolve("ota")

            if (!Files.isDirectory(otaDirectory)) {
                return@forEach
            }

            val manifests =
                mutableListOf<Path>()

            Files.walk(
                otaDirectory,
                5
            ).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter {
                        it.fileName
                            .toString()
                            .equals(
                                "network_plugins.json",
                                ignoreCase = true
                            )
                    }
                    .forEach(
                        manifests::add
                    )
            }

            manifests.forEach { manifest ->

                findWindowsPluginUrl(
                    manifest
                )
                    ?.let {
                        logger.info(
                            "Bambu network plugin download found in {}",
                            manifest
                        )

                        return it
                    }
            }
        }

        return null
    }

    private fun findWindowsPluginUrl(
        manifest: Path
    ): String? {

        return try {
            val json =
                Json.parseToJsonElement(
                    Files.readString(manifest)
                )

            jsonStrings(json)
                .filter {
                    it.startsWith(
                        "https://",
                        ignoreCase = true
                    )
                }
                .filter {
                    it.endsWith(
                        ".zip",
                        ignoreCase = true
                    )
                }
                .filter {
                    it.contains(
                        "win_",
                        ignoreCase = true
                    ) ||
                            it.contains(
                                "windows",
                                ignoreCase = true
                            )
                }
                .filter(
                    ::isOfficialBambuUrl
                )
                .maxByOrNull(
                    ::pluginVersionScore
                )

        } catch (exception: Exception) {
            logger.debug(
                "Unable to read network plugin manifest {}: {}",
                manifest,
                exception.message
            )

            null
        }
    }

    private fun jsonStrings(
        element: JsonElement
    ): Sequence<String> =
        when (element) {
            is JsonObject ->
                element.values
                    .asSequence()
                    .flatMap {
                        jsonStrings(it)
                    }

            is JsonArray ->
                element
                    .asSequence()
                    .flatMap {
                        jsonStrings(it)
                    }

            is JsonPrimitive ->
                if (element.isString) {
                    sequenceOf(
                        element.content
                    )
                } else {
                    emptySequence()
                }

          /*  else ->
                emptySequence()*/
        }

    private fun pluginVersionScore(
        url: String
    ): Long {

        val version =
            Regex(
                """(\d{2})\.(\d{2})\.(\d{2})\.(\d{2})"""
            )
                .find(url)
                ?.groupValues
                ?: return 0

        return version[1].toLong() * 1_000_000 +
                version[2].toLong() * 10_000 +
                version[3].toLong() * 100 +
                version[4].toLong()
    }

    private fun validatePluginUrl(
        url: String
    ) {
        require(
            isOfficialBambuUrl(url)
        ) {
            "BAMBU_CAMERA_PLUGIN_URL must use HTTPS and point to a bambulab.com host"
        }
    }

    private fun isOfficialBambuUrl(
        url: String
    ): Boolean {

        return try {
            val uri =
                URI.create(url)

            val host =
                uri.host
                    ?.lowercase()
                    ?: return false

            uri.scheme.equals(
                "https",
                ignoreCase = true
            ) &&
                    (
                            host == "bambulab.com" ||
                                    host.endsWith(
                                        ".bambulab.com"
                                    )
                            )

        } catch (_: Exception) {
            false
        }
    }

    private suspend fun downloadAndInstallCameraTools(
        url: String
    ) =
        withContext(Dispatchers.IO) {

            validatePluginUrl(url)

            Files.createDirectories(
                applicationDirectory
            )

            val zipFile =
                Files.createTempFile(
                    applicationDirectory,
                    "bambu-network-plugin-",
                    ".zip"
                )

            try {
                val client =
                    HttpClient
                        .newBuilder()
                        .followRedirects(
                            HttpClient.Redirect.NORMAL
                        )
                        .connectTimeout(
                            Duration.ofSeconds(30)
                        )
                        .build()

                val request =
                    HttpRequest
                        .newBuilder(
                            URI.create(url)
                        )
                        .timeout(
                            Duration.ofMinutes(3)
                        )
                        .header(
                            "User-Agent",
                            "bambu-live-automation"
                        )
                        .GET()
                        .build()

                val response =
                    client.send(
                        request,
                        HttpResponse.BodyHandlers.ofFile(
                            zipFile
                        )
                    )

                if (
                    response.statusCode()
                    !in 200..299
                ) {
                    error(
                        "Bambu plugin download failed with HTTP ${response.statusCode()}"
                    )
                }

                logger.info(
                    "Bambu network plugin downloaded"
                )

                deleteDirectory(
                    managedToolsDirectory
                )

                Files.createDirectories(
                    managedToolsDirectory
                )

                extractZip(
                    zipFile = zipFile,
                    destination =
                        managedToolsDirectory
                )

                logger.info(
                    "Bambu camera tools extracted to {}",
                    managedToolsDirectory
                )

            } finally {
                Files.deleteIfExists(
                    zipFile
                )
            }
        }

    private fun extractZip(
        zipFile: Path,
        destination: Path
    ) {

        val normalizedDestination =
            destination
                .toAbsolutePath()
                .normalize()

        ZipInputStream(
            Files.newInputStream(zipFile)
        ).use { zip ->

            while (true) {
                val entry =
                    zip.nextEntry
                        ?: break

                val target =
                    normalizedDestination
                        .resolve(entry.name)
                        .normalize()

                require(
                    target.startsWith(
                        normalizedDestination
                    )
                ) {
                    "Invalid ZIP entry: ${entry.name}"
                }

                if (entry.isDirectory) {
                    Files.createDirectories(
                        target
                    )
                } else {
                    Files.createDirectories(
                        target.parent
                    )

                    Files.copy(
                        zip,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }

                zip.closeEntry()
            }
        }
    }

    private suspend fun startProcesses(
        tools: CameraTools,
        urlFile: Path
    ) =
        withContext(Dispatchers.IO) {

            Files.createDirectories(
                logDirectory
            )

            Files.deleteIfExists(
                sourceLog
            )

            Files.deleteIfExists(
                ffmpegLog
            )

            val urlPath =
                urlFile
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .replace(
                        '\\',
                        '/'
                    )

            val cameraArgument =
                "bambu:///camera/$urlPath"

            val sourceBuilder =
                ProcessBuilder(
                    tools.bambuSource.toString(),
                    cameraArgument
                )
                    .directory(
                        tools.directory.toFile()
                    )
                    .redirectError(
                        sourceLog.toFile()
                    )

            val ffmpegBuilder =
                ProcessBuilder(
                    tools.ffmpeg.toString(),

                    "-nostdin",

                    "-fflags",
                    "nobuffer",

                    "-flags",
                    "low_delay",

                    "-analyzeduration",
                    "10",

                    "-probesize",
                    "3200",

                    "-f",
                    "h264",

                    "-i",
                    "pipe:",

                    "-vcodec",
                    "copy",

                    "-f",
                    "rtp",

                    rtpUrl
                )
                    .directory(
                        tools.directory.toFile()
                    )
                    .redirectError(
                        ffmpegLog.toFile()
                    )

            logger.info(
                "Starting bambu_source -> ffmpeg -> {}",
                rtpUrl
            )

            val processes =
                ProcessBuilder.startPipeline(
                    listOf(
                        sourceBuilder,
                        ffmpegBuilder
                    )
                )

            sourceProcess =
                processes[0]

            ffmpegProcess =
                processes[1]
        }

    private suspend fun waitUntilVideoReceived(): Boolean {

        val timeout =
            30.seconds

        val started =
            System.nanoTime()

        while (
            (System.nanoTime() - started) <
            timeout.inWholeNanoseconds
        ) {
            val source =
                sourceProcess

            val ffmpeg =
                ffmpegProcess

            if (
                source == null ||
                ffmpeg == null ||
                !source.isAlive ||
                !ffmpeg.isAlive
            ) {
                logger.error(
                    "H2C camera process stopped unexpectedly"
                )

                return false
            }

            if (
                sourceReportsVideo() ||
                ffmpegReportsVideo()
            ) {
                return true
            }

            delay(
                500.milliseconds
            )
        }

        return false
    }

    private fun sourceReportsVideo(): Boolean {

        if (!Files.isRegularFile(sourceLog)) {
            return false
        }

        return try {
            val content =
                Files.readString(
                    sourceLog
                )

            val fps =
                Regex(
                    """FPS:(\d+(?:\.\d+)?)"""
                )
                    .findAll(content)
                    .any {
                        it.groupValues[1]
                            .toDoubleOrNull()
                            ?.let { value ->
                                value > 0.0
                            }
                            ?: false
                    }

            val bps =
                Regex(
                    """BPS:(\d+(?:\.\d+)?)K"""
                )
                    .findAll(content)
                    .any {
                        it.groupValues[1]
                            .toDoubleOrNull()
                            ?.let { value ->
                                value > 0.0
                            }
                            ?: false
                    }

            fps || bps

        } catch (_: Exception) {
            false
        }
    }

    private fun ffmpegReportsVideo(): Boolean {

        if (!Files.isRegularFile(ffmpegLog)) {
            return false
        }

        return try {
            val content =
                Files.readString(
                    ffmpegLog
                )

            Regex(
                """frame=\s*(\d+)"""
            )
                .findAll(content)
                .any {
                    it.groupValues[1]
                        .toLongOrNull()
                        ?.let { frame ->
                            frame > 0
                        }
                        ?: false
                }

        } catch (_: Exception) {
            false
        }
    }

    private suspend fun stopProcesses() =
        withContext(Dispatchers.IO) {

            terminateProcess(
                sourceProcess
            )

            terminateProcess(
                ffmpegProcess
            )

            sourceProcess = null
            ffmpegProcess = null
        }

    private fun terminateProcess(
        process: Process?
    ) {

        if (
            process == null ||
            !process.isAlive
        ) {
            return
        }

        process.destroy()

        if (
            !process.waitFor(
                5,
                TimeUnit.SECONDS
            )
        ) {
            process.destroyForcibly()

            process.waitFor(
                5,
                TimeUnit.SECONDS
            )
        }
    }

    private fun isRunningInternal(): Boolean =
        sourceProcess?.isAlive == true &&
                ffmpegProcess?.isAlive == true

    private fun deleteDirectory(
        directory: Path
    ) {

        if (!Files.exists(directory)) {
            return
        }

        Files.walk(directory).use { paths ->
            paths
                .sorted(
                    Comparator.reverseOrder()
                )
                .forEach {
                    Files.deleteIfExists(it)
                }
        }
    }
}

private data class CameraTools(
    val directory: Path,
    val bambuSource: Path,
    val ffmpeg: Path
)