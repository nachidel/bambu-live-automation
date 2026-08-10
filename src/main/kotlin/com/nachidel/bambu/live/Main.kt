package com.nachidel.bambu.live

import com.nachidel.bambu.event.BambuEvent
import com.nachidel.bambu.live.automation.AutomationAction
import com.nachidel.bambu.live.automation.PrintAutomationController
import com.nachidel.bambu.live.bambu.BambuPrinterService
import com.nachidel.bambu.live.simulator.BambuEventSimulator
import com.nachidel.bambu.live.studio.WakeOnLanService
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger =
    LoggerFactory.getLogger("Application")

private val bambuLogger =
    LoggerFactory.getLogger("Bambu")

private val automationLogger =
    LoggerFactory.getLogger("Automation")

fun main() = runBlocking {

    val controller =
        PrintAutomationController()

    /*
     * Wake-on-LAN configuration
     *
     * It is created before handleEvent() because the event handler
     * needs access to it.
     */
    val wolEnabled =
        System.getenv("WOL_ENABLED")
            ?.trim()
            ?.toBooleanStrictOrNull()
            ?: false

    val wakeOnLan =
        if (wolEnabled) {

            WakeOnLanService(
                macAddress =
                    System.getenv("STUDIO_PC_MAC")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: error(
                            "STUDIO_PC_MAC missing"
                        ),

                broadcastAddress =
                    System.getenv("WOL_BROADCAST")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: error(
                            "WOL_BROADCAST missing"
                        ),

                port =
                    System.getenv("WOL_PORT")
                        ?.toIntOrNull()
                        ?: 9
            )

        } else {

            null
        }

    /*
     * Common event handler.
     *
     * Real Bambu events and simulated events both go through
     * exactly the same automation logic.
     */
    suspend fun handleEvent(
        event: BambuEvent
    ) {

        /*
         * Full Bambu events can contain a lot of information.
         * Keep them at DEBUG level.
         */
        bambuLogger.debug(
            "{}",
            event
        )

        controller
            .handle(event)
            .forEach { action ->

                when (action) {

                    is AutomationAction
                    .EnsurePrintAutomationStarted -> {

                        automationLogger.info(
                            "Print automation start requested ({})",
                            action.reason
                        )

                        if (wakeOnLan != null) {

                            automationLogger.info(
                                "Waking studio PC"
                            )

                            wakeOnLan.wake()

                        } else {

                            automationLogger.warn(
                                "Wake-on-LAN disabled"
                            )
                        }
                    }

                    AutomationAction.PrintPaused -> {

                        automationLogger.warn(
                            "Print paused"
                        )
                    }

                    AutomationAction.PrintResumed -> {

                        automationLogger.info(
                            "Print resumed"
                        )
                    }

                    AutomationAction.PrintFinished -> {

                        automationLogger.info(
                            "Print finished"
                        )
                    }

                    AutomationAction.PrintFailed -> {

                        automationLogger.error(
                            "Print failed"
                        )
                    }
                }
            }
    }

    /*
     * Simulation mode
     *
     * No Bambu Cloud connection is made.
     *
     * Wake-on-LAN can still be enabled independently,
     * allowing us to test the real studio PC from the simulator.
     */
    val simulation =
        System.getenv("BAMBU_SIMULATION")
            ?.trim()
            ?.toBooleanStrictOrNull()
            ?: false

    if (simulation) {

        logger.warn(
            "Running in SIMULATION mode"
        )

        if (wolEnabled) {

            logger.warn(
                "Wake-on-LAN is ENABLED during simulation"
            )

        } else {

            logger.info(
                "Wake-on-LAN is disabled during simulation"
            )
        }

        BambuEventSimulator()
            .run(
                ::handleEvent
            )

        return@runBlocking
    }

    /*
     * Real Bambu Cloud mode
     */
    val token =
        System.getenv("BAMBU_TOKEN")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error(
                "BAMBU_TOKEN missing"
            )

    val bambu =
        BambuPrinterService(
            token = token,
            scope = this
        )

    try {

        logger.info(
            "Connecting to Bambu Cloud..."
        )

        bambu.connect(
            ::handleEvent
        )

        logger.info(
            "Bambu Cloud connected. Waiting for events..."
        )

        awaitCancellation()

    } finally {

        logger.info(
            "Disconnecting from Bambu Cloud..."
        )

        bambu.disconnect()
        bambu.close()
    }
}