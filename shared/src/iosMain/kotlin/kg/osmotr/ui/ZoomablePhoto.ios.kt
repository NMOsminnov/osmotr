package kg.osmotr.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.compose.AsyncImage
import kg.osmotr.core.File
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

@Composable
actual fun ZoomablePhoto(photo: File, modifier: Modifier) {
    val state = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 6f))
    AsyncImage(model = photo.path, contentDescription = photo.name, modifier = modifier.zoomable(state))
}
