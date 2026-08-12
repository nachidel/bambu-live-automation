package com.nachidel.bambu.live.automation

import com.nachidel.bambu.event.BambuEvent
import com.nachidel.bambu.live.obs.ObsOverlayServer
import com.nachidel.bambu.model.PrinterState

class PrintAutomationController {

    private var printActive = false
    private var paused = false

    fun handle(
        event: BambuEvent
    ): List<AutomationAction> {

        val actions =
            mutableListOf<AutomationAction>()

        when (event) {

            is BambuEvent.PrinterPreparing -> {
                ensurePrintActive(
                    actions,
                    StartReason.PREPARING_EVENT
                )
            }

            is BambuEvent.PrinterStarted -> {
                ensurePrintActive(
                    actions,
                    StartReason.STARTED_EVENT
                )
            }

            is BambuEvent.PrinterPaused -> {

                ensurePrintActive(
                    actions,
                    StartReason.STATE_RECONCILIATION
                )

                if (!paused) {
                    paused = true
                    actions +=
                        AutomationAction.PrintPaused
                }
            }

            is BambuEvent.PrinterResumed -> {

                ensurePrintActive(
                    actions,
                    StartReason.STATE_RECONCILIATION
                )

                paused = false

                actions +=
                    AutomationAction.PrintResumed
            }

            is BambuEvent.PrinterFinished -> {
                terminate(
                    actions,
                    failed = false
                )
            }

            is BambuEvent.PrinterFailed -> {
                terminate(
                    actions,
                    failed = true
                )
            }

            is BambuEvent.PrinterStatusChanged -> {
                ObsOverlayServer.update(event.snapshot)

                reconcileState(
                    event.snapshot.state,
                    actions
                )
            }

            else -> Unit
        }

        return actions
    }

    private fun reconcileState(
        state: PrinterState,
        actions: MutableList<AutomationAction>
    ) {

        when (state) {

            PrinterState.PREPARING -> {

                ensurePrintActive(
                    actions,
                    StartReason.STATE_RECONCILIATION
                )

                paused = false
            }

            PrinterState.PRINTING -> {

                ensurePrintActive(
                    actions,
                    StartReason.STATE_RECONCILIATION
                )

                if (paused) {
                    paused = false

                    actions +=
                        AutomationAction.PrintResumed
                }
            }

            PrinterState.PAUSED -> {

                ensurePrintActive(
                    actions,
                    StartReason.STATE_RECONCILIATION
                )

                if (!paused) {
                    paused = true

                    actions +=
                        AutomationAction.PrintPaused
                }
            }

            PrinterState.FINISHED -> {
                terminate(
                    actions,
                    failed = false
                )
            }

            PrinterState.FAILED -> {
                terminate(
                    actions,
                    failed = true
                )
            }

            else -> Unit
        }
    }

    private fun ensurePrintActive(
        actions: MutableList<AutomationAction>,
        reason: StartReason
    ) {

        if (printActive) {
            return
        }

        printActive = true
        paused = false

        actions +=
            AutomationAction.EnsurePrintAutomationStarted(
                reason
            )
    }

    private fun terminate(
        actions: MutableList<AutomationAction>,
        failed: Boolean
    ) {

        /*
         * Important:
         *
         * If the application starts while the printer is already
         * FINISHED, the first MQTT snapshot must NOT be interpreted
         * as a newly finished print.
         */
        if (!printActive) {
            return
        }

        printActive = false
        paused = false

        actions +=
            if (failed) {
                AutomationAction.PrintFailed
            } else {
                AutomationAction.PrintFinished
            }
    }
}

enum class StartReason {
    PREPARING_EVENT,
    STARTED_EVENT,
    STATE_RECONCILIATION
}

sealed interface AutomationAction {

    data class EnsurePrintAutomationStarted(
        val reason: StartReason
    ) : AutomationAction

    data object PrintPaused :
        AutomationAction

    data object PrintResumed :
        AutomationAction

    data object PrintFinished :
        AutomationAction

    data object PrintFailed :
        AutomationAction
}