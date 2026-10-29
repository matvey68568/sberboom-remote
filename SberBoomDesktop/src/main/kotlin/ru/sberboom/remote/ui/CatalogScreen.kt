package ru.sberboom.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.sberboom.desktop.compose.NetImage
import ru.sberboom.remote.data.PlayerRepository
import ru.sberboom.remote.net.ZvukApi

/** Куда ведёт drill-down каталога. */
sealed interface CatalogTarget {
    val id: String
    val title: String

    data class Artist(override val id: String, override val title: String) : CatalogTarget
    data class Release(override val id: String, override val title: String) : CatalogTarget
}

/**
 * Страница артиста (дискография + популярные треки) или альбома (треклист).
 * Порт drill-down из панели интеграции: getArtist / getRelease.
 */
@Composable
fun CatalogScreen(
    repo: PlayerRepository,
    target: CatalogTarget,
    onBack: () -> Unit,
    onOpen: (CatalogTarget) -> Unit,
) {
    var loading by remember(target) { mutableStateOf(true) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var artist by remember(target) { mutableStateOf<ZvukApi.ArtistDetail?>(null) }
    var release by remember(target) { mutableStateOf<ZvukApi.ReleaseDetail?>(null) }

    LaunchedEffect(target) {
        loading = true
        error = null
        runCatching {
            when (target) {
                is CatalogTarget.Artist -> artist = repo.zvuk.artist(target.id)
                is CatalogTarget.Release -> release = repo.zvuk.release(target.id)
            }
        }.onFailure { error = "Не удалось загрузить: ${it.message ?: "нет сети"}" }
        loading = false
    }

    val cover = artist?.coverUrl ?: release?.coverUrl
    val subtitle = when (target) {
        is CatalogTarget.Artist -> artist?.let {
            "${it.tracks.size} треков · ${it.releases.size} релизов"
        }

        is CatalogTarget.Release -> release?.let {
            listOfNotNull(it.artist.takeIf { a -> a.isNotBlank() }, it.year?.toString()).joinToString(" · ")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onBackground)
            }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface2),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    NetImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = Muted)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    target.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Muted)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Button(
                onClick = {
                    val pt = when (target) {
                        is CatalogTarget.Artist -> "artist"
                        is CatalogTarget.Release -> "release"
                    }
                    ZvukApi.deeplink(pt, target.id)?.let { repo.playDeeplink(it) }
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SberGreen, contentColor = Ink),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (target is CatalogTarget.Artist) "Играть артиста" else "Играть альбом",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        when {
            loading -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = SberGreen)
            }

            error != null -> Text(
                error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val popular = artist?.tracks.orEmpty()
                if (popular.isNotEmpty()) {
                    item(key = "h-popular") { SectionHeader("Популярные треки") }
                    items(popular, key = { "t-${it.id}" }) { item ->
                        CatalogRow(item) { repo.play(item) }
                    }
                }

                val releases = artist?.releases.orEmpty()
                if (releases.isNotEmpty()) {
                    item(key = "h-releases") { SectionHeader("Дискография") }
                    items(releases, key = { "r-${it.id}" }) { item ->
                        CatalogRow(item) {
                            onOpen(CatalogTarget.Release(item.id, item.title))
                        }
                    }
                }

                val tracklist = release?.tracks.orEmpty()
                if (tracklist.isNotEmpty()) {
                    item(key = "h-tracks") { SectionHeader("Треклист") }
                    items(tracklist, key = { "rt-${it.id}" }) { item ->
                        CatalogRow(item) { repo.play(item) }
                    }
                }

                if (popular.isEmpty() && releases.isEmpty() && tracklist.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Звук ничего не вернул по этому запросу.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = Muted,
        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun CatalogRow(item: ZvukApi.Item, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (item.coverUrl != null) {
                NetImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = Muted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotEmpty()) {
                Text(
                    item.subtitle + if (item.explicit) "  ·  18+" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Rounded.PlayArrow,
            "Включить",
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}
