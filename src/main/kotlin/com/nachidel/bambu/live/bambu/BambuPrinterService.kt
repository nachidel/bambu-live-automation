package com.nachidel.bambu.live.bambu

import com.nachidel.bambu.api.BambuCloudClient
import com.nachidel.bambu.event.BambuEvent
import com.nachidel.bambu.model.PrinterSnapshot
import com.nachidel.bambu.model.PrintTask
import com.nachidel.bambu.value.AccessToken
import com.nachidel.bambu.value.SerialNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class BambuPrinterService(
    token: String,
    private val scope: CoroutineScope,
    private val configuredPrinterSerial: String? = null
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

    private var resolvedPrinterSerial: SerialNumber? = null

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

    /**
     * Retourne la dernière tâche Cloud de l'imprimante suivie.
     *
     * Si BAMBU_PRINTER_SERIAL est défini, cette imprimante est utilisée.
     * Sinon, le compte doit exposer une seule imprimante afin de ne jamais
     * sélectionner arbitrairement un périphérique.
     */
    suspend fun latestTask(): PrintTask? =
        client.latestTask(
            resolvePrinterSerial()
        )

    private suspend fun resolvePrinterSerial(): SerialNumber {
        resolvedPrinterSerial
            ?.let {
                return it
            }

        configuredPrinterSerial
            ?.trim()
            ?.takeIf {
                it.isNotEmpty()
            }
            ?.let { value ->
                return SerialNumber(value)
                    .also {
                        resolvedPrinterSerial = it
                    }
            }

        val printers =
            client.printers()

        val serial =
            when (printers.size) {
                0 ->
                    error(
                        "No Bambu printer found for this account"
                    )

                1 ->
                    printers.single().serial

                else ->
                    error(
                        "Several Bambu printers are available; " +
                                "set BAMBU_PRINTER_SERIAL"
                    )
            }

        resolvedPrinterSerial = serial

        return serial
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