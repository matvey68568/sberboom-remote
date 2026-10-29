package ru.sberboom.remote.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.sberboom.remote.MainActivity
import ru.sberboom.remote.R
import ru.sberboom.remote.data.PlayerRepository
import ru.sberboom.remote.data.PlayerUiState

/**
 * Медиа-уведомление и экран блокировки: транспорт и обложка текущего трека.
 *
 * Это локальный пульт, а не плеер: аудио идёт в колонке, поэтому уведомление
 * только отправляет команды в [PlayerRepository].
 */
object MediaNotification {

    const val CHANNEL_ID = "sboom_playback"
    const val NOTIFICATION_ID = 4242

    const val ACTION_PLAY_PAUSE = "ru.sberboom.remote.PLAY_PAUSE"
    const val ACTION_NEXT = "ru.sberboom.remote.NEXT"
    const val ACTION_PREV = "ru.sberboom.remote.PREV"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSessionCompat? = null
    private var job: Job? = null
    private var coverUrl: String? = null
    private var cover: Bitmap? = null

    private val http by lazy { OkHttpClient() }

    /** Подключает уведомление к состоянию репозитория. Вызывается один раз из SberApp. */
    fun attach(context: Context, repo: PlayerRepository) {
        val app = context.applicationContext
        createChannel(app)

        val mediaSession = MediaSessionCompat(app, "SberBoomRemote").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = repo.playPause()
                override fun onPause() = repo.playPause()
                override fun onSkipToNext() = repo.next()
                override fun onSkipToPrevious() = repo.prev()
            })
            isActive = true
        }
        session = mediaSession

        job?.cancel()
        job = scope.launch {
            repo.state.collect { state -> render(app, state, mediaSession) }
        }
    }

    fun release() {
        job?.cancel()
        job = null
        session?.isActive = false
        session?.release()
        session = null
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Управление колонкой",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Текущий трек, пауза и переключение — как в плеере"
                setShowBadge(false)
            },
        )
    }

    private suspend fun render(context: Context, state: PlayerUiState, mediaSession: MediaSessionCompat) {
        val track = state.track
        if (!state.isConnected || track?.title == null) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f)
                    .build(),
            )
            coverUrl = null
            cover = null
            return
        }

        if (state.coverUrl != coverUrl) {
            coverUrl = state.coverUrl
            cover = state.coverUrl?.let { loadCover(it) }
        }

        val playing = track.playing
        val title = track.title ?: "SberBoom"
        val artist = track.artistLine.ifBlank { track.stationName ?: "SberBoom" }

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (track.durationSec ?: 0) * 1000L)
                .apply { cover?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    track.livePositionSec() * 1000L,
                    track.speed ?: 1f,
                )
                .build(),
        )

        val notification = build(context, mediaSession, state, title, artist, playing)
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun build(
        context: Context,
        mediaSession: MediaSessionCompat,
        state: PlayerUiState,
        title: String,
        artist: String,
        playing: Boolean,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, MediaActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_boom)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openIntent)
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .addAction(
                R.drawable.ic_prev,
                "Предыдущий",
                action(ACTION_PREV, 1),
            )
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Пауза" else "Играть",
                action(ACTION_PLAY_PAUSE, 2),
            )
            .addAction(
                R.drawable.ic_next,
                "Следующий",
                action(ACTION_NEXT, 3),
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )

        cover?.let { builder.setLargeIcon(it) }
        state.volume.takeIf { state.muted }?.let { builder.setSubText("без звука") }

        return builder.build()
    }

    private suspend fun loadCover(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }
}

/** Кнопки уведомления и гарнитуры → команды репозитория. */
class MediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = (context.applicationContext as? ru.sberboom.remote.SberApp)?.repo ?: return
        when (intent.action) {
            MediaNotification.ACTION_PLAY_PAUSE -> repo.playPause()
            MediaNotification.ACTION_NEXT -> repo.next()
            MediaNotification.ACTION_PREV -> repo.prev()
        }
    }
}
