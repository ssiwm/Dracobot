package eu.draconest.hermesbots.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Dekoder i podglad data URL obrazu (base64 PNG/JPEG) — bez Coil/Glide.
 * Dekodowanie w tle (produceState), kruciak podczas pracy.
 */
@Composable
fun DataUrlImage(
    dataUrl: String,
    modifier: Modifier = Modifier,
    contentDesc: String = ""
) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, dataUrl) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val b64 = dataUrl.substringAfter("base64,", "")
                if (b64.isEmpty()) null
                else BitmapFactory.decodeByteArray(
                    Base64.decode(b64, Base64.DEFAULT), 0,
                    Base64.decode(b64, Base64.DEFAULT).size
                )
            } catch (_: Exception) { null }
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDesc,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.fillMaxSize(0.25f))
            }
        }
    }
}
