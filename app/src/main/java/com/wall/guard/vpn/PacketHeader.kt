package com.wall.guard.vpn

import java.net.InetAddress
import java.nio.ByteBuffer

data class IpHeader(
    val version: Int,
    val ihl: Int,
    val totalLength: Int,
    val protocol: Int,
    val sourceIp: InetAddress,
    val destIp: InetAddress
) {
    val headerLength: Int get() = ihl

    fun payloadLength(): Int = totalLength - headerLength

    companion object {
        fun parse(buffer: ByteBuffer): IpHeader? {
            val start = buffer.position()
            if (buffer.remaining() < 20) return null

            val firstByte = buffer.get(buffer.position()).toInt() and 0xFF
            val version = (firstByte shr 4) and 0x0F

            if (version == 4) {
                val ihlBytes = (firstByte and 0x0F) * 4
                if (buffer.remaining() < ihlBytes) return null

                val totalLength = buffer.getShort(start + 2).toInt() and 0xFFFF
                val protocol = buffer.get(start + 9).toInt() and 0xFF

                val srcBytes = ByteArray(4)
                buffer.position(start + 12)
                buffer.get(srcBytes)
                val sourceIp = InetAddress.getByAddress(srcBytes)

                val dstBytes = ByteArray(4)
                buffer.get(dstBytes)
                val destIp = InetAddress.getByAddress(dstBytes)

                buffer.position(start)

                return IpHeader(
                    version = version,
                    ihl = ihlBytes,
                    totalLength = totalLength,
                    protocol = protocol,
                    sourceIp = sourceIp,
                    destIp = destIp
                )
            }

            if (version == 6) {
                if (buffer.remaining() < 40) return null

                val ipv6HeaderLength = 40
                val payloadLength = buffer.getShort(start + 4).toInt() and 0xFFFF
                val protocol = buffer.get(start + 6).toInt() and 0xFF

                val srcBytes = ByteArray(16)
                buffer.position(start + 8)
                buffer.get(srcBytes)
                val sourceIp = InetAddress.getByAddress(srcBytes)

                val dstBytes = ByteArray(16)
                buffer.get(dstBytes)
                val destIp = InetAddress.getByAddress(dstBytes)

                buffer.position(start)

                return IpHeader(
                    version = version,
                    ihl = ipv6HeaderLength,
                    totalLength = payloadLength + ipv6HeaderLength,
                    protocol = protocol,
                    sourceIp = sourceIp,
                    destIp = destIp
                )
            }

            return null
        }
    }
}

data class TcpHeader(
    val sourcePort: Int,
    val destPort: Int,
    val sequenceNumber: Long,
    val acknowledgmentNumber: Long,
    val flags: Int,
    val dataOffset: Int
) {
    val headerLength: Int get() = dataOffset * 4
    val isSYN: Boolean get() = (flags and 0x02) != 0
    val isACK: Boolean get() = (flags and 0x10) != 0
    val isRST: Boolean get() = (flags and 0x04) != 0
    val isFIN: Boolean get() = (flags and 0x01) != 0

    companion object {
        fun parse(buffer: ByteBuffer, headerOffset: Int): TcpHeader? {
            if (buffer.remaining() < headerOffset + 20) return null

            val sourcePort = buffer.getShort(headerOffset).toInt() and 0xFFFF
            val destPort = buffer.getShort(headerOffset + 2).toInt() and 0xFFFF
            val seq = buffer.getInt(headerOffset + 4).toLong() and 0xFFFFFFFFL
            val ack = buffer.getInt(headerOffset + 8).toLong() and 0xFFFFFFFFL
            val dataOffset = (buffer.get(headerOffset + 12).toInt() shr 4) and 0x0F
            val flags = buffer.get(headerOffset + 13).toInt() and 0x3F

            return TcpHeader(
                sourcePort = sourcePort,
                destPort = destPort,
                sequenceNumber = seq,
                acknowledgmentNumber = ack,
                flags = flags,
                dataOffset = dataOffset
            )
        }
    }
}

data class UdpHeader(
    val sourcePort: Int,
    val destPort: Int,
    val length: Int
) {
    companion object {
        fun parse(buffer: ByteBuffer, headerOffset: Int): UdpHeader? {
            if (buffer.remaining() < headerOffset + 8) return null

            val sourcePort = buffer.getShort(headerOffset).toInt() and 0xFFFF
            val destPort = buffer.getShort(headerOffset + 2).toInt() and 0xFFFF
            val length = buffer.getShort(headerOffset + 4).toInt() and 0xFFFF

            return UdpHeader(
                sourcePort = sourcePort,
                destPort = destPort,
                length = length
            )
        }
    }
}

data class ParsedPacket(
    val ipHeader: IpHeader,
    val tcpHeader: TcpHeader? = null,
    val udpHeader: UdpHeader? = null
) {
    val protocol: Int get() = ipHeader.protocol
    val destPort: Int
        get() = tcpHeader?.destPort ?: udpHeader?.destPort ?: 0
    val sourcePort: Int
        get() = tcpHeader?.sourcePort ?: udpHeader?.sourcePort ?: 0
    val isTCP: Boolean get() = tcpHeader != null
    val isUDP: Boolean get() = udpHeader != null
    val isSYN: Boolean get() = tcpHeader?.isSYN == true
    val isRST: Boolean get() = tcpHeader?.isRST == true
    val isFIN: Boolean get() = tcpHeader?.isFIN == true
    val destIpString: String get() = ipHeader.destIp.hostAddress ?: "?"
    val sourceIpString: String get() = ipHeader.sourceIp.hostAddress ?: "?"

    companion object {
        fun parse(buffer: ByteBuffer): ParsedPacket? {
            val ip = IpHeader.parse(buffer) ?: return null

            return when (ip.protocol) {
                6 -> {
                    val tcp = TcpHeader.parse(buffer, ip.headerLength)
                    ParsedPacket(ipHeader = ip, tcpHeader = tcp)
                }
                17 -> {
                    val udp = UdpHeader.parse(buffer, ip.headerLength)
                    ParsedPacket(ipHeader = ip, udpHeader = udp)
                }
                else -> ParsedPacket(ipHeader = ip)
            }
        }
    }
}
