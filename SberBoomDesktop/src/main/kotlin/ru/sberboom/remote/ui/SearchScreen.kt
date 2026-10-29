package ru.sberboom.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.sberboom.desktop.compose.NetImage
import kotlinx.coroutines.delay
import ru.sberboom.remote.data.PlayerRepository
import ru.sberboom.remote.net.ZvukApi

/**
 * Поиск по каталогу Sber Звук и запуск контента на колонке.
 * Публичный API Звука анонимный — аккаунт не нужен.
 */
@Composable
fun SearchScreen(repo: PlayerRepository, onBack: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ZvukApi.SearchResult?>(null) }

    LaunchedEffect(submitted) {
        val q = submitted.trim()
        if (q.isEmpty()) {
            result = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { repo.zvuk.search(q) }
            .onSuccess { result = it }
            .onFailure { error = "Поиск недоступен: ${it.message ?: "нет сети"}" }
        loading = false
    }

    fun submit(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        if (value.contains("zvuk.com/") || value.startsWith("staros://")) {
            val ok = if (value.startsWith("staros://")) {
                repo.playDeeplink(value)
                true
            } else {
                repo.playZvukUrl(value)
            }
            if (!ok) error = "Не удалось разобрать ссылку"
            return
        }
        submitted = value
        keyboard?.hide()
    }

    // Drill-down: артист и альбом открываются отдельной страницей.
    var detail by remember { mutableStateOf<CatalogTarget?>(null) }
    detail?.let { target ->
        CatalogScreen(
            repo = repo,
            target = target,
            onBack = { detail = null },
            onOpen = { detail = it },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onBackground)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        "Трек, исполнитель или ссылка",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            submitted = ""
                            result = null
                            error = null
                        }) {
                            Icon(Icons.Rounded.Close, "Очистить", tint = Muted)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, keyboardType = KeyboardType.Text),
                keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SberGreen,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        when {
            loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SberGreen)
            }

            error != null -> Text(
                error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            result == null -> Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "Всё, что найдёте, заиграет на колонке.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Можно вставить ссылку вида zvuk.com/track/84279897 — или искать по названию.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                )
            }

            result?.isEmpty == true -> Text(
                "Ничего не найдено по запросу «$submitted»",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            else -> {
                val data = result ?: return@Column
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        bottom = 40.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    section("Исполнители", data.artists) { item ->
                        detail = CatalogTarget.Artist(item.id, item.title)
                    }
                    section("Альбомы", data.releases) { item ->
                        detail = CatalogTarget.Release(item.id, item.title)
                    }
                    section("Треки", data.tracks) { repo.play(it) }
                    section("Плейлисты", data.playlists) { repo.play(it) }
                }
            }
        }
    }

    // Небольшая задержка, чтобы не дёргать API на каждый символ — только по Enter.
    LaunchedEffect(query) {
        if (query.isEmpty() && submitted.isNotEmpty()) {
            delay(50)
            submitted = ""
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<ZvukApi.Item>,
    onPlay: (ZvukApi.Item) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Muted,
            modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp),
        )
    }
    items(items, key = { "$title-${it.id}" }) { item ->
        SearchRow(item) { onPlay(item) }
    }
}

@Composable
private fun SearchRow(item: ZvukApi.Item, onClick: () -> Unit) {
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
                Icon(Icons.Rounded.PlayArrow, null, tint = Muted)
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
            tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}
