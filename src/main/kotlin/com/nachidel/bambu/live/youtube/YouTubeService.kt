package com.nachidel.bambu.live.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

class YouTubeService(
    private val oauth: YouTubeOAuthClient
) {

    companion object {
        private const val AUTOMATION_STREAM_TITLE =
            "bambu-live-automation"

        private const val STREAM_PARTS =
            "id%2Csnippet%2Ccdn%2Cstatus"

        private const val BROADCAST_PARTS =
            "id%2Csnippet%2Cstatus%2CcontentDetails"

        private val ALLOWED_TRANSITIONS =
            setOf("live", "testing", "complete")
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    private val httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    suspend fun myChannels(): List<YouTubeChannelInfo> =
        withContext(Dispatchers.IO) {
            val accessToken = oauth.accessToken()

            val request =
                HttpRequest.newBuilder(
                    URI.create(
                        "https://www.googleapis.com/youtube/v3/channels" +
                                "?part=id%2Csnippet" +
                                "&mine=true"
                    )
                )
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .GET()
                    .build()

            val response =
                httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )

            if (response.statusCode() !in 200..299) {
                error(
                    "YouTube API error " +
                            "(${response.statusCode()}): ${response.body()}"
                )
            }

            val root =
                json.parseToJsonElement(response.body()).jsonObject

            root["items"]
                ?.jsonArray
                ?.map { item ->
                    val channel = item.jsonObject
                    val snippet = channel["snippet"]?.jsonObject

                    YouTubeChannelInfo(
                        id =
                            channel["id"]
                                ?.jsonPrimitive
                                ?.content
                                ?: "",
                        title =
                            snippet
                                ?.get("title")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: ""
                    )
                }
                ?: emptyList()
        }

    suspend fun ensureAutomationLiveStream(): YouTubeLiveStreamInfo =
        withContext(Dispatchers.IO) {
            findAutomationLiveStream()
                ?: createAutomationLiveStream()
        }

    suspend fun getLiveStream(
        streamId: String
    ): YouTubeLiveStreamInfo =
        withContext(Dispatchers.IO) {
            val accessToken = oauth.accessToken()

            val request =
                HttpRequest.newBuilder(
                    URI.create(
                        "https://www.googleapis.com/youtube/v3/liveStreams" +
                                "?part=$STREAM_PARTS" +
                                "&id=${urlEncode(streamId)}"
                    )
                )
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .GET()
                    .build()

            val response =
                httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )

            if (response.statusCode() !in 200..299) {
                error(
                    "Unable to read YouTube live stream " +
                            "(${response.statusCode()}): ${response.body()}"
                )
            }

            val root =
                json.parseToJsonElement(response.body()).jsonObject

            val stream =
                root["items"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?: error(
                        "YouTube live stream not found: $streamId"
                    )

            parseLiveStream(stream)
        }

    suspend fun createBroadcastAndBind(
        title: String,
        description: String,
        privacyStatus: String,
        streamId: String
    ): YouTubeLiveBroadcastInfo =
        withContext(Dispatchers.IO) {

            val broadcast =
                createBroadcast(
                    title = title,
                    description = description,
                    privacyStatus = privacyStatus,
                    enableAutoStart = false,
                    enableAutoStop = false
                )

            bindBroadcast(
                broadcastId = broadcast.id,
                streamId = streamId
            )
        }

    suspend fun createTestBroadcastAndBind(): YouTubeLiveBroadcastInfo =
        withContext(Dispatchers.IO) {
            val stream = ensureAutomationLiveStream()

            val broadcast =
                createBroadcast(
                    title = "Test bambu-live-automation",
                    description = "Test automatique bambu-live-automation",
                    privacyStatus = "private",
                    enableAutoStart = false,
                    enableAutoStop = false
                )

            bindBroadcast(
                broadcastId = broadcast.id,
                streamId = stream.id
            )
        }

    /*
     * The method name is kept for compatibility with the current Main.kt.
     * AutoStart and AutoStop are intentionally disabled: the application
     * now controls the broadcast lifecycle explicitly with transitionBroadcast().
     */
    suspend fun createPrivateAutoStartTestBroadcastAndBind(): YouTubeLiveBroadcastInfo =
        withContext(Dispatchers.IO) {
            val stream = ensureAutomationLiveStream()

            val broadcast =
                createBroadcast(
                    title = "LIVE TEST bambu-live-automation",
                    description = "Private automatic live test",
                    privacyStatus = "private",
                    enableAutoStart = false,
                    enableAutoStop = false
                )

            bindBroadcast(
                broadcastId = broadcast.id,
                streamId = stream.id
            )
        }

    suspend fun getBroadcast(
        broadcastId: String
    ): YouTubeLiveBroadcastInfo =
        withContext(Dispatchers.IO) {
            val accessToken = oauth.accessToken()

            val request =
                HttpRequest.newBuilder(
                    URI.create(
                        "https://www.googleapis.com/youtube/v3/liveBroadcasts" +
                                "?part=$BROADCAST_PARTS" +
                                "&id=${urlEncode(broadcastId)}"
                    )
                )
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .GET()
                    .build()

            val response =
                httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )

            if (response.statusCode() !in 200..299) {
                error(
                    "Unable to read YouTube broadcast " +
                            "(${response.statusCode()}): ${response.body()}"
                )
            }

            val root =
                json.parseToJsonElement(response.body()).jsonObject

            val broadcast =
                root["items"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?: error(
                        "YouTube broadcast not found: $broadcastId"
                    )

            parseBroadcast(broadcast)
        }

    suspend fun getActiveBroadcasts(): List<YouTubeLiveBroadcastInfo> =
        withContext(Dispatchers.IO) {
            val accessToken = oauth.accessToken()

            val request =
                HttpRequest.newBuilder(
                    URI.create(
                        "https://www.googleapis.com/youtube/v3/liveBroadcasts" +
                                "?part=$BROADCAST_PARTS" +
                                "&broadcastStatus=active"
                    )
                )
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .GET()
                    .build()

            val response =
                httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )

            if (response.statusCode() !in 200..299) {
                error(
                    "Unable to read active YouTube broadcasts " +
                            "(${response.statusCode()}): ${response.body()}"
                )
            }

            val root =
                json.parseToJsonElement(response.body()).jsonObject

            root["items"]
                ?.jsonArray
                ?.map { item ->
                    parseBroadcast(item.jsonObject)
                }
                ?: emptyList()
        }

    suspend fun transitionBroadcast(
        broadcastId: String,
        status: String
    ): YouTubeLiveBroadcastInfo =
        withContext(Dispatchers.IO) {
            require(status in ALLOWED_TRANSITIONS) {
                "Invalid YouTube broadcast transition status: $status"
            }

            val accessToken = oauth.accessToken()

            val request =
                HttpRequest.newBuilder(
                    URI.create(
                        "https://www.googleapis.com/youtube/v3/liveBroadcasts/transition" +
                                "?broadcastStatus=${urlEncode(status)}" +
                                "&id=${urlEncode(broadcastId)}" +
                                "&part=$BROADCAST_PARTS"
                    )
                )
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()

            val response =
                httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )

            if (response.statusCode() !in 200..299) {
                error(
                    "Unable to transition YouTube broadcast to $status " +
                            "(${response.statusCode()}): ${response.body()}"
                )
            }

            parseBroadcast(
                json.parseToJsonElement(response.body()).jsonObject
            )
        }

    private suspend fun findAutomationLiveStream(): YouTubeLiveStreamInfo? {
        val accessToken = oauth.accessToken()

        val request =
            HttpRequest.newBuilder(
                URI.create(
                    "https://www.googleapis.com/youtube/v3/liveStreams" +
                            "?part=$STREAM_PARTS" +
                            "&mine=true" +
                            "&maxResults=50"
                )
            )
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .GET()
                .build()

        val response =
            withContext(Dispatchers.IO) {
                httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )
            }

        if (response.statusCode() !in 200..299) {
            error(
                "YouTube API error " +
                        "(${response.statusCode()}): ${response.body()}"
            )
        }

        val root =
            json.parseToJsonElement(response.body()).jsonObject

        return root["items"]
            ?.jsonArray
            ?.map { item ->
                parseLiveStream(item.jsonObject)
            }
            ?.firstOrNull { stream ->
                stream.title == AUTOMATION_STREAM_TITLE
            }
    }

    private suspend fun createAutomationLiveStream(): YouTubeLiveStreamInfo {
        val accessToken = oauth.accessToken()

        val body =
            buildJsonObject {
                put(
                    "snippet",
                    buildJsonObject {
                        put("title", AUTOMATION_STREAM_TITLE)
                        put(
                            "description",
                            "Reusable stream for bambu-live-automation"
                        )
                    }
                )

                put(
                    "cdn",
                    buildJsonObject {
                        /* RTMP also covers RTMPS in the YouTube API. */
                        put("ingestionType", "rtmp")
                        put("resolution", "variable")
                        put("frameRate", "variable")
                    }
                )

                put(
                    "contentDetails",
                    buildJsonObject {
                        put("isReusable", true)
                    }
                )
            }

        val request =
            HttpRequest.newBuilder(
                URI.create(
                    "https://www.googleapis.com/youtube/v3/liveStreams" +
                            "?part=id%2Csnippet%2Ccdn%2CcontentDetails%2Cstatus"
                )
            )
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        body.toString()
                    )
                )
                .build()

        val response =
            withContext(Dispatchers.IO) {
                httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )
            }

        if (response.statusCode() !in 200..299) {
            error(
                "Unable to create YouTube live stream " +
                        "(${response.statusCode()}): ${response.body()}"
            )
        }

        return parseLiveStream(
            json.parseToJsonElement(response.body()).jsonObject
        )
    }

    private suspend fun createBroadcast(
        title: String,
        description: String,
        privacyStatus: String,
        enableAutoStart: Boolean = false,
        enableAutoStop: Boolean = false
    ): YouTubeLiveBroadcastInfo {
        require(title.isNotBlank()) {
            "YouTube broadcast title is empty"
        }

        require(privacyStatus in setOf("private", "unlisted", "public")) {
            "Invalid YouTube privacy status: $privacyStatus"
        }

        val accessToken = oauth.accessToken()

        /*
         * YouTube requires a future scheduledStartTime on insert.
         * The real start is controlled later with transitionBroadcast().
         */
        val scheduledStartTime =
            Instant.now()
                .plusSeconds(60)
                .toString()

        val body =
            buildJsonObject {
                put(
                    "snippet",
                    buildJsonObject {
                        put("title", title)
                        put("description", description)
                        put("scheduledStartTime", scheduledStartTime)
                    }
                )

                put(
                    "status",
                    buildJsonObject {
                        put("privacyStatus", privacyStatus)
                        put("selfDeclaredMadeForKids", false)
                    }
                )

                put(
                    "contentDetails",
                    buildJsonObject {
                        put("enableAutoStart", enableAutoStart)
                        put("enableAutoStop", enableAutoStop)
                        put("enableDvr", true)
                        put("recordFromStart", true)

                        put(
                            "monitorStream",
                            buildJsonObject {
                                /*
                                 * false means there is no testing stage.
                                 * The broadcast can transition directly to live.
                                 */
                                put("enableMonitorStream", false)
                            }
                        )
                    }
                )
            }

        val request =
            HttpRequest.newBuilder(
                URI.create(
                    "https://www.googleapis.com/youtube/v3/liveBroadcasts" +
                            "?part=$BROADCAST_PARTS"
                )
            )
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        body.toString()
                    )
                )
                .build()

        val response =
            withContext(Dispatchers.IO) {
                httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )
            }

        if (response.statusCode() !in 200..299) {
            error(
                "Unable to create YouTube broadcast " +
                        "(${response.statusCode()}): ${response.body()}"
            )
        }

        return parseBroadcast(
            json.parseToJsonElement(response.body()).jsonObject
        )
    }

    private suspend fun bindBroadcast(
        broadcastId: String,
        streamId: String
    ): YouTubeLiveBroadcastInfo {
        val accessToken = oauth.accessToken()

        val url =
            "https://www.googleapis.com/youtube/v3/liveBroadcasts/bind" +
                    "?id=${urlEncode(broadcastId)}" +
                    "&streamId=${urlEncode(streamId)}" +
                    "&part=$BROADCAST_PARTS"

        val request =
            HttpRequest.newBuilder(
                URI.create(url)
            )
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()

        val response =
            withContext(Dispatchers.IO) {
                httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )
            }

        if (response.statusCode() !in 200..299) {
            error(
                "Unable to bind YouTube broadcast " +
                        "(${response.statusCode()}): ${response.body()}"
            )
        }

        return parseBroadcast(
            json.parseToJsonElement(response.body()).jsonObject
        )
    }

    private fun parseLiveStream(
        stream: JsonObject
    ): YouTubeLiveStreamInfo {
        val snippet = stream["snippet"]?.jsonObject
        val cdn = stream["cdn"]?.jsonObject
        val ingestion = cdn?.get("ingestionInfo")?.jsonObject
        val status = stream["status"]?.jsonObject

        return YouTubeLiveStreamInfo(
            id =
                stream["id"]
                    ?.jsonPrimitive
                    ?.content
                    ?: error(
                        "YouTube live stream id missing"
                    ),
            title =
                snippet
                    ?.get("title")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: "",
            ingestionAddress =
                ingestion
                    ?.get("ingestionAddress")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            rtmpsIngestionAddress =
                ingestion
                    ?.get("rtmpsIngestionAddress")
                    ?.jsonPrimitive
                    ?.contentOrNull,

            /*
             * This is the YouTube stream key.
             * NEVER log it.
             */
            streamName =
                ingestion
                    ?.get("streamName")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            status =
                status
                    ?.get("streamStatus")
                    ?.jsonPrimitive
                    ?.contentOrNull
        )
    }

    private fun parseBroadcast(
        broadcast: JsonObject
    ): YouTubeLiveBroadcastInfo {
        val snippet = broadcast["snippet"]?.jsonObject
        val status = broadcast["status"]?.jsonObject
        val contentDetails = broadcast["contentDetails"]?.jsonObject

        return YouTubeLiveBroadcastInfo(
            id =
                broadcast["id"]
                    ?.jsonPrimitive
                    ?.content
                    ?: error(
                        "YouTube broadcast id missing"
                    ),
            title =
                snippet
                    ?.get("title")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: "",
            privacyStatus =
                status
                    ?.get("privacyStatus")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            lifeCycleStatus =
                status
                    ?.get("lifeCycleStatus")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            boundStreamId =
                contentDetails
                    ?.get("boundStreamId")
                    ?.jsonPrimitive
                    ?.contentOrNull
        )
    }

    private fun urlEncode(
        value: String
    ): String =
        URLEncoder.encode(
            value,
            StandardCharsets.UTF_8
        )
}

data class YouTubeChannelInfo(
    val id: String,
    val title: String
)

data class YouTubeLiveStreamInfo(
    val id: String,
    val title: String,
    val ingestionAddress: String?,
    val rtmpsIngestionAddress: String?,
    val streamName: String?,
    val status: String?
)

data class YouTubeLiveBroadcastInfo(
    val id: String,
    val title: String,
    val privacyStatus: String?,
    val lifeCycleStatus: String?,
    val boundStreamId: String?
)