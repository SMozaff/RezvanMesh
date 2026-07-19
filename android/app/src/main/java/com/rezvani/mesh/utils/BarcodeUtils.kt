// android/app/src/main/java/com/rezvani/mesh/utils/BarcodeUtils.kt

package com.rezvani.mesh.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

object BarcodeUtils {

    fun generateQrCodeBitmap(text: String, size: Int = 512): Bitmap? {
        if (text.isEmpty()) return null
        val writer = QRCodeWriter()
        return try {
            val bitMatrix: BitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: WriterException) {
            null
        }
    }
}