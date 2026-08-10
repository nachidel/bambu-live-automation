package com.nachidel.bambu.live.simulator

import com.nachidel.bambu.event.BambuEvent
import com.nachidel.bambu.model.PrinterSnapshot
import com.nachidel.bambu.model.PrinterState
import kotlinx.coroutines.delay

class BambuEventSimulator {

    suspend fun run(
        onEvent: suspend (BambuEvent) -> Unit
    ) {

        println()
        println("=== BAMBU EVENT SIMULATOR ===")
        println("p = prepare")
        println("s = start")
        println("a = pause")
        println("r = resume")
        println("f = finish")
        println("x = failed")
        println("i = startup while already printing")
        println("0 = initial finished snapshot")
        println("c = complete print scenario")
        println("q = quit")
        println()

        while (true) {

            print("simulation> ")

            when (
                readln()
                    .trim()
                    .lowercase()
            ) {

                "p" -> {
                    preparing(onEvent)
                }

                "s" -> {
                    started(onEvent)
                }

                "a" -> {
                    paused(onEvent)
                }

                "r" -> {
                    resumed(onEvent)
                }

                "f" -> {
                    finished(onEvent)
                }

                "x" -> {
                    failed(onEvent)
                }

                "i" -> {
                    startupAlreadyPrinting(onEvent)
                }

                "0" -> {
                    initialFinished(onEvent)
                }

                "c" -> {
                    completeScenario(onEvent)
                }

                "q" -> {
                    println("Simulator stopped.")
                    return
                }

                else -> {
                    println("Unknown command.")
                }
            }
        }
    }

    private suspend fun preparing(
        emit: suspend (BambuEvent) -> Unit
    ) {

        val snapshot =
            snapshot(
                state = PrinterState.PREPARING,
                rawState = "PREPARE",
                percent = 0
            )

        emit(
            BambuEvent.PrinterPreparing(
                snapshot
            )
        )

        emit(
            BambuEvent.PrinterStatusChanged(
                snapshot
            )
        )
    }

    private suspend fun started(
        emit: suspend (BambuEvent) -> Unit
    ) {

        val snapshot =
            snapshot(
                state = PrinterState.PRINTING,
                rawState = "RUNNING",
                percent = 1
            )

        emit(
            BambuEvent.PrinterStarted(
                snapshot
            )
        )

        emit(
            BambuEvent.PrinterStatusChanged(
                snapshot
            )
        )
    }

    private suspend fun paused(
        emit: suspend (BambuEvent) -> Unit
    ) {

        val snapshot =
            snapshot(
                state = PrinterState.PAUSED,
                rawState = "PAUSE",
                percent = 35
            )

        emit(
            BambuEvent.PrinterPaused(
                snapshot = snapshot
            )
        )

        emit(
            BambuEvent.PrinterStatusChanged(
                snapshot
            )
        )
    }

    private suspend fun resumed(
        emit: suspend (BambuEvent) -> Unit
    ) {

        val snapshot =
            snapshot(
                state = PrinterState.PRINTING,
                rawState = "RUNNING",
                percent = 35
            )

        emit(
            BambuEvent.PrinterResumed(
                snapshot
            )
        )

        emit(
            BambuEvent.PrinterStatusChanged(
                snapshot
            )
        )
    }

    private suspend fun finished(
        emit: suspend (BambuEvent) -> Unit
    ) {

        val snapshot =
            snapshot(
                state = PrinterState.FINISHED,
                rawState = "FINISH",
                percent = 100
            )

        emit(
            BambuEvent.PrinterFinished(
                snapshot
            )
        )

        emit(
            BambuEvent.PrinterStatusChanged(
                snapshot
            )
        )
    }

    private suspend fun failed(
        emit: suspend (BambuEvent) -> Unit
    ) {

        val snapshot =
            snapshot(
                state = PrinterState.FAILED,
                rawState = "FAILED",
                percent = 42
            )

        emit(
            BambuEvent.PrinterFailed(
                snapshot
            )
        )

        emit(
            BambuEvent.PrinterStatusChanged(
                snapshot
            )
        )
    }

    /*
     * Simulates application startup/reconnection while
     * the printer is already printing.
     *
     * Deliberately NO PrinterStarted event.
     */
    private suspend fun startupAlreadyPrinting(
        emit: suspend (BambuEvent) -> Unit
    ) {

        emit(
            BambuEvent.PrinterStatusChanged(
                snapshot(
                    state = PrinterState.PRINTING,
                    rawState = "RUNNING",
                    percent = 47
                )
            )
        )
    }

    /*
     * Simulates the first MQTT snapshot when the previous
     * print is already finished.
     *
     * Deliberately NO PrinterFinished event.
     */
    private suspend fun initialFinished(
        emit: suspend (BambuEvent) -> Unit
    ) {

        emit(
            BambuEvent.PrinterStatusChanged(
                snapshot(
                    state = PrinterState.FINISHED,
                    rawState = "FINISH",
                    percent = 100
                )
            )
        )
    }

    private suspend fun completeScenario(
        emit: suspend (BambuEvent) -> Unit
    ) {

        println("Starting complete simulated print...")

        preparing(emit)

        delay(500)

        started(emit)

        delay(500)

        paused(emit)

        delay(500)

        resumed(emit)

        delay(500)

        finished(emit)

        println("Complete scenario finished.")
    }

    private fun snapshot(
        state: PrinterState,
        rawState: String,
        percent: Int
    ): PrinterSnapshot {

        return PrinterSnapshot(
            state = state,
            rawGcodeState = rawState,
            jobId = "SIMULATED-JOB-001",
            subtaskName = "Simulated print",
            percent = percent,
            currentLayer =
                when (state) {
                    PrinterState.FINISHED -> 100
                    else -> percent
                },
            totalLayers = 100,
            remainingTime =
                if (state == PrinterState.FINISHED) {
                    0
                } else {
                    60
                }
        )
    }
}