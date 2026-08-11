package com.nachidel.bambu.live.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

class YouTubeOAuthClient(
    private val credentialsPath: Path =
        Path.of("config", "client_secret.json"),

    private val tokenPath: Path =
        Path.of("youtube-token", "token.json"),

    private val scope: String =
        "https://www.googleapis.com/auth/youtube.force-ssl"
) {

    private val logger =
        LoggerFactory.getLogger("YouTubeOAuth")

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    private val httpClient =
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofSeconds(10)
            )
            .build()

    suspend fun accessToken(): String =
        withContext(Dispatchers.IO) {

            val credentials =
                loadCredentials()

            val stored =
                loadToken()

            /*
             * Keep one minute of margin before expiry.
             */
            if (
                stored != null &&
                stored.expiresAt >
                System.currentTimeMillis() + 60_000
            ) {

                return@withContext stored.accessToken
            }

            if (
                stored?.refreshToken != null
            ) {

                logger.debug(
                    "Refreshing YouTube access token"
                )

                val refreshed =
                    refreshToken(
                        credentials,
                        stored.refreshToken
                    )

                saveToken(
                    refreshed
                )

                return@withContext refreshed.accessToken
            }

            logger.info(
                "YouTube authorization required"
            )

            val token =
                authorize(
                    credentials
                )

            saveToken(
                token
            )

            token.accessToken
        }

    private fun loadCredentials(): OAuthCredentials {

        require(
            Files.exists(
                credentialsPath
            )
        ) {
            "YouTube OAuth credentials not found: $credentialsPath"
        }

        val root =
            json
                .parseToJsonElement(
                    Files.readString(
                        credentialsPath
                    )
                )
                .jsonObject

        val installed =
            root["installed"]
                ?.jsonObject
                ?: error(
                    "Invalid OAuth JSON: 'installed' section missing"
                )

        return OAuthCredentials(
            clientId =
                installed["client_id"]
                    ?.jsonPrimitive
                    ?.content
                    ?: error(
                        "OAuth client_id missing"
                    ),

            clientSecret =
                installed["client_secret"]
                    ?.jsonPrimitive
                    ?.contentOrNull
        )
    }

    private fun loadToken(): StoredToken? {

        if (
            !Files.exists(
                tokenPath
            )
        ) {
            return null
        }

        val root =
            json
                .parseToJsonElement(
                    Files.readString(
                        tokenPath
                    )
                )
                .jsonObject

        val accessToken =
            root["access_token"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: return null

        val expiresAt =
            root["expires_at"]
                ?.jsonPrimitive
                ?.longOrNull
                ?: return null

        val refreshToken =
            root["refresh_token"]
                ?.jsonPrimitive
                ?.contentOrNull

        return StoredToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt
        )
    }

    private fun saveToken(
        token: StoredToken
    ) {

        tokenPath.parent?.let {
            Files.createDirectories(
                it
            )
        }

        val content =
            buildJsonObject {

                put(
                    "access_token",
                    token.accessToken
                )

                token.refreshToken?.let {
                    put(
                        "refresh_token",
                        it
                    )
                }

                put(
                    "expires_at",
                    token.expiresAt
                )
            }

        Files.writeString(
            tokenPath,
            json.encodeToString(
                JsonObject.serializer(),
                content
            )
        )

        logger.info(
            "YouTube OAuth token stored"
        )
    }

    private fun authorize(
        credentials: OAuthCredentials
    ): StoredToken {

        val codeVerifier =
            randomUrlSafe(
                64
            )

        val codeChallenge =
            sha256Base64Url(
                codeVerifier
            )

        val state =
            randomUrlSafe(
                32
            )

        ServerSocket(
            0,
            50,
            InetAddress.getByName(
                "127.0.0.1"
            )
        ).use { server ->

            /*
             * Five minutes to complete the browser login.
             */
            server.soTimeout =
                300_000

            val redirectUri =
                "http://127.0.0.1:${server.localPort}/oauth2callback"

            val authorizationUrl =
                buildUrl(
                    AUTHORIZATION_ENDPOINT,
                    mapOf(
                        "client_id" to
                                credentials.clientId,

                        "redirect_uri" to
                                redirectUri,

                        "response_type" to
                                "code",

                        "scope" to
                                scope,

                        "access_type" to
                                "offline",

                        "include_granted_scopes" to
                                "true",

                        /*
                         * Ensures that our first setup
                         * gives us a refresh token.
                         */
                        "prompt" to
                                "consent",

                        "state" to
                                state,

                        "code_challenge" to
                                codeChallenge,

                        "code_challenge_method" to
                                "S256"
                    )
                )

            logger.info(
                "Opening Google authorization page"
            )

            if (
                !openBrowser(
                    authorizationUrl
                )
            ) {

                logger.info(
                    "Open this URL manually:\n{}",
                    authorizationUrl
                )
            }

            server.accept().use { socket ->

                val reader =
                    socket
                        .getInputStream()
                        .bufferedReader(
                            StandardCharsets.UTF_8
                        )

                val requestLine =
                    reader.readLine()
                        ?: error(
                            "Invalid OAuth callback"
                        )

                /*
                 * Consume HTTP headers.
                 */
                while (true) {

                    val line =
                        reader.readLine()

                    if (
                        line == null ||
                        line.isEmpty()
                    ) {
                        break
                    }
                }

                val target =
                    requestLine
                        .split(" ")
                        .getOrNull(1)
                        ?: error(
                            "Invalid OAuth callback request"
                        )

                val callbackUri =
                    URI.create(
                        "http://127.0.0.1$target"
                    )

                val query =
                    parseQuery(
                        callbackUri.rawQuery
                    )

                sendBrowserResponse(
                    socket,
                    query["error"] == null
                )

                val returnedState =
                    query["state"]

                require(
                    returnedState == state
                ) {
                    "Invalid OAuth state"
                }

                query["error"]?.let {
                    error(
                        "Google authorization failed: $it"
                    )
                }

                val code =
                    query["code"]
                        ?: error(
                            "OAuth authorization code missing"
                        )

                return exchangeCode(
                    credentials = credentials,
                    code = code,
                    codeVerifier = codeVerifier,
                    redirectUri = redirectUri
                )
            }
        }
    }

    private fun exchangeCode(
        credentials: OAuthCredentials,
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): StoredToken {

        val parameters =
            mutableMapOf(
                "client_id" to
                        credentials.clientId,

                "code" to
                        code,

                "code_verifier" to
                        codeVerifier,

                "grant_type" to
                        "authorization_code",

                "redirect_uri" to
                        redirectUri
            )

        credentials.clientSecret
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                parameters[
                    "client_secret"
                ] = it
            }

        val response =
            postForm(
                TOKEN_ENDPOINT,
                parameters
            )

        val accessToken =
            response[
                "access_token"
            ]
                ?.jsonPrimitive
                ?.content
                ?: error(
                    "Google access_token missing"
                )

        val refreshToken =
            response[
                "refresh_token"
            ]
                ?.jsonPrimitive
                ?.contentOrNull

        val expiresIn =
            response[
                "expires_in"
            ]
                ?.jsonPrimitive
                ?.long
                ?: 3600L

        return StoredToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt =
                System.currentTimeMillis() +
                        expiresIn * 1000
        )
    }

    private fun refreshToken(
        credentials: OAuthCredentials,
        refreshToken: String
    ): StoredToken {

        val parameters =
            mutableMapOf(
                "client_id" to
                        credentials.clientId,

                "refresh_token" to
                        refreshToken,

                "grant_type" to
                        "refresh_token"
            )

        credentials.clientSecret
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                parameters[
                    "client_secret"
                ] = it
            }

        val response =
            postForm(
                TOKEN_ENDPOINT,
                parameters
            )

        val accessToken =
            response[
                "access_token"
            ]
                ?.jsonPrimitive
                ?.content
                ?: error(
                    "Google access_token missing"
                )

        val expiresIn =
            response[
                "expires_in"
            ]
                ?.jsonPrimitive
                ?.long
                ?: 3600L

        return StoredToken(
            accessToken = accessToken,

            /*
             * A refresh response normally does
             * not return a new refresh token.
             */
            refreshToken = refreshToken,

            expiresAt =
                System.currentTimeMillis() +
                        expiresIn * 1000
        )
    }

    private fun postForm(
        endpoint: String,
        parameters: Map<String, String>
    ): JsonObject {

        val request =
            HttpRequest.newBuilder(
                URI.create(
                    endpoint
                )
            )
                .header(
                    "Content-Type",
                    "application/x-www-form-urlencoded"
                )
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        formEncode(
                            parameters
                        )
                    )
                )
                .build()

        val response =
            httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            )

        if (
            response.statusCode() !in 200..299
        ) {

            error(
                "Google OAuth request failed (${response.statusCode()}): ${response.body()}"
            )
        }

        return json
            .parseToJsonElement(
                response.body()
            )
            .jsonObject
    }

    private fun openBrowser(
        url: String
    ): Boolean {

        return try {

            if (
                Desktop.isDesktopSupported() &&
                Desktop.getDesktop()
                    .isSupported(
                        Desktop.Action.BROWSE
                    )
            ) {

                Desktop.getDesktop()
                    .browse(
                        URI.create(
                            url
                        )
                    )

                true

            } else {

                false
            }

        } catch (
            exception: Exception
        ) {

            logger.debug(
                "Unable to open browser automatically: {}",
                exception.message
            )

            false
        }
    }

    private fun sendBrowserResponse(
        socket: java.net.Socket,
        success: Boolean
    ) {

        val body =
            if (success) {

                """
                <html>
                <body>
                <h2>Authorization successful</h2>
                <p>You can close this window and return to bambu-live-automation.</p>
                </body>
                </html>
                """.trimIndent()

            } else {

                """
                <html>
                <body>
                <h2>Authorization failed</h2>
                <p>You can close this window.</p>
                </body>
                </html>
                """.trimIndent()
            }

        val bytes =
            body.toByteArray(
                StandardCharsets.UTF_8
            )

        val headers =
            buildString {
                append(
                    "HTTP/1.1 200 OK\r\n"
                )
                append(
                    "Content-Type: text/html; charset=UTF-8\r\n"
                )
                append(
                    "Content-Length: ${bytes.size}\r\n"
                )
                append(
                    "Connection: close\r\n"
                )
                append(
                    "\r\n"
                )
            }

        socket
            .getOutputStream()
            .apply {
                write(
                    headers.toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
                write(
                    bytes
                )
                flush()
            }
    }

    private fun buildUrl(
        base: String,
        parameters: Map<String, String>
    ): String {

        return base +
                "?" +
                formEncode(
                    parameters
                )
    }

    private fun formEncode(
        parameters: Map<String, String>
    ): String {

        return parameters
            .entries
            .joinToString(
                "&"
            ) {
                "${encode(it.key)}=${encode(it.value)}"
            }
    }

    private fun encode(
        value: String
    ): String =
        URLEncoder.encode(
            value,
            StandardCharsets.UTF_8
        )

    private fun parseQuery(
        query: String?
    ): Map<String, String> {

        if (
            query.isNullOrBlank()
        ) {
            return emptyMap()
        }

        return query
            .split("&")
            .mapNotNull {

                val parts =
                    it.split(
                        "=",
                        limit = 2
                    )

                if (
                    parts.isEmpty()
                ) {
                    null
                } else {

                    URLDecoder.decode(
                        parts[0],
                        StandardCharsets.UTF_8
                    ) to
                            URLDecoder.decode(
                                parts.getOrElse(1) {
                                    ""
                                },
                                StandardCharsets.UTF_8
                            )
                }
            }
            .toMap()
    }

    private fun randomUrlSafe(
        size: Int
    ): String {

        val bytes =
            ByteArray(
                size
            )

        SecureRandom()
            .nextBytes(
                bytes
            )

        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                bytes
            )
    }

    private fun sha256Base64Url(
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
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                digest
            )
    }

    private data class OAuthCredentials(
        val clientId: String,
        val clientSecret: String?
    )

    private data class StoredToken(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long
    )

    companion object {

        private const val AUTHORIZATION_ENDPOINT =
            "https://accounts.google.com/o/oauth2/v2/auth"

        private const val TOKEN_ENDPOINT =
            "https://oauth2.googleapis.com/token"
    }
}