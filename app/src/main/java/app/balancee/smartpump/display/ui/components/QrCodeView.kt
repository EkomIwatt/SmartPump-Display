// QR code renderer. Spec calls for inverted styling: black backdrop, light modules,
// 24dp inner padding. The QR matrix comes from ZXing's MultiFormatWriter; rendered
// once into a Bitmap and re-keyed by content + size + colours.
package app.balancee.smartpump.display.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

@Composable
fun QrCodeView(
    content: String,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 220.dp,
    moduleColor: Color = Color.White,
    panelColor: Color = Color.Black,
) {
    val pixelSize = with(androidx.compose.ui.platform.LocalDensity.current) {
        sizeDp.toPx().toInt().coerceAtLeast(MIN_PIXEL_SIZE)
    }
    val bitmap = remember(content, pixelSize, moduleColor, panelColor) {
        renderQrBitmap(
            content = content,
            sizePx = pixelSize,
            onArgb = moduleColor.toArgb(),
            offArgb = panelColor.toArgb(),
        )
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dimensions.cornerCard))
            .background(panelColor)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code",
            modifier = Modifier.size(sizeDp),
        )
    }
}

private const val MIN_PIXEL_SIZE = 128

private fun renderQrBitmap(
    content: String,
    sizePx: Int,
    onArgb: Int,
    offArgb: Int,
): Bitmap {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            pixels[row + x] = if (matrix.get(x, y)) onArgb else offArgb
        }
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 360, heightDp = 360)
@Composable
private fun QrCodeViewPreview() {
    SmartPumpDisplayTheme {
        Box(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            QrCodeView(content = "nip://balancee/BLC-00847?amount=5000")
        }
    }
}
