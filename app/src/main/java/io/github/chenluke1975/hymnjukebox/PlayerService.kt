package io.github.chenluke1975.hymnjukebox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.Executors

/**
 * Streams the official preview MP3 straight from twgbr.org (nothing is downloaded or stored)
 * and keeps playing with the screen off. Notification has play/pause, next and stop.
 */
class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY_INDEX = "play_index"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"
        const val EXTRA_INDEX = "index"
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1

        fun send(ctx: Context, action: String, index: Int = -1) {
            val i = Intent(ctx, PlayerService::class.java).setAction(action).putExtra(EXTRA_INDEX, index)
            if (action == ACTION_PLAY_INDEX) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var mp: MediaPlayer? = null
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var catalog: Catalog
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var generation = 0
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        catalog = Catalog(this)
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        session = MediaSession(this, "HymnJukebox").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = toggle(forcePlay = true)
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = prev()
                override fun onStop() = stopAll()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always satisfy the foreground-service promise first.
        goForeground()
        when (intent?.action) {
            ACTION_PLAY_INDEX -> playIndex(intent.getIntExtra(EXTRA_INDEX, 0))
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_STOP -> stopAll()
            else -> if (mp == null && Player.status == Player.Status.IDLE) stopAll()
        }
        return START_NOT_STICKY
    }

    private fun playIndex(i: Int) {
        if (i !in Player.queue.indices) { stopAll(); return }
        Player.index = i
        val item = Player.queue[i]
        val gen = ++generation
        releasePlayer()
        val local = item.localUri
        if (local != null) {
            // The user's own file: played straight from the phone, no network.
            setStatus(Player.Status.LOADING, getString(R.string.status_loading_local))
            startStream(Uri.parse(local), gen, true)
            return
        }
        val album = item.album ?: run { stopAll(); return }
        setStatus(Player.Status.LOADING, getString(R.string.status_loading))
        io.execute {
            val detail = try { catalog.albumDetail(album) } catch (e: Exception) { null }
            val url = detail?.mp3
            android.os.Handler(mainLooper).post {
                if (gen != generation) return@post
                if (url == null || !Sites.isOfficial(url)) {
                    setStatus(Player.Status.ERROR, getString(if (detail == null) R.string.status_net_error else R.string.status_no_preview))
                    return@post
                }
                startStream(Uri.parse(url), gen, false)
            }
        }
    }

    private fun startStream(uri: Uri, gen: Int, local: Boolean) {
        val p = MediaPlayer()
        mp = p
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
        p.setOnPreparedListener {
            if (gen != generation) return@setOnPreparedListener
            if (requestFocus()) { it.start(); setStatus(Player.Status.PLAYING, if (local) getString(R.string.status_playing_local) else "") }
            else setStatus(Player.Status.PAUSED, "")
        }
        p.setOnCompletionListener { if (gen == generation) next(auto = true) }
        p.setOnErrorListener { _, _, _ ->
            if (gen == generation) setStatus(Player.Status.ERROR, getString(if (local) R.string.status_local_error else R.string.status_net_error))
            true
        }
        try {
            if (local) p.setDataSource(this, uri) else p.setDataSource(uri.toString())
            p.prepareAsync()
        } catch (e: Exception) {
            setStatus(Player.Status.ERROR, getString(if (local) R.string.status_local_error else R.string.status_net_error))
        }
    }

    private fun toggle(forcePlay: Boolean = false) {
        val p = mp
        when {
            p != null && Player.status == Player.Status.PLAYING && !forcePlay -> pause()
            p != null && Player.status == Player.Status.PAUSED -> {
                if (requestFocus()) { p.start(); setStatus(Player.Status.PLAYING, if (Player.current?.isLocal == true) getString(R.string.status_playing_local) else "") }
            }
            Player.status == Player.Status.ERROR || p == null -> if (Player.queue.isNotEmpty()) playIndex(maxOf(Player.index, 0))
        }
    }

    private fun pause() {
        val p = mp ?: return
        if (p.isPlaying) p.pause()
        setStatus(Player.Status.PAUSED, "")
    }

    private fun next(auto: Boolean = false) {
        val n = Player.index + 1
        if (n in Player.queue.indices) playIndex(n)
        else if (auto) { releasePlayer(); setStatus(Player.Status.IDLE, getString(R.string.status_done)); stopForegroundOnly() }
        else setStatus(Player.status, getString(R.string.status_last))
    }

    private fun prev() {
        val n = Player.index - 1
        if (n in Player.queue.indices) playIndex(n) else mp?.seekTo(0)
    }

    private fun stopAll() {
        generation++
        releasePlayer()
        abandonFocus()
        Player.status = Player.Status.IDLE
        Player.message = ""
        Player.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun stopForegroundOnly() {
        stopForeground(STOP_FOREGROUND_DETACH)
        foreground = false
    }

    private fun releasePlayer() {
        mp?.let { try { it.reset(); it.release() } catch (_: Exception) {} }
        mp = null
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> mp?.setVolume(1f, 1f)
        }
    }

    private fun requestFocus(): Boolean {
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener).build().also { focus = it }
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() { focus?.let { audio.abandonAudioFocusRequest(it) } }

    private fun setStatus(s: Player.Status, msg: String) {
        Player.status = s
        Player.message = msg
        Player.changed()
        updateSession()
        if (s == Player.Status.PLAYING || s == Player.Status.LOADING) goForeground()
        else if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun updateSession() {
        val title = Player.current?.title ?: getString(R.string.app_name)
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.owner_short)).build())
        val state = when (Player.status) {
            Player.Status.PLAYING -> PlaybackState.STATE_PLAYING
            Player.Status.LOADING -> PlaybackState.STATE_BUFFERING
            Player.Status.PAUSED -> PlaybackState.STATE_PAUSED
            Player.Status.ERROR -> PlaybackState.STATE_ERROR
            Player.Status.IDLE -> PlaybackState.STATE_STOPPED
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(state, mp?.let { try { it.currentPosition.toLong() } catch (_: Exception) { 0L } } ?: 0L, 1f)
            .build())
    }

    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val playing = Player.status == Player.Status.PLAYING
        val title = Player.current?.title ?: getString(R.string.app_name)
        val text = Player.message.ifEmpty { getString(R.string.streamed_from) }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                getString(R.string.prev), pending(ACTION_PREV, 1)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                getString(if (playing) R.string.pause else R.string.play), pending(ACTION_TOGGLE, 2)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next),
                getString(R.string.next), pending(ACTION_NEXT, 3)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(R.string.stop), pending(ACTION_STOP, 4)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
        foreground = true
    }

    override fun onDestroy() {
        generation++
        releasePlayer()
        abandonFocus()
        session.release()
        io.shutdownNow()
        super.onDestroy()
    }
}
