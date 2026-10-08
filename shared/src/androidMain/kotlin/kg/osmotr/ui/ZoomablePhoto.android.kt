package kg.osmotr.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kg.osmotr.core.File
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/** Приближение подгружает полное разрешение только видимой области: шильдик читается при сильном зуме. */
@Composable
actual fun ZoomablePhoto(photo: File, modifier: Modifier) {
    ZoomableAsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current).data(java.io.File(photo.path)).build(),
        contentDescription = photo.name, modifier = modifier,
        state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 12f))),
    )
}
