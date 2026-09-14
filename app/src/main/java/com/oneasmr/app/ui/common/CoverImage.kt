package com.oneasmr.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.oneasmr.app.data.local.AgeRating
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.CoverType

/**
 * Coil cover renderer (plan Task 10 "Coil 集成"): local cached file first,
 * bundled-work-folder cover second, placeholder (surface color) when neither
 * exists. The model comes from [CoverStore.coverModelFor] — local-first,
 * missing → placeholder — and resolution is cached in composition per key so
 * list scrolling does not re-query the store every frame.
 *
 * Uses the default Coil singleton loader, which OneAsmrApp wires to the
 * Hilt-provided ImageLoader (CoverModule).
 *
 * Safe-mode hook: with NSFW off (see [LocalNsfwEnabled]) and a sensitive
 * [ageRating] (null/R15/R18) the cover renders as [CensoredCoverPlaceholder]
 * instead — no decode, no blur. The decision comes from [safeModeHidesCover]
 * and nowhere else, so every caller that passes the work row's real rating
 * (list card, search row, mini player) masks the cover at exactly the same
 * threshold.
 *
 * @param coverStore Task 10's cover cache; pass via remember { } from the
 *   ViewModel/Hilt in the calling screen (Task 12).
 * @param rootFolderUri + relativeDir locate the work folder for the bundled
 *   cover fallback; pass null (e.g. remote works) to skip the fallback.
 * @param ageRating the work row's manual rating (null = 未标记, treated as
 *   sensitive under safe mode); pass it through rather than pre-computing a
 *   hidden/shown boolean at the call site.
 */
@Composable
fun CoverImage(
    coverStore: CoverStore,
    rjCode: String,
    type: CoverType,
    rootFolderUri: String?,
    relativeDir: String?,
    ageRating: AgeRating? = null,
    modifier: Modifier = Modifier,
) {
    if (safeModeHidesCover(LocalNsfwEnabled.current, ageRating)) {
        CensoredCoverPlaceholder(modifier)
        return
    }
    var model by remember(rjCode, type, rootFolderUri, relativeDir) { mutableStateOf<Any?>(null) }
    LaunchedEffect(rjCode, type, rootFolderUri, relativeDir) {
        model = coverStore.coverModelFor(rjCode, type, rootFolderUri, relativeDir)
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
    )
}
