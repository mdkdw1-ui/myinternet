package com.myinternet.share

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrHelper {
    // 표준 Wi-Fi QR: 카메라/구글 렌즈/삼성 Wi-Fi QR 스캔으로 바로 접속 가능
    fun wifi(ssid: String, pass: String, sec: String): Bitmap {
        fun esc(s: String) = s
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")
        return encode("WIFI:T:$sec;S:${esc(ssid)};P:${esc(pass)};;")
    }

    fun encode(text: String, size: Int = 600): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size) { if (m.get(it % size, it / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
