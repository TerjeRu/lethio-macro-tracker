package com.lethio.macros.ui.scanner

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import zxingcpp.BarcodeReader

/**
 * Barcode lookup is an exact string match, so the decoder must return the digit strings the
 * catalogue stores: 13 digits for EAN-13, 12 for UPC-A, 8 for EAN-8 and 8 (not expanded) for UPC-E.
 * The symbols are drawn from the GS1 encoding tables. On the device because the decoder is native;
 * clean synthetic images prove format handling, not camera robustness.
 */
@RunWith(AndroidJUnit4::class)
class BarcodeDecodeTest {

    private val reader = BarcodeReader(retailBarcodeOptions())

    @Test
    fun ean13DecodesToItsThirteenDigits() =
        assertDecodes(ean13("4006381333931"), "4006381333931", BarcodeReader.Format.EAN_13)

    @Test
    fun upcADecodesToTwelveDigitsNotAPaddedEan13() =
        assertDecodes(ean13("0036000291452"), "036000291452", BarcodeReader.Format.UPC_A)

    @Test
    fun ean8DecodesToItsEightDigits() =
        assertDecodes(ean8("96385074"), "96385074", BarcodeReader.Format.EAN_8)

    @Test
    fun upcEDecodesToItsEightDigitsUnexpanded() =
        assertDecodes(upcE("01234565"), "01234565", BarcodeReader.Format.UPC_E)

    @Test
    fun aRotatedPackStillReads() {
        val upright = ean13("4006381333931")
        val sideways = Bitmap.createBitmap(upright.height, upright.width, Bitmap.Config.ARGB_8888)
        for (x in 0 until upright.width) for (y in 0 until upright.height) {
            sideways.setPixel(upright.height - 1 - y, x, upright.getPixel(x, y))
        }
        assertDecodes(sideways, "4006381333931", BarcodeReader.Format.EAN_13)
    }

    @Test
    fun aQrCodeIsNotAcceptedEvenIfPresent() {
        // Not drawn here: the point is the option set. QR stays out: a QR code can carry any text,
        // not just a product code.
        assertThat(retailBarcodeOptions().formats)
            .containsExactly(
                BarcodeReader.Format.UPC_A,
                BarcodeReader.Format.UPC_E,
                BarcodeReader.Format.EAN_13,
                BarcodeReader.Format.EAN_8,
            )
    }

    private fun assertDecodes(image: Bitmap, text: String, format: BarcodeReader.Format) {
        val results = reader.read(image).filter { it.error == null }
        assertThat(results.map { it.text }).containsExactly(text)
        assertThat(results.single().format).isEqualTo(format)
    }

    // --- Drawing: modules from the GS1 General Specifications' EAN/UPC tables. ---

    private val l =
        listOf(
            "0001101",
            "0011001",
            "0010011",
            "0111101",
            "0100011",
            "0110001",
            "0101111",
            "0111011",
            "0110111",
            "0001011",
        )
    private val g =
        listOf(
            "0100111",
            "0110011",
            "0011011",
            "0100001",
            "0011101",
            "0111001",
            "0000101",
            "0010001",
            "0001001",
            "0010111",
        )
    private val r = l.map { code -> code.map { if (it == '0') '1' else '0' }.joinToString("") }
    private val ean13Parity =
        listOf(
            "LLLLLL",
            "LLGLGG",
            "LLGGLG",
            "LLGGGL",
            "LGLLGG",
            "LGGLLG",
            "LGGGLL",
            "LGLGLG",
            "LGLGGL",
            "LGGLGL",
        )
    private val upcEParity =
        listOf(
            "GGGLLL",
            "GGLGLL",
            "GGLLGL",
            "GGLLLG",
            "GLGGLL",
            "GLLGGL",
            "GLLLGG",
            "GLGLGL",
            "GLGLLG",
            "GLLGLG",
        )

    private fun d(c: Char) = c - '0'

    private fun ean13(digits: String): Bitmap {
        val parity = ean13Parity[d(digits[0])]
        val left =
            (1..6).joinToString("") { i ->
                if (parity[i - 1] == 'L') l[d(digits[i])] else g[d(digits[i])]
            }
        val right = (7..12).joinToString("") { i -> r[d(digits[i])] }
        return draw("101$left" + "01010" + "$right" + "101")
    }

    private fun ean8(digits: String): Bitmap {
        val left = (0..3).joinToString("") { l[d(digits[it])] }
        val right = (4..7).joinToString("") { r[d(digits[it])] }
        return draw("101$left" + "01010" + "$right" + "101")
    }

    /** Number system 0 only; [digits] is the 8-digit form, its last digit selecting the parity. */
    private fun upcE(digits: String): Bitmap {
        val parity = upcEParity[d(digits[7])]
        val body =
            (1..6).joinToString("") { i ->
                if (parity[i - 1] == 'L') l[d(digits[i])] else g[d(digits[i])]
            }
        return draw("101$body" + "010101")
    }

    private fun draw(modules: String, module: Int = 4, quiet: Int = 12, height: Int = 240): Bitmap {
        val width = (modules.length + 2 * quiet) * module
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        modules.forEachIndexed { i, m ->
            if (m == '1')
                for (x in 0 until module) for (y in 0 until height) {
                    bitmap.setPixel((quiet + i) * module + x, y, Color.BLACK)
                }
        }
        return bitmap
    }
}
