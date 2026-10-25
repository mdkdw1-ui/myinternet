package com.myinternet.share

import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object Socks5Test {
    fun tcp(host: String, port: Int): String {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 5000)
            s.soTimeout = 8000
            val o = s.getOutputStream()
            val i = DataInputStream(s.getInputStream())
            o.write(byteArrayOf(5, 1, 0)); o.flush()
            val r = ByteArray(2); i.readFully(r)
            if (r[0].toInt() != 5 || r[1].toInt() != 0) return "핸드셰이크 실패"
            o.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 0, 80)); o.flush()
            val rep = ByteArray(10); i.readFully(rep)
            if (rep[1].toInt() != 0) return "CONNECT 실패 코드 ${rep[1]}"
            o.write("HEAD / HTTP/1.0\r\nHost: 1.1.1.1\r\n\r\n".toByteArray()); o.flush()
            val line = BufferedReader(InputStreamReader(i)).readLine()
            s.close()
            "OK ($line)"
        } catch (e: Exception) {
            "실패: ${e.javaClass.simpleName} ${e.message}"
        }
    }

    fun udp(host: String, port: Int): String {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 5000)
            s.soTimeout = 5000
            val o = s.getOutputStream()
            val i = DataInputStream(s.getInputStream())
            o.write(byteArrayOf(5, 1, 0)); o.flush()
            val r = ByteArray(2); i.readFully(r)
            o.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
            val rep = ByteArray(10); i.readFully(rep)
            if (rep[1].toInt() != 0) return "ASSOCIATE 실패 코드 ${rep[1]}"
            val up = ((rep[8].toInt() and 255) shl 8) or (rep[9].toInt() and 255)
            val dns = byteArrayOf(
                0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
                7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
                'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1
            )
            val hdr = byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 0, 53)
            val pkt = hdr + dns
            val ds = DatagramSocket()
            ds.soTimeout = 4000
            ds.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(host), up))
            val buf = ByteArray(2048)
            val rp = DatagramPacket(buf, buf.size)
            ds.receive(rp)
            ds.close()
            s.close()
            "OK (응답 ${rp.length}바이트)"
        } catch (e: Exception) {
            "실패: ${e.javaClass.simpleName} ${e.message}"
        }
    }
}
