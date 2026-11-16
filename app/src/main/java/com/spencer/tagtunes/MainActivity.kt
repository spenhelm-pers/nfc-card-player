package com.spencer.tagtunes

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.spencer.tagtunes.TagStore.Track

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    companion object {
        const val EXTRA_SHOW_PLAYER = "showPlayer"
    }

    private lateinit var store: TagStore
    private lateinit var volume: VolumeLimiter
    private var nfc: NfcAdapter? = null
    private lateinit var usbReader: Pn532UsbReader

    // Connection to PlaybackService
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()
    private var showPlayerWhenConnected = false

    // Home screen
    private lateinit var homeView: View
    private lateinit var status: TextView
    private lateinit var openPlayer: Button
    private lateinit var volumeCapButton: Button
    private lateinit var tagList: ListView
    private var listEntries: List<TagStore.Entry> = emptyList()

    // Player screen
    private lateinit var playerView: View
    private lateinit var shelf: View
    private lateinit var shelfView: ShelfView
    private lateinit var cover: View
    private lateinit var bookCover: View
    private lateinit var coverTitle: TextView
    private lateinit var coverArt: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackCount: TextView
    private lateinit var progress: LineProgress
    private lateinit var timeElapsed: TextView
    private lateinit var timeTotal: TextView
    private lateinit var bigPlay: ImageButton
    private lateinit var volBar: LineProgress
    private var artworkKey: Any? = null
    private var hasArt = false

    private var pendingPairUid: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    /** On the player screen, system Back is ignored so a kid can't wander into the tag list. */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            Toast.makeText(this@MainActivity, "Press and hold ⟵ Tags to go back", Toast.LENGTH_SHORT).show()
        }
    }

    private val controllerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updatePlayerUi()

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@MainActivity, "Couldn't play a song", Toast.LENGTH_SHORT).show()
        }
    }

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            volume.enforce()
            updateVolumeBar()
        }
    }

    // ---- Pickers ----

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val uid = pendingPairUid.also { pendingPairUid = null }
        if (uid == null || uris.isNullOrEmpty()) {
            setStatus("Pairing cancelled"); return@registerForActivityResult
        }
        uris.forEach { persist(it) }
        val files = uris.map { Track(it, displayName(it)) }.sortedWith(compareBy(natural) { it.name })
        val name = if (files.size == 1) files[0].name else "${files[0].name} + ${files.size - 1} more"
        if (store.nowPlaying == uid) stopPlayback()
        store.putFiles(uid, name, files)
        refreshList()
        showHome()
        setStatus("Paired: $name")
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val uid = pendingPairUid.also { pendingPairUid = null }
        if (uid == null || tree == null) {
            setStatus("Pairing cancelled"); return@registerForActivityResult
        }
        persist(tree)
        val folderDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = displayName(folderDoc)
        if (store.nowPlaying == uid) stopPlayback()
        store.putFolder(uid, name, tree)
        refreshList()
        showHome()
        val count = folderTracks(tree).size
        setStatus(if (count == 0) "No audio files in $name" else "Paired: $name ($count songs)")
    }

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = TagStore(this)
        volume = VolumeLimiter(this, store)
        nfc = NfcAdapter.getDefaultAdapter(this)
        usbReader = Pn532UsbReader(
            this,
            onTag = { uid -> onTagRead(uid) },
            onStateChanged = { if (homeView.visibility == View.VISIBLE) setStatus(homeHint()) },
            onError = { msg -> setStatus(msg) },
        )

        homeView = findViewById(R.id.homeView)
        status = findViewById(R.id.status)
        openPlayer = findViewById(R.id.openPlayer)
        volumeCapButton = findViewById(R.id.volumeCap)
        tagList = findViewById(R.id.tagList)

        playerView = findViewById(R.id.playerView)
        shelf = findViewById(R.id.shelf)
        shelfView = findViewById(R.id.shelfView)
        cover = findViewById(R.id.cover)
        bookCover = findViewById(R.id.bookCover)
        coverTitle = findViewById(R.id.coverTitle)
        coverArt = findViewById(R.id.coverArt)
        coverArt.clipToOutline = true
        trackTitle = findViewById(R.id.trackTitle)
        trackCount = findViewById(R.id.trackCount)
        progress = findViewById(R.id.progress)
        timeElapsed = findViewById(R.id.timeElapsed)
        timeTotal = findViewById(R.id.timeTotal)
        bigPlay = findViewById(R.id.bigPlay)
        volBar = findViewById(R.id.volBar)
        applyStyle()

        // Home screen
        openPlayer.setOnClickListener { showPlayer() }
        volumeCapButton.setOnClickListener {
            Toast.makeText(this, "Press and hold to change the volume limit", Toast.LENGTH_SHORT).show()
        }
        volumeCapButton.setOnLongClickListener { chooseVolumeCap(); true }
        findViewById<Button>(R.id.pin).setOnClickListener {
            try {
                startLockTask()
            } catch (e: Exception) {
                Toast.makeText(this, "Turn on App pinning in Settings > Security first", Toast.LENGTH_LONG).show()
            }
        }
        status.setOnClickListener {
            if (nfc != null && nfc?.isEnabled == false) startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }
        tagList.setOnItemClickListener { _, _, pos, _ -> editTag(listEntries[pos]) }

        // Player screen
        val back = findViewById<View>(R.id.back)
        back.setOnClickListener {
            Toast.makeText(this, "Press and hold to go back", Toast.LENGTH_SHORT).show()
        }
        back.setOnLongClickListener { showHome(); true }
        bigPlay.setOnClickListener {
            withController { c ->
                if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) c.pause() else play(c)
            }
        }
        findViewById<ImageButton>(R.id.prev).setOnClickListener {
            // Restarts the song if more than 3 s in, otherwise goes back one.
            withController { it.seekToPrevious() }
        }
        findViewById<ImageButton>(R.id.next).setOnClickListener {
            withController { c ->
                if (c.hasNextMediaItem()) c.seekToNextMediaItem()
                else Toast.makeText(this, "That's the last song", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<ImageButton>(R.id.volDown).setOnClickListener { volume.step(-1); updateVolumeBar() }
        findViewById<ImageButton>(R.id.volUp).setOnClickListener { volume.step(+1); updateVolumeBar() }
        shelf.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) shelf.post { sizeCover() }
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)

        showPlayerWhenConnected = intent?.getBooleanExtra(EXTRA_SHOW_PLAYER, false) == true
        updateVolumeCapButton()
        refreshList()
        showHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_PLAYER, false)) {
            if (controller != null) showPlayerIfLoaded() else showPlayerWhenConnected = true
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            val c = try { future.get() } catch (e: Exception) { return@addListener }
            controller = c
            c.addListener(controllerListener)
            pendingActions.toList().forEach { it(c) }
            pendingActions.clear()
            if (showPlayerWhenConnected) {
                showPlayerWhenConnected = false
                showPlayerIfLoaded()
            }
            updatePlayerUi()
            if (homeView.visibility == View.VISIBLE) showHome()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        volume.enforce()
        updateVolumeBar()
        handler.post(ticker)
        usbReader.start()
        val adapter = nfc
        if (adapter != null && adapter.isEnabled) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
            adapter.enableReaderMode(this, this, flags, Bundle())
        } else if (!usbReader.connected) {
            setStatus(homeHint())
        }
    }

    /** What to tell the user on the home screen, depending on which tag reader is available. */
    private fun homeHint(): String = when {
        usbReader.connected -> "Place a tag on the reader"
        nfc?.isEnabled == true -> "Place a tag on the back of the phone"
        nfc != null -> "NFC is off. Tap here to turn it on."
        else -> "No built-in NFC. Plug in the USB NFC reader."
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        nfc?.disableReaderMode(this)
        usbReader.stop()
    }

    /** Music keeps playing in PlaybackService; the screen just lets go of its remote control. */
    override fun onStop() {
        super.onStop()
        controller?.removeListener(controllerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(volumeObserver)
    }

    /** Run now if connected to the player, otherwise as soon as the connection is up. */
    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pendingActions += action
    }

    // ---- Screens ----

    private fun showHome() {
        playerView.visibility = View.GONE
        homeView.visibility = View.VISIBLE
        backCallback.isEnabled = false
        val loaded = (controller?.mediaItemCount ?: 0) > 0
        val name = store.nowPlaying?.let { store.get(it)?.name }
        if (loaded && name != null) {
            openPlayer.visibility = View.VISIBLE
            openPlayer.text = "Continue  ·  $name"
        } else {
            openPlayer.visibility = View.GONE
        }
        setStatus(homeHint())
    }

    private fun showPlayer() {
        homeView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        backCallback.isEnabled = true
        updatePlayerUi()
        updateVolumeBar()
    }

    private fun showPlayerIfLoaded() {
        if ((controller?.mediaItemCount ?: 0) > 0) showPlayer()
    }

    // ---- NFC ----

    /** Called on a binder thread whenever a tag comes into range. */
    override fun onTagDiscovered(tag: Tag) {
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        runOnUiThread { onTagRead(uid) }
    }

    private fun onTagRead(uid: String) {
        if (pendingPairUid != null) return
        if (store.get(uid) == null) {
            askHowToPair(uid, "New tag! What should it play?")
        } else {
            openTag(uid)
        }
    }

    private fun askHowToPair(uid: String, title: String) {
        showHome()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("Pick songs", "Pick a folder (plays every song in it)")) { _, which ->
                pendingPairUid = uid
                if (which == 0) pickFiles.launch(arrayOf("audio/*")) else pickFolder.launch(null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Playback ----

    private fun openTag(uid: String) {
        val entry = store.get(uid) ?: return
        withController { c ->
            if (store.nowPlaying == uid && c.mediaItemCount > 0) {
                play(c)
                showPlayer()
                return@withController
            }
            val list = if (entry.folder != null) folderTracks(entry.folder) else entry.files
            val coverUri = entry.folder?.let { folderCover(it) }
            if (list.isEmpty()) {
                showHome()
                setStatus("Nothing to play for ${entry.name}. Tap it in the list to choose again.")
                return@withController
            }
            saveCurrentProgress(c)
            var index = store.track(uid)
            var pos = store.position(uid)
            if (index !in list.indices) { index = 0; pos = 0 }
            store.nowPlaying = uid
            c.setMediaItems(list.map { mediaItem(it, entry.name, coverUri) }, index, pos.toLong())
            c.prepare()
            c.play()
            showPlayer()
        }
    }

    private fun play(c: MediaController) {
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    private fun mediaItem(track: Track, playlistName: String, coverUri: Uri?): MediaItem =
        MediaItem.Builder()
            .setUri(track.uri)
            .setMediaId(track.uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(playlistName)
                    .setAlbumTitle(playlistName)
                    .setArtworkUri(coverUri)
                    .build()
            )
            .build()

    /** Remember where the outgoing tag was before loading a different one. */
    private fun saveCurrentProgress(c: MediaController) {
        val old = store.nowPlaying ?: return
        if (c.mediaItemCount > 0 && c.playbackState != Player.STATE_ENDED) {
            store.setProgress(old, c.currentMediaItemIndex, c.currentPosition.toInt())
        }
    }

    private fun stopPlayback() {
        store.nowPlaying = null
        withController { c ->
            c.stop()
            c.clearMediaItems()
        }
    }

    private fun updatePlayerUi() {
        val c = controller ?: return
        val playing = c.playWhenReady && c.playbackState != Player.STATE_ENDED
        bigPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        bigPlay.contentDescription = if (playing) "Pause" else "Play"
        coverTitle.text = c.mediaMetadata.albumTitle ?: ""
        trackTitle.text = c.mediaMetadata.title ?: ""
        trackCount.text =
            if (c.mediaItemCount > 1) "TRACK ${c.currentMediaItemIndex + 1} OF ${c.mediaItemCount}" else ""
        updateArtwork(c)
        updateProgress()
    }

    private fun updateProgress() {
        val c = controller
        val dur = c?.duration ?: C.TIME_UNSET
        if (c != null && dur != C.TIME_UNSET && dur > 0) {
            val pos = c.currentPosition.coerceIn(0, dur)
            progress.fraction = pos.toFloat() / dur
            timeElapsed.text = clock(pos)
            timeTotal.text = clock(dur)
        } else {
            progress.fraction = 0f
            timeElapsed.text = ""
            timeTotal.text = ""
        }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---- Look ----

    private fun font(id: Int): Typeface? = try { ResourcesCompat.getFont(this, id) } catch (e: Exception) { null }

    private fun applyStyle() {
        val regular = font(R.font.lora_regular)
        val medium = font(R.font.lora_medium)
        val semibold = font(R.font.lora_semibold)
        val bold = font(R.font.lora_bold)
        findViewById<TextView>(R.id.homeTitle).typeface = semibold
        status.typeface = regular
        openPlayer.typeface = medium
        volumeCapButton.typeface = medium
        findViewById<Button>(R.id.pin).typeface = medium
        findViewById<TextView>(R.id.shelfLabel).typeface = medium
        findViewById<TextView>(R.id.backLabel).typeface = medium
        coverTitle.typeface = bold
        trackTitle.typeface = semibold
        trackCount.typeface = medium
        timeElapsed.typeface = regular
        timeTotal.typeface = regular
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            coverTitle, 14, 30, 1, TypedValue.COMPLEX_UNIT_SP
        )

        progress.trackColor = getColor(R.color.track)
        progress.fillColor = getColor(R.color.brass)
        progress.thumbColor = getColor(R.color.brass_light)
        volBar.trackColor = getColor(R.color.track)
        volBar.fillColor = getColor(R.color.muted)
        volBar.thumbColor = 0

        val play = getDrawable(R.drawable.ic_play)?.mutate()
        play?.setTint(getColor(R.color.brass))
        val size = (20 * resources.displayMetrics.density).toInt()
        play?.setBounds(0, 0, size, size)
        openPlayer.setCompoundDrawablesRelative(play, null, null, null)
        openPlayer.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
    }

    /** Fit the book to the shelf: a portrait book normally, a square when showing cover art. */
    private fun sizeCover() {
        val dp = resources.displayMetrics.density
        val avail = shelf.height - shelfView.bookBaseOffset - 20 * dp
        if (avail <= 0) return
        var h = minOf(292 * dp, avail).coerceAtLeast(120 * dp)
        var w = if (hasArt) h else h * 220f / 292f
        val maxW = shelf.width - 56 * dp
        if (w > maxW) { h *= maxW / w; w = maxW }
        val lp = cover.layoutParams
        if (lp.width != w.toInt() || lp.height != h.toInt()) {
            lp.width = w.toInt()
            lp.height = h.toInt()
            cover.layoutParams = lp
        }
        shelfView.coverHeight = h
    }

    /** Show the folder's cover image or the song's embedded art; otherwise the cloth book. */
    private fun updateArtwork(c: MediaController) {
        val md = c.mediaMetadata
        val uri = md.artworkUri
        val data = md.artworkData
        val key: Any? = uri ?: data?.let { it.size to it.contentHashCode() }
        if (key == artworkKey) return
        artworkKey = key
        if (key == null) { showArtwork(null); return }
        Thread {
            val fromUri: Bitmap? = try {
                val bytes = if (uri != null) contentResolver.openInputStream(uri)?.use { it.readBytes() } else data
                bytes?.let { decodeSampled(it, 900) }
            } catch (e: Exception) { null }
            val bmp: Bitmap? = fromUri ?: try { data?.let { decodeSampled(it, 900) } } catch (e: Exception) { null }
            runOnUiThread { if (artworkKey == key) showArtwork(bmp) }
        }.start()
    }

    private fun showArtwork(bmp: Bitmap?) {
        hasArt = bmp != null
        if (bmp != null) {
            coverArt.setImageBitmap(bmp)
            coverArt.visibility = View.VISIBLE
            bookCover.visibility = View.INVISIBLE
        } else {
            coverArt.setImageDrawable(null)
            coverArt.visibility = View.GONE
            bookCover.visibility = View.VISIBLE
        }
        sizeCover()
    }

    private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- Folders ----

    /** A cover image in the folder (cover.jpg, folder.png, front.jpg ...), if there is one. */
    private fun folderCover(tree: Uri): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val wanted = listOf("cover", "folder", "front", "albumart")
        var best: Pair<Int, String>? = null
        try {
            contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1)?.lowercase() ?: continue
                    val ext = name.substringAfterLast('.', "")
                    if (ext !in setOf("jpg", "jpeg", "png", "webp")) continue
                    val rank = wanted.indexOf(name.substringBeforeLast('.'))
                    if (rank >= 0 && (best == null || rank < best!!.first)) best = rank to id
                }
            }
        } catch (e: Exception) { return null }
        return best?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.second) }
    }

    // Fallback when a provider reports a generic MIME type (e.g. application/octet-stream).
    private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "amr", "awb", "3ga")

    /** Every audio file directly inside the folder, in natural name order (2 before 10). */
    private fun folderTracks(tree: Uri): List<Track> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val out = mutableListOf<Track>()
        try {
            contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (name.startsWith(".")) continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (mime.startsWith("audio/") || ext in audioExtensions) {
                        out += Track(DocumentsContract.buildDocumentUriUsingTree(tree, id), name.substringBeforeLast('.'))
                    }
                }
            }
        } catch (e: Exception) {
            // Permission lost or folder gone; caller shows "nothing to play".
        }
        return out.sortedWith(compareBy(natural) { it.name })
    }

    private val natural = Comparator<String> { a, b ->
        val chunk = Regex("\\d+|\\D+")
        val ca = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val cb = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]; val y = cb[i]
            val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0'); val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length.compareTo(ny.length) else nx.compareTo(ny)
            } else x.compareTo(y)
            if (cmp != 0) return@Comparator cmp
        }
        ca.size.compareTo(cb.size)
    }

    // ---- Volume ----

    private fun updateVolumeBar() {
        volBar.fraction = volume.current.toFloat() / volume.max.coerceAtLeast(1)
    }

    /** Stop the phone's volume-up button at the limit instead of letting it overshoot. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && volume.atLimit()) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun chooseVolumeCap() {
        val options = intArrayOf(30, 40, 50, 60, 70, 80, 100)
        val labels = options.map { if (it == 100) "No limit" else "$it%" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Maximum volume")
            .setSingleChoiceItems(labels, options.indexOf(store.volumeCap).coerceAtLeast(0)) { d, which ->
                store.volumeCap = options[which]
                updateVolumeCapButton()
                volume.enforce()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateVolumeCapButton() {
        volumeCapButton.text =
            if (store.volumeCap >= 100) "Volume limit: off" else "Volume limit: ${store.volumeCap}%"
    }

    // ---- Tag list ----

    private fun refreshList() {
        listEntries = store.all()
        val nameFont = font(R.font.lora_medium)
        val detailFont = font(R.font.lora_regular)
        tagList.adapter = object : ArrayAdapter<TagStore.Entry>(this, R.layout.item_tag, listEntries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_tag, parent, false)
                val e = listEntries[position]
                val kind = when {
                    e.folder != null -> "Folder"
                    e.files.size == 1 -> "1 track"
                    else -> "${e.files.size} tracks"
                }
                row.findViewById<TextView>(R.id.name).apply { text = e.name; typeface = nameFont }
                row.findViewById<TextView>(R.id.detail).apply { text = "$kind · tag ${e.uid}"; typeface = detailFont }
                return row
            }
        }
    }

    private fun editTag(entry: TagStore.Entry) {
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(arrayOf("Play", "Rename", "Choose different songs or folder", "Forget this tag")) { _, which ->
                when (which) {
                    0 -> openTag(entry.uid)
                    1 -> renameTag(entry)
                    2 -> askHowToPair(entry.uid, "What should ${entry.name} play?")
                    3 -> {
                        if (store.nowPlaying == entry.uid) stopPlayback()
                        store.remove(entry.uid)
                        refreshList()
                        showHome()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameTag(entry: TagStore.Entry) {
        val input = EditText(this).apply { setText(entry.name); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    store.rename(entry.uid, name)
                    refreshList()
                    showHome()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Helpers ----

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { /* some providers don't offer persistable grants */ }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name.substringBeforeLast('.')
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Audio"
    }
}
