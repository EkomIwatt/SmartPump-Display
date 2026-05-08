// QR code renderer backed by zxing. Encodes once per payload via remember(); pads onto a
// white background so the kiosk's dark theme doesn't break scanner contrast.
package app.balancee.smartpump.display.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

@Composable
fun QrCodeView(
    payload: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 240,
) {
    val bitmap = remember(payload, sizeDp) { encode(payload, sizeDp) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Payment QR code",
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(8.dp),
    )
}

private fun encode(payload: String, sizeDp: Int): Bitmap {
    // Render at higher pixel density for crisp scanning under direct sunlight.
    val px = (sizeDp * 3).coerceAtLeast(256)
    return BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, px, px)
}
