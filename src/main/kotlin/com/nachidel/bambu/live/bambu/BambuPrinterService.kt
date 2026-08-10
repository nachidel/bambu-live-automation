package com.nachidel.bambu.live.bambu

import com.nachidel.bambu.api.BambuCloudClient
import com.nachidel.bambu.event.BambuEvent
import com.nachidel.bambu.model.PrinterSnapshot
import com.nachidel.bambu.value.AccessToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BambuPrinterService(
    token: String,
    private val scope: CoroutineScope
) : AutoCloseable {

    private val client =
        BambuCloudClient {
            accessToken = AccessToken(token)
        }

    private val _snapshot =
        MutableStateFlow<PrinterSnapshot?>(null)

    val snapshot: StateFlow<PrinterSnapshot?> =
        _snapshot.asStateFlow()

    private var eventJob: Job? = null

    suspend fun connect(
        onEvent: suspend (BambuEvent) -> Unit
    ) {
        eventJob =
            scope.launch {
                client.events.collect { event ->

                    if (
                        event is BambuEvent.PrinterStatusEvent
                    ) {
                        _snapshot.value =
                            event.snapshot
                    }

                    onEvent(event)
                }
            }

        client.connect()
    }

    suspend fun disconnect() {
        eventJob?.cancel()
        eventJob = null

        client.disconnect()
    }

    override fun close() {
        client.close()
    }
}