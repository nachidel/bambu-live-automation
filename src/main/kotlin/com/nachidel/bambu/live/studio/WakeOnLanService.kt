package com.nachidel.bambu.live.studio

import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class WakeOnLanService(
    private val macAddress: String,
    private val broadcastAddress: String,
    private val port: Int = 7
) {

    private val logger =
        LoggerFactory.getLogger("WakeOnLan")

    fun wake() {

        val mac =
            parseMacAddress(
                macAddress
            )

        val packet =
            ByteArray(
                6 + 16 * mac.size
            )

        for (index in 0 until 6) {
            packet[index] =
                0xFF.toByte()
        }

        for (repeat in 0 until 16) {

            System.arraycopy(
                mac,
                0,
                packet,
                6 + repeat * mac.size,
                mac.size
            )
        }

        val address =
            InetAddress.getByName(
                broadcastAddress
            )

        DatagramSocket().use { socket ->

            socket.broadcast = true

            socket.send(
                DatagramPacket(
                    packet,
                    packet.size,
                    address,
                    port
                )
            )
        }

        logger.info(
            "Wake-on-LAN packet sent to {}:{}",
            broadcastAddress,
            port
        )
    }

    private fun parseMacAddress(
        value: String
    ): ByteArray {

        val normalized =
            value
                .trim()
                .replace(":", "")
                .replace("-", "")

        require(
            normalized.length == 12 &&
                    normalized.all {
                        it.isDigit() ||
                                it.lowercaseChar() in 'a'..'f'
                    }
        ) {
            "Invalid MAC address"
        }

        return ByteArray(6) { index ->

            normalized
                .substring(
                    index * 2,
                    index * 2 + 2
                )
                .toInt(16)
                .toByte()
        }
    }
}