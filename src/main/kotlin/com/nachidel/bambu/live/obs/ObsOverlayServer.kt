package com.nachidel.bambu.live.obs

import com.nachidel.bambu.model.PrinterSnapshot
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

object ObsOverlayServer {

    private data class ThumbnailState(
        val jobId: String? = null,
        val requestKey: String? = null,
        val bytes: ByteArray? = null,
        val status: String = "waiting-project-file",
        val error: String? = null
    )

    private val snapshot =
        AtomicReference(
            PrinterSnapshot()
        )

    private val thumbnail =
        AtomicReference(
            ThumbnailState()
        )

    private val thumbnailCacheDirectory: Path =
        Path.of(
            System.getenv("BAMBU_THUMBNAIL_CACHE_DIR")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "cache/bambu-thumbnails"
        )

    private val httpClient =
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofSeconds(15)
            )
            .followRedirects(
                HttpClient.Redirect.NORMAL
            )
            .build()

    private val thumbnailExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "bambu-thumbnail"
            ).apply {
                isDaemon = true
            }
        }

    /**
     * Reçoit directement le snapshot publié par bambu-cloud-kotlin.
     *
     * Le téléchargement du 3MF est asynchrone afin de ne jamais
     * bloquer le thread qui traite les événements Bambu.
     */
    fun update(
        newSnapshot: PrinterSnapshot
    ) {
        val previous =
            snapshot.getAndSet(
                newSnapshot
            )

        /*
         * Nouveau job : on retire immédiatement l'ancienne vignette
         * pour éviter d'afficher la pièce précédente pendant que
         * la nouvelle image se télécharge.
         */
        if (
            newSnapshot.jobId != null &&
            newSnapshot.jobId != previous.jobId
        ) {
            val cached =
                loadCachedThumbnail(
                    newSnapshot.jobId!!
                )

            thumbnail.set(
                ThumbnailState(
                    jobId = newSnapshot.jobId,
                    bytes = cached,
                    status =
                        if (cached != null) {
                            "ready"
                        } else {
                            "waiting-project-file"
                        }
                )
            )
        } else if (
            newSnapshot.jobId != null &&
            thumbnail.get().bytes == null
        ) {
            /*
             * Cas d'un redémarrage de l'application alors que
             * l'impression est déjà en cours.
             */
            val cached =
                loadCachedThumbnail(
                    newSnapshot.jobId!!
                )

            if (cached != null) {
                thumbnail.updateAndGet { state ->
                    state.copy(
                        jobId = newSnapshot.jobId,
                        bytes = cached,
                        status = "ready",
                        error = null
                    )
                }
            }
        }

        scheduleThumbnailDownload(
            newSnapshot
        )
    }

    /**
     * Ecoute sur toutes les interfaces réseau du Raspberry Pi afin
     * que la Source navigateur d'OBS puisse atteindre l'overlay.
     */
    fun start(
        port: Int = 8080
    ) {
        val server =
            HttpServer.create(
                InetSocketAddress(
                    "0.0.0.0",
                    port
                ),
                0
            )

        server.createContext(
            "/api/print"
        ) { exchange ->
            sendPrintStatus(
                exchange
            )
        }

        server.createContext(
            "/thumbnail"
        ) { exchange ->
            sendThumbnail(
                exchange
            )
        }

        server.createContext(
            "/obs"
        ) { exchange ->
            send(
                exchange = exchange,
                data = html,
                contentType = "text/html"
            )
        }

        server.executor =
            Executors.newCachedThreadPool()

        server.start()

        println(
            "Overlay OBS disponible sur le port $port : " +
                    "http://<IP_DU_RASPBERRY>:$port/obs"
        )

        println(
            "API impression : " +
                    "http://<IP_DU_RASPBERRY>:$port/api/print"
        )

        println(
            "Thumbnail : " +
                    "http://<IP_DU_RASPBERRY>:$port/thumbnail"
        )
    }

    private fun sendPrintStatus(
        exchange: HttpExchange
    ) {
        val s =
            snapshot.get()

        val thumb =
            thumbnail.get()

        val percent =
            (s.percent ?: 0)
                .coerceIn(
                    0,
                    100
                )

        val thumbnailAvailable =
            thumb.bytes != null &&
                    (
                            thumb.jobId == null ||
                                    s.jobId == null ||
                                    thumb.jobId == s.jobId
                            )

        val json = """
            {
                "jobId": ${jsonString(s.jobId)},
                "name": ${jsonString(s.subtaskName)},
                "state": ${jsonString(s.state.name)},
                "percent": $percent,
                "remainingMinutes": ${s.remainingTime ?: 0},
                "layer": ${s.currentLayer ?: 0},
                "totalLayers": ${s.totalLayers ?: 0},
                "nozzleTemp": ${jsonNumber(s.nozzleTemperature)},
                "nozzleTarget": ${jsonNumber(s.nozzleTargetTemperature)},
                "head0Temp": ${jsonNumber(s.head0Temperature)},
                "head0Target": ${jsonNumber(s.head0TargetTemperature)},
                "head1Temp": ${jsonNumber(s.head1Temperature)},
                "head1Target": ${jsonNumber(s.head1TargetTemperature)},
                "bedTemp": ${jsonNumber(s.bedTemperature)},
                "bedTarget": ${jsonNumber(s.bedTargetTemperature)},
                "thumbnailAvailable": $thumbnailAvailable,
                "thumbnailStatus": ${jsonString(thumb.status)},
                "thumbnailError": ${jsonString(thumb.error)},
                "projectFileUrlAvailable": ${s.projectFileUrl != null}
            }
        """.trimIndent()

        send(
            exchange = exchange,
            data = json,
            contentType = "application/json"
        )
    }

    private fun sendThumbnail(
        exchange: HttpExchange
    ) {
        val image =
            thumbnail.get()
                .bytes

        if (image == null) {
            exchange.responseHeaders.set(
                "Cache-Control",
                "no-cache, no-store, must-revalidate"
            )

            exchange.sendResponseHeaders(
                404,
                -1
            )

            exchange.close()
            return
        }

        exchange.responseHeaders.set(
            "Content-Type",
            "image/png"
        )

        exchange.responseHeaders.set(
            "Cache-Control",
            "no-cache, no-store, must-revalidate"
        )

        exchange.sendResponseHeaders(
            200,
            image.size.toLong()
        )

        exchange.responseBody.use {
            it.write(
                image
            )
        }
    }

    private fun scheduleThumbnailDownload(
        newSnapshot: PrinterSnapshot
    ) {
        val url =
            newSnapshot.projectFileUrl
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: return

        val requestKey =
            listOf(
                newSnapshot.jobId.orEmpty(),
                newSnapshot.plateIndex?.toString().orEmpty(),
                url
            ).joinToString(
                separator = "|"
            )

        val current =
            thumbnail.get()

        /*
         * Même URL / même plateau :
         * pas de téléchargement en double.
         */
        if (
            current.requestKey == requestKey
        ) {
            return
        }

        /*
         * On marque la requête avant de lancer le thread pour
         * dédupliquer les événements MQTT rapprochés.
         */
        thumbnail.set(
            current.copy(
                jobId =
                    newSnapshot.jobId
                        ?: current.jobId,
                requestKey = requestKey,
                bytes = null,
                status = "downloading",
                error = null
            )
        )

        thumbnailExecutor.execute {
            try {
                val bytes =
                    downloadThumbnail(
                        projectFileUrl = url,
                        plateIndex =
                            newSnapshot.plateIndex
                    )

                newSnapshot.jobId
                    ?.let { jobId ->
                        saveCachedThumbnail(
                            jobId = jobId,
                            bytes = bytes
                        )
                    }

                /*
                 * Pendant le téléchargement, un autre job peut
                 * avoir commencé. Dans ce cas on ignore le résultat
                 * devenu obsolète.
                 */
                thumbnail.updateAndGet { state ->
                    if (
                        state.requestKey ==
                        requestKey
                    ) {
                        state.copy(
                            jobId =
                                newSnapshot.jobId,
                            bytes = bytes,
                            status = "ready",
                            error = null
                        )
                    } else {
                        state
                    }
                }

                println(
                    "Thumbnail Bambu récupéré" +
                            (
                                    newSnapshot.plateIndex
                                        ?.let {
                                            " (plateau $it)"
                                        }
                                        ?: ""
                                    )
                )

            } catch (
                exception: Exception
            ) {
                /*
                 * On laisse requestKey en place pour éviter une
                 * boucle de téléchargement sur chaque paquet MQTT.
                 * Une nouvelle URL signée relancera naturellement
                 * une tentative.
                 */
                val message =
                    exception.message
                        ?: exception.javaClass.simpleName

                thumbnail.updateAndGet { state ->
                    if (
                        state.requestKey ==
                        requestKey
                    ) {
                        state.copy(
                            status = "error",
                            error = message
                        )
                    } else {
                        state
                    }
                }

                println(
                    "Impossible de récupérer le thumbnail Bambu : " +
                            message
                )
            }
        }
    }

    private fun cacheFile(
        jobId: String
    ): Path {

        val safeJobId =
            jobId.replace(
                Regex(
                    """[^A-Za-z0-9._-]"""
                ),
                "_"
            )

        return thumbnailCacheDirectory
            .resolve(
                "$safeJobId.png"
            )
    }

    private fun loadCachedThumbnail(
        jobId: String
    ): ByteArray? {

        return runCatching {
            val file =
                cacheFile(
                    jobId
                )

            if (!Files.isRegularFile(file)) {
                return@runCatching null
            }

            Files.readAllBytes(
                file
            )
        }.getOrNull()
    }

    private fun saveCachedThumbnail(
        jobId: String,
        bytes: ByteArray
    ) {
        runCatching {
            Files.createDirectories(
                thumbnailCacheDirectory
            )

            Files.write(
                cacheFile(jobId),
                bytes
            )
        }.onFailure { exception ->
            println(
                "Impossible de mettre le thumbnail en cache : " +
                        (
                                exception.message
                                    ?: exception.javaClass.simpleName
                                )
            )
        }
    }

    private fun downloadThumbnail(
        projectFileUrl: String,
        plateIndex: Int?
    ): ByteArray {
        val request =
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        projectFileUrl
                    )
                )
                .timeout(
                    Duration.ofSeconds(60)
                )
                .header(
                    "User-Agent",
                    "bambu-live-automation"
                )
                .GET()
                .build()

        val response =
            httpClient.send(
                request,
                HttpResponse.BodyHandlers
                    .ofInputStream()
            )

        if (
            response.statusCode() !in 200..299
        ) {
            response.body().close()

            error(
                "HTTP ${response.statusCode()} lors du téléchargement du 3MF"
            )
        }

        response.body().use { body ->
            return extractThumbnail(
                input = body,
                plateIndex = plateIndex
            )
        }
    }

    private fun extractThumbnail(
        input: InputStream,
        plateIndex: Int?
    ): ByteArray {
        val preferredPath =
            plateIndex
                ?.takeIf {
                    it > 0
                }
                ?.let {
                    "metadata/plate_$it.png"
                }

        var firstPlateThumbnail:
                ByteArray? = null

        var genericThumbnail:
                ByteArray? = null

        ZipInputStream(
            input.buffered()
        ).use { zip ->
            while (true) {
                val entry =
                    zip.nextEntry
                        ?: break

                if (
                    entry.isDirectory
                ) {
                    zip.closeEntry()
                    continue
                }

                val name =
                    entry.name
                        .replace(
                            '\\',
                            '/'
                        )
                        .lowercase()

                val isPng =
                    name.endsWith(
                        ".png"
                    )

                if (!isPng) {
                    zip.closeEntry()
                    continue
                }

                /*
                 * Priorité absolue au plateau réellement imprimé.
                 */
                if (
                    preferredPath != null &&
                    name == preferredPath
                ) {
                    return readCurrentZipEntry(
                        zip
                    )
                }

                /*
                 * Fallback : premier aperçu de plateau disponible.
                 */
                if (
                    firstPlateThumbnail == null &&
                    name.matches(
                        Regex(
                            """metadata/plate_\d+\.png"""
                        )
                    )
                ) {
                    firstPlateThumbnail =
                        readCurrentZipEntry(
                            zip
                        )

                    zip.closeEntry()
                    continue
                }

                /*
                 * Certains 3MF Bambu exposent également cette image.
                 */
                if (
                    genericThumbnail == null &&
                    (
                            name ==
                                    "auxiliaries/.thumbnails/thumbnail_3mf.png" ||
                                    name.contains(
                                        "thumbnail"
                                    )
                            )
                ) {
                    genericThumbnail =
                        readCurrentZipEntry(
                            zip
                        )

                    zip.closeEntry()
                    continue
                }

                zip.closeEntry()
            }
        }

        return firstPlateThumbnail
            ?: genericThumbnail
            ?: error(
                "Aucune vignette PNG trouvée dans le 3MF"
            )
    }

    private fun readCurrentZipEntry(
        zip: ZipInputStream
    ): ByteArray {
        val output =
            ByteArrayOutputStream()

        val buffer =
            ByteArray(
                16 * 1024
            )

        var total =
            0

        while (true) {
            val read =
                zip.read(
                    buffer
                )

            if (
                read < 0
            ) {
                break
            }

            total +=
                read

            /*
             * Une vignette de plusieurs dizaines de Mo serait
             * anormale : on protège le Raspberry d'un 3MF corrompu.
             */
            if (
                total >
                MAX_THUMBNAIL_BYTES
            ) {
                error(
                    "Thumbnail 3MF trop volumineux"
                )
            }

            output.write(
                buffer,
                0,
                read
            )
        }

        return output
            .toByteArray()
    }

    private fun jsonNumber(
        value: Double?
    ): String =
        value?.takeIf {
            it.isFinite()
        }
            ?.toString()
            ?: "null"

    private fun jsonString(
        value: String?
    ): String =
        if (
            value == null
        ) {
            "null"
        } else {
            "\"${escape(value)}\""
        }

    private fun send(
        exchange: HttpExchange,
        data: String,
        contentType: String
    ) {
        val bytes =
            data.toByteArray(
                StandardCharsets.UTF_8
            )

        exchange.responseHeaders.set(
            "Content-Type",
            "$contentType; charset=UTF-8"
        )

        exchange.responseHeaders.set(
            "Cache-Control",
            "no-cache, no-store, must-revalidate"
        )

        exchange.sendResponseHeaders(
            200,
            bytes.size.toLong()
        )

        exchange.responseBody.use {
            it.write(
                bytes
            )
        }
    }

    private fun escape(
        value: String
    ): String =
        value
            .replace(
                "\\",
                "\\\\"
            )
            .replace(
                "\"",
                "\\\""
            )
            .replace(
                "\n",
                "\\n"
            )
            .replace(
                "\r",
                ""
            )

    private const val MAX_THUMBNAIL_BYTES =
        20 * 1024 * 1024

    private val html = """
<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">

<style>

html, body {
    margin: 0;
    padding: 0;
    background: transparent;
    font-family: Arial, sans-serif;
    color: white;
}

.container {
    width: 1080px;
    padding: 18px 22px;

    background: rgba(10, 10, 15, 0.88);
    border-radius: 18px;

    box-sizing: border-box;
}

.main {
    display: flex;
    align-items: stretch;
    gap: 22px;
}

.thumbnail-box {
    width: 250px;
    min-width: 250px;
    height: 145px;

    display: flex;
    align-items: center;
    justify-content: center;

    background: rgba(255,255,255,0.055);
    border: 1px solid rgba(255,255,255,0.10);
    border-radius: 13px;

    overflow: hidden;
}

.thumbnail-box.visible {
    display: flex;
}

.thumbnail {
    width: 100%;
    height: 100%;
    object-fit: contain;
    display: none;
}

.thumbnail.visible {
    display: block;
}

.thumbnail-placeholder {
    padding: 14px;
    text-align: center;
    font-size: 13px;
    line-height: 1.35;
    opacity: 0.62;
}

.details {
    flex: 1;
    min-width: 0;
}

.state {
    margin-bottom: 7px;

    font-size: 14px;
    opacity: 0.72;
}

.top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 18px;
}

.name {
    min-width: 0;

    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    font-size: 24px;
    font-weight: bold;
}

.percent {
    flex: 0 0 auto;

    font-size: 31px;
    font-weight: bold;
}

.progress-background {
    margin-top: 14px;

    width: 100%;
    height: 14px;

    background: rgba(255,255,255,0.15);

    border-radius: 20px;
    overflow: hidden;
}

.progress {
    height: 100%;
    width: 0%;

    background: white;

    transition: width 0.5s ease;
}

.info {
    margin-top: 15px;

    display: grid;
    grid-template-columns:
        repeat(5, minmax(0, 1fr));

    gap: 12px;

    font-size: 17px;
}

.label {
    opacity: 0.65;

    font-size: 12px;
    text-transform: uppercase;
}

.value {
    margin-top: 3px;

    font-size: 17px;
}

</style>
</head>

<body>

<div class="container">

    <div class="main">

        <div
            class="thumbnail-box visible"
            id="thumbnailBox"
        >
            <div
                class="thumbnail-placeholder"
                id="thumbnailPlaceholder"
            >
                Aperçu en attente
            </div>

            <img
                class="thumbnail"
                id="thumbnail"
                alt="Aperçu de l'impression"
            />
        </div>

        <div class="details">

            <div
                class="state"
                id="state"
            >
                H2C
            </div>

            <div class="top">

                <div
                    class="name"
                    id="name"
                >
                    Aucune impression
                </div>

                <div
                    class="percent"
                    id="percent"
                >
                    0 %
                </div>

            </div>

            <div class="progress-background">
                <div
                    class="progress"
                    id="progress"
                ></div>
            </div>

            <div class="info">

                <div>
                    <div class="label">
                        Couche
                    </div>
                    <div
                        class="value"
                        id="layer"
                    >
                        -
                    </div>
                </div>

                <div>
                    <div class="label">
                        Temps restant
                    </div>
                    <div
                        class="value"
                        id="remaining"
                    >
                        -
                    </div>
                </div>

                <div>
                    <div class="label">
                        Tête 0
                    </div>
                    <div
                        class="value"
                        id="head0"
                    >
                        -
                    </div>
                </div>

                <div>
                    <div class="label">
                        Tête 1
                    </div>
                    <div
                        class="value"
                        id="head1"
                    >
                        -
                    </div>
                </div>

                <div>
                    <div class="label">
                        Plateau
                    </div>
                    <div
                        class="value"
                        id="bed"
                    >
                        -
                    </div>
                </div>

            </div>

        </div>

    </div>

</div>

<script>

let thumbnailJobId = null;
let thumbnailLoaded = false;

function formatTime(minutes) {

    if (!minutes || minutes <= 0)
        return "-";

    const h = Math.floor(minutes / 60);
    const m = minutes % 60;

    if (h === 0)
        return m + " min";

    return h + " h " +
        String(m).padStart(2, "0");
}

function formatTemperature(
    current,
    target
) {

    if (
        current == null &&
        target == null
    ) {
        return "-";
    }

    const currentText =
        current == null
            ? "-"
            : Math.round(current);

    const targetText =
        target == null
            ? "-"
            : Math.round(target);

    return currentText +
        " / " +
        targetText +
        " °C";
}

function updateThumbnail(s) {

    const box =
        document.getElementById(
            "thumbnailBox"
        );

    const image =
        document.getElementById(
            "thumbnail"
        );

    const placeholder =
        document.getElementById(
            "thumbnailPlaceholder"
        );

    box.classList.add(
        "visible"
    );

    if (!s.thumbnailAvailable) {

        image.classList.remove(
            "visible"
        );

        image.removeAttribute(
            "src"
        );

        thumbnailJobId =
            s.jobId;

        thumbnailLoaded =
            false;

        switch (s.thumbnailStatus) {

            case "downloading":
                placeholder.textContent =
                    "Chargement de l'aperçu…";
                break;

            case "error":
                placeholder.textContent =
                    "Aperçu indisponible";
                break;

            default:
                placeholder.textContent =
                    s.projectFileUrlAvailable
                        ? "Préparation de l'aperçu…"
                        : "En attente du projet Bambu";
                break;
        }

        placeholder.style.display =
            "block";

        return;
    }

    placeholder.style.display =
        "none";

    if (
        !thumbnailLoaded ||
        thumbnailJobId !== s.jobId
    ) {

        thumbnailJobId =
            s.jobId;

        thumbnailLoaded =
            true;

        image.src =
            "/thumbnail?job=" +
            encodeURIComponent(
                s.jobId || ""
            ) +
            "&t=" +
            Date.now();
    }

    image.classList.add(
        "visible"
    );
}

async function update() {

    try {

        const response =
            await fetch(
                "/api/print?t=" +
                Date.now()
            );

        const s =
            await response.json();

        document
            .getElementById("name")
            .textContent =
                s.name ||
                "Aucune impression";

        document
            .getElementById("state")
            .textContent =
                "H2C • " +
                s.state;

        document
            .getElementById("percent")
            .textContent =
                s.percent +
                " %";

        document
            .getElementById("progress")
            .style.width =
                s.percent +
                "%";

        document
            .getElementById("layer")
            .textContent =
                s.totalLayers > 0
                    ? s.layer +
                        " / " +
                        s.totalLayers
                    : s.layer ||
                        "-";

        document
            .getElementById("remaining")
            .textContent =
                formatTime(
                    s.remainingMinutes
                );

        document
            .getElementById("head0")
            .textContent =
                formatTemperature(
                    s.head0Temp,
                    s.head0Target
                );

        document
            .getElementById("head1")
            .textContent =
                formatTemperature(
                    s.head1Temp,
                    s.head1Target
                );

        document
            .getElementById("bed")
            .textContent =
                formatTemperature(
                    s.bedTemp,
                    s.bedTarget
                );

        updateThumbnail(
            s
        );

    } catch (e) {

        document
            .getElementById("state")
            .textContent =
                "H2C • connexion perdue";
    }
}

update();

setInterval(
    update,
    1000
);

</script>

</body>
</html>
    """.trimIndent()
}
