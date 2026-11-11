package io.github.chenluke1975.hymnjukebox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val REQ_TREE = 7
    }

    private lateinit var ui: Ui
    private lateinit var catalog: Catalog
    private lateinit var favorites: Favorites
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var content: FrameLayout
    private val tabButtons = mutableListOf<Button>()
    private var tab = 0

    // Number pad state
    private var book = Book.HYMNS
    private var digits = ""
    private lateinit var numberView: TextView
    private val bookButtons = mutableMapOf<Book, Button>()

    // Albums
    private var albums: List<Album> = emptyList()
    private var albumError = false

    // Player bar
    private lateinit var nowTitle: TextView
    private lateinit var nowStatus: TextView
    private lateinit var playButton: Button
    private val playerListener: () -> Unit = { refreshPlayer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        catalog = Catalog(this)
        favorites = Favorites(this)

        val root = ui.column()
        // Header
        val header = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ui.text(getString(R.string.app_name), 28f, bold = true).apply {
            setPadding(ui.dp(8), ui.dp(6), 0, ui.dp(6))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ui.button(getString(R.string.about), primary = false, sp = 18f, height = 52) { showAbout() },
            LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, ui.dp(4), ui.dp(8), ui.dp(4)) })
        root.addView(header)

        // Tabs
        val tabs = ui.row()
        listOf(R.string.tab_number, R.string.tab_local, R.string.tab_albums, R.string.tab_search, R.string.tab_favorites).forEachIndexed { i, r ->
            val b = ui.button(getString(r), primary = false, sp = 18f, height = 58) { showTab(i) }
            tabButtons += b
            tabs.addView(b, ui.weightLp())
        }
        root.addView(tabs)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildPlayerBar())
        root.addView(ui.text(getString(R.string.owner_note), 13f, color = R.color.muted).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(6))
        })
        setContentView(root)

        showTab(savedInstanceState?.getInt("tab") ?: 0)
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (!prefs.getBoolean("about_seen", false)) {
            prefs.edit().putBoolean("about_seen", true).apply()
            showAbout()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onStart() {
        super.onStart()
        Player.addListener(playerListener)
        refreshPlayer()
        if (tab == 4 || tab == 1) showTab(tab)
    }

    override fun onStop() {
        Player.removeListener(playerListener)
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun showTab(i: Int) {
        tab = i
        tabButtons.forEachIndexed { idx, b ->
            b.setBackgroundResource(if (idx == i) R.drawable.bg_primary else R.drawable.bg_secondary)
            b.setTextColor(getColor(if (idx == i) R.color.on_primary else R.color.ink))
        }
        content.removeAllViews()
        content.addView(when (i) {
            0 -> buildNumberPad()
            1 -> buildLocal()
            2 -> buildAlbums()
            3 -> buildSearch()
            else -> buildFavorites()
        })
    }

    private fun scroll(child: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(child)
    }

    // ------------------------------------------------------------------ 點歌 (number pad)

    private fun buildNumberPad(): View {
        val col = ui.column().apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) }
        val books = ui.row()
        bookButtons.clear()
        Book.entries.forEach { b ->
            val btn = ui.button(b.zh, primary = false, sp = 18f, height = 58) { book = b; refreshBook() }
            bookButtons[b] = btn
            books.addView(btn, ui.weightLp())
        }
        col.addView(books)
        numberView = ui.text("", 44f, bold = true).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_card)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        }
        col.addView(numberView, ui.fullLp())

        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("清除", "0", "⌫"))
        keys.forEach { r ->
            val row = ui.row()
            r.forEach { k ->
                val primary = k.length == 1 && k[0].isDigit()
                row.addView(ui.button(if (k == "清除") getString(R.string.clear) else k, primary = false,
                    sp = if (primary) 32f else 22f, height = 72) { onKey(k) }, ui.weightLp())
            }
            col.addView(row)
        }
        val actions = ui.row()
        actions.addView(ui.button(getString(R.string.open_hymn), primary = true, sp = 22f, height = 76) { openTyped() }, ui.weightLp(2f))
        actions.addView(ui.button(getString(R.string.fav_add), primary = false, sp = 20f, height = 76) {
            val n = digits.toIntOrNull() ?: return@button toast(getString(R.string.type_number))
            val on = favorites.toggle(Favorite.hymn(book, n))
            toast(getString(if (on) R.string.fav_added else R.string.fav_removed))
        }, ui.weightLp(1f))
        col.addView(actions)
        col.addView(ui.text(getString(R.string.number_hint), 16f, color = R.color.muted).apply {
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), 0)
        })
        refreshBook()
        return scroll(col)
    }

    private fun refreshBook() {
        bookButtons.forEach { (b, btn) ->
            btn.setBackgroundResource(if (b == book) R.drawable.bg_accent else R.drawable.bg_secondary)
        }
        numberView.text = if (digits.isEmpty()) getString(R.string.number_placeholder, book.zh)
        else getString(R.string.hymn_title, book.zh, digits.toInt())
    }

    private fun onKey(k: String) {
        digits = when (k) {
            "清除" -> ""
            "⌫" -> digits.dropLast(1)
            else -> if (digits.length >= 4) digits else (digits + k).trimStart('0')
        }
        refreshBook()
    }

    private fun openTyped() {
        val n = digits.toIntOrNull() ?: return toast(getString(R.string.type_number))
        playOrOpen(book, n)
    }

    /** Plays the phone's own file for this hymn if there is one (and the setting is on), else opens 影音美地. */
    private fun playOrOpen(b: Book, n: Int) {
        val lib = LocalLibrary.get(this)
        val song = if (lib.preferLocal) lib.find(b, n) else null
        if (song != null) {
            History.add(this, b, n)
            playLocal(song, lib.playable().filter { it.book == song.book })
            toast(getString(R.string.playing_local_toast, song.label(this)))
        } else {
            if (lib.preferLocal) toast(getString(R.string.no_local_fallback, b.zh, n))
            openHymn(b, n)
        }
    }

    /** Replaces the queue with [list] (in that order) and starts at [song]. */
    private fun playLocal(song: LocalSong, list: List<LocalSong>) {
        askNotificationPermission()
        val l = if (list.any { it.uri == song.uri }) list else listOf(song)
        Player.queue.clear()
        l.forEach { Player.queue.add(Player.Item(it.label(this), localUri = it.uri)) }
        PlayerService.send(this, PlayerService.ACTION_PLAY_INDEX, l.indexOfFirst { it.uri == song.uri })
    }

    private fun openHymn(b: Book, n: Int) {
        startActivity(Intent(this, HymnPageActivity::class.java)
            .putExtra(HymnPageActivity.EXTRA_BOOK, b.name).putExtra(HymnPageActivity.EXTRA_NUMBER, n))
    }


    // ------------------------------------------------------------------ 手機 (the user's own audio files)

    private var localFilter = ""
    private var scanning = false

    private fun buildLocal(): View {
        val lib = LocalLibrary.get(this)
        val list = ListView(this).apply {
            divider = null
            setPadding(0, 0, 0, ui.dp(8))
            clipToPadding = false
        }
        val header = ui.column().apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        header.addView(ui.text(getString(R.string.local_intro), 16f, color = R.color.muted).apply {
            setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(4))
        })
        header.addView(ui.button(getString(R.string.pick_folder), primary = true, sp = 22f, height = 72) { pickFolder() }, ui.fullLp())
        val status = ui.text("", 17f).apply { setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(2)) }
        header.addView(status)

        val folders = lib.folders()
        folders.forEach { f ->
            val row = ui.row()
            row.addView(ui.text("📁 " + lib.folderName(f), 17f).apply { gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(ui.dp(8), 0, 0, 0) })
            row.addView(ui.button(getString(R.string.remove_folder), primary = false, sp = 16f, height = 52) {
                AlertDialog.Builder(this).setMessage(getString(R.string.remove_folder_q, lib.folderName(f)))
                    .setPositiveButton(R.string.remove) { _, _ -> lib.removeFolder(f); showTab(1) }
                    .setNegativeButton(R.string.cancel, null).show()
            }, LinearLayout.LayoutParams(-2, -2))
            header.addView(row)
        }

        val r1 = ui.row()
        r1.addView(ui.button(getString(R.string.rescan), primary = false, sp = 19f, height = 64) { rescan(status) }, ui.weightLp())
        val prefer = ui.button("", primary = false, sp = 17f, height = 64) {}
        fun paintPrefer() { prefer.text = getString(if (lib.preferLocal) R.string.prefer_local_on else R.string.prefer_local_off) }
        paintPrefer()
        prefer.setOnClickListener { lib.preferLocal = !lib.preferLocal; paintPrefer() }
        r1.addView(prefer, ui.weightLp(1.4f))
        header.addView(r1)
        val r2 = ui.row()
        r2.addView(ui.button(getString(R.string.play_all), primary = true, sp = 20f, height = 64) {
            val all = lib.playable()
            if (all.isEmpty()) toast(getString(R.string.local_empty)) else playLocal(all.first(), all)
        }, ui.weightLp())
        r2.addView(ui.button(getString(R.string.shuffle), primary = true, sp = 20f, height = 64) {
            val all = lib.load().shuffled()
            if (all.isEmpty()) toast(getString(R.string.local_empty)) else playLocal(all.first(), all)
        }, ui.weightLp())
        header.addView(r2)
        val filter = bigEdit(getString(R.string.local_filter_hint)).apply { setText(localFilter) }
        header.addView(filter, ui.fullLp())
        list.addHeaderView(header, null, false)

        val adapter = LocalAdapter()
        list.adapter = adapter
        fun refill() {
            val songs = lib.load()
            val q = localFilter.trim()
            val shown = songs.filter {
                q.isEmpty() || it.title.contains(q, true) || it.fileName.contains(q, true) || it.number?.toString() == q
            }
            val missing = shown.filter { it.number == null }
            val items = mutableListOf<Any>()
            if (missing.isNotEmpty()) { items += getString(R.string.no_number_section, missing.size); items.addAll(missing) }
            val numbered = shown.filter { it.number != null }
            if (numbered.isNotEmpty()) { items += getString(R.string.numbered_section, numbered.size); items.addAll(numbered) }
            adapter.items = items
            adapter.notifyDataSetChanged()
            status.text = when {
                scanning -> getString(R.string.scanning)
                folders.isEmpty() -> getString(R.string.no_folders)
                songs.isEmpty() -> getString(R.string.local_empty)
                else -> getString(R.string.local_count, songs.size, songs.count { it.number != null })
            }
        }
        localRefill = { refill() }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { localFilter = s.toString(); refill() }
        })
        status.text = getString(R.string.loading)
        io.execute { lib.load(); runOnUiThread { if (tab == 1) refill() } }
        return list
    }

    private var localRefill: (() -> Unit)? = null

    private inner class LocalAdapter : BaseAdapter() {
        var items: List<Any> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is String) 0 else 1
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            if (item is String) {
                val tv = (convertView as? TextView) ?: ui.text("", 21f, bold = true).apply {
                    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(4))
                }
                tv.text = item
                return tv
            }
            val song = item as LocalSong
            val holder = (convertView?.tag as? LocalHolder) ?: LocalHolder()
            holder.bind(song)
            return holder.root
        }
    }

    private inner class LocalHolder {
        val card = ui.column().apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
        }
        val root = FrameLayout(this@MainActivity).apply {
            setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
            addView(card)
            tag = this@LocalHolder
        }
        val label = ui.text("", 20f, bold = true)
        val file = ui.text("", 14f, color = R.color.muted)
        val play = ui.button(getString(R.string.play), primary = true, sp = 19f, height = 56) {}
        val star = ui.button("☆", primary = false, sp = 24f, height = 56) {}
        val edit = ui.button(getString(R.string.edit), primary = false, sp = 19f, height = 56) {}

        init {
            card.addView(label)
            card.addView(file)
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(play, ui.weightLp(1.3f))
            row.addView(star, ui.weightLp(0.7f))
            row.addView(edit, ui.weightLp(1f))
            card.addView(row)
        }

        fun bind(song: LocalSong) {
            val lib = LocalLibrary.get(this@MainActivity)
            val text = song.label(this@MainActivity)
            label.text = text
            file.text = song.path + if (song.manual) "　" + getString(R.string.manual_mark) else ""
            play.setOnClickListener { playLocal(song, if (song.number != null) lib.playable().filter { it.book == song.book } else listOf(song)) }
            val fav = Favorite.local(song, text)
            star.text = if (favorites.contains(fav)) "★" else "☆"
            star.setOnClickListener { star.text = if (favorites.toggle(fav)) "★" else "☆" }
            edit.setOnClickListener { editSong(song) }
        }
    }

    private fun editSong(song: LocalSong) {
        val lib = LocalLibrary.get(this)
        var chosen = song.book
        val col = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
        col.addView(ui.text(song.path, 15f, color = R.color.muted))
        col.addView(ui.text(getString(R.string.edit_book), 18f, bold = true).apply { setPadding(0, ui.dp(8), 0, 0) })
        val bookButtons = mutableMapOf<Book, Button>()
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun paint() = bookButtons.forEach { (b, btn) ->
            btn.setBackgroundResource(if (b == chosen) R.drawable.bg_accent else R.drawable.bg_secondary)
        }
        Book.entries.forEachIndexed { i, b ->
            val btn = ui.button(b.zh, primary = false, sp = 18f, height = 56) { chosen = b; paint() }
            bookButtons[b] = btn
            (if (i < 2) r1 else r2).addView(btn, ui.weightLp())
        }
        col.addView(r1); col.addView(r2); paint()
        col.addView(ui.text(getString(R.string.edit_number), 18f, bold = true).apply { setPadding(0, ui.dp(8), 0, 0) })
        val num = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(song.number?.toString() ?: "")
            hint = getString(R.string.edit_number_hint)
        }
        col.addView(num)
        col.addView(ui.text(getString(R.string.edit_title), 18f, bold = true).apply { setPadding(0, ui.dp(8), 0, 0) })
        val title = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setSingleLine()
            setText(song.title)
        }
        col.addView(title)
        val b = AlertDialog.Builder(this).setTitle(R.string.edit_song).setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton(R.string.save) { _, _ ->
                val n = num.text.toString().trim().toIntOrNull()?.takeIf { it in 1..9999 }
                lib.setManual(song, chosen, n, title.text.toString())
                localRefill?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
        if (song.manual) b.setNeutralButton(R.string.reset_auto) { _, _ -> lib.clearManual(song); localRefill?.invoke() }
        b.show()
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_TREE)
        } catch (e: Exception) {
            toast(getString(R.string.no_folder_picker))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            LocalLibrary.get(this).addFolder(uri)
        } catch (e: Exception) {
            toast(getString(R.string.folder_failed)); return
        }
        showTab(1)
        rescanNow()
    }

    private fun rescan(status: TextView) { status.text = getString(R.string.scanning); rescanNow() }

    private fun rescanNow() {
        if (scanning) return
        scanning = true
        localRefill?.invoke()
        io.execute {
            val (count, errors) = runCatching { LocalLibrary.get(this).scan { n ->
                runOnUiThread { if (tab == 1) toastShort(getString(R.string.scan_progress, n)) }
            } }.getOrDefault(0 to listOf("?"))
            runOnUiThread {
                scanning = false
                if (tab == 1) showTab(1)
                toast(getString(R.string.scan_done, count) +
                    if (errors.isNotEmpty()) "\n" + getString(R.string.scan_errors, errors.joinToString("、")) else "")
            }
        }
    }

    private var lastShortToast: Toast? = null
    private fun toastShort(s: String) {
        lastShortToast?.cancel()
        lastShortToast = Toast.makeText(this, s, Toast.LENGTH_SHORT).also { it.show() }
    }

    // ------------------------------------------------------------------ 試聽 (official album previews)

    private fun buildAlbums(): View {
        val col = ui.column().apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) }
        col.addView(ui.text(getString(R.string.albums_intro), 16f, color = R.color.muted).apply {
            setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(4))
        })
        val filter = bigEdit(getString(R.string.filter_hint))
        col.addView(filter, ui.fullLp())
        val list = ui.column()
        col.addView(list)
        val refresh = ui.button(getString(R.string.reload), primary = false, sp = 18f, height = 56) { loadAlbums(list, filter, true) }
        col.addView(refresh, ui.fullLp())
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fillAlbums(list, s.toString()) }
        })
        loadAlbums(list, filter, false)
        return scroll(col)
    }

    private fun loadAlbums(list: LinearLayout, filter: EditText, refresh: Boolean) {
        if (albums.isNotEmpty() && !refresh) { fillAlbums(list, filter.text.toString()); return }
        list.removeAllViews()
        list.addView(ui.text(getString(R.string.loading), 20f).apply { setPadding(ui.dp(14), ui.dp(10), 0, ui.dp(10)) })
        io.execute {
            val result = runCatching { catalog.albums(refresh) }
            runOnUiThread {
                albums = result.getOrDefault(albums)
                albumError = result.isFailure
                fillAlbums(list, filter.text.toString())
            }
        }
    }

    private fun fillAlbums(list: LinearLayout, q: String) {
        list.removeAllViews()
        if (albums.isEmpty()) {
            list.addView(ui.text(getString(if (albumError) R.string.net_error else R.string.loading), 19f).apply {
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            })
            return
        }
        val shown = albums.filter { q.isBlank() || it.title.contains(q.trim(), ignoreCase = true) }
        if (shown.isEmpty()) list.addView(ui.text(getString(R.string.no_results), 19f).apply { setPadding(ui.dp(14), ui.dp(10), 0, 0) })
        shown.forEach { list.addView(albumCard(it)) }
    }

    private fun albumCard(a: Album): View {
        val card = ui.column().apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
        }
        card.addView(ui.text(a.title, 20f, bold = true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ui.button(getString(R.string.play), primary = true, sp = 19f, height = 56) { playNow(a) }, ui.weightLp())
        row.addView(ui.button(getString(R.string.enqueue), primary = false, sp = 19f, height = 56) { enqueue(a) }, ui.weightLp())
        row.addView(ui.button(getString(R.string.tracks), primary = false, sp = 19f, height = 56) { showTracks(a) }, ui.weightLp())
        val fav = Favorite.album(a)
        val star = ui.button(if (favorites.contains(fav)) "★" else "☆", primary = false, sp = 24f, height = 56) {}
        star.setOnClickListener { star.text = if (favorites.toggle(fav)) "★" else "☆" }
        row.addView(star, ui.weightLp(0.6f))
        card.addView(row)
        return FrameLayout(this).apply {
            setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
            addView(card)
        }
    }

    private fun showTracks(a: Album) {
        val wait = AlertDialog.Builder(this).setTitle(a.title).setMessage(R.string.loading).show()
        io.execute {
            val d = runCatching { catalog.albumDetail(a) }.getOrNull()
            runOnUiThread {
                wait.dismiss()
                if (isFinishing) return@runOnUiThread
                if (d == null) { toast(getString(R.string.net_error)); return@runOnUiThread }
                val items = d.tracks.ifEmpty { listOf(getString(R.string.no_tracks)) }
                AlertDialog.Builder(this)
                    .setTitle(a.title + "\n" + getString(if (d.mp3 != null) R.string.has_preview else R.string.no_preview))
                    .setItems(items.map { t -> if (Parsers.hymnRef(t) != null) "🔢 $t" else t }.toTypedArray()) { _, which ->
                        val ref = d.tracks.getOrNull(which)?.let { Parsers.hymnRef(it) }
                        if (ref != null) playOrOpen(ref.book, ref.number) else playNow(a)
                    }
                    .setPositiveButton(R.string.play) { _, _ -> playNow(a) }
                    .setNegativeButton(R.string.close, null)
                    .setNeutralButton(R.string.open_store_page) { _, _ -> openUrl(a.url) }
                    .show()
            }
        }
    }

    private fun playNow(a: Album) {
        askNotificationPermission()
        val at = if (Player.index < 0) 0 else Player.index + 1
        Player.queue.add(minOf(at, Player.queue.size), Player.Item.of(a))
        PlayerService.send(this, PlayerService.ACTION_PLAY_INDEX, minOf(at, Player.queue.size - 1))
    }

    private fun enqueue(a: Album) {
        Player.queue.add(Player.Item.of(a))
        Player.changed()
        toast(getString(R.string.enqueued, Player.queue.size))
    }

    // ------------------------------------------------------------------ 搜尋 (title search)

    private fun buildSearch(): View {
        val col = ui.column().apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) }
        val edit = bigEdit(getString(R.string.search_hint))
        col.addView(edit, ui.fullLp())
        val results = ui.column()
        val go = ui.button(getString(R.string.search), primary = true, sp = 22f, height = 68) { runSearch(edit.text.toString(), results) }
        col.addView(go, ui.fullLp())
        edit.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { runSearch(edit.text.toString(), results); true } else false
        }
        col.addView(ui.text(getString(R.string.search_intro), 16f, color = R.color.muted).apply {
            setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4))
        })
        col.addView(ui.button(getString(R.string.build_index), primary = false, sp = 18f, height = 56) { buildIndex(results) }, ui.fullLp())
        col.addView(results)
        return scroll(col)
    }

    private fun bigEdit(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setBackgroundResource(R.drawable.bg_card)
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        minHeight = ui.dp(64)
    }

    private fun runSearch(q0: String, results: LinearLayout) {
        val q = q0.trim()
        results.removeAllViews()
        if (q.isEmpty()) return
        results.addView(ui.text(getString(R.string.loading), 19f).apply { setPadding(ui.dp(14), ui.dp(8), 0, 0) })
        io.execute {
            val hits = mutableListOf<Triple<String, String, () -> Unit>>() // label, sub, action
            // 0. The phone's own files.
            val lib = LocalLibrary.get(this)
            for (song in lib.load()) {
                if (song.title.contains(q, ignoreCase = true) || song.fileName.contains(q, ignoreCase = true)) {
                    hits += Triple(song.label(this), getString(R.string.src_local) + "：" + song.fileName) {
                        playLocal(song, lib.playable().filter { it.book == song.book })
                    }
                }
            }
            // 1. Numbered hymn titles already read from the official 影音美地 pages.
            val vols = catalog.volumes()
            for (v in vols) {
                val titles = runCatching { cachedTitles(v) }.getOrNull() ?: continue
                titles.forEach { (n, t) ->
                    if (t.contains(q, ignoreCase = true)) {
                        hits += Triple(getString(R.string.hymn_title, v.book.zh, n) + "  " + t, getString(R.string.src_goodland)) { playOrOpen(v.book, n) }
                    }
                }
            }
            // 2. Album titles and their track lists (official store).
            val al = albums.ifEmpty { runCatching { catalog.albums() }.getOrDefault(emptyList()) }
            for (a in al) {
                if (a.title.contains(q, ignoreCase = true)) hits += Triple(a.title, getString(R.string.src_album)) { playNow(a) }
                catalog.cachedAlbumDetail(a)?.tracks?.forEach { t ->
                    if (t.contains(q, ignoreCase = true)) {
                        val ref = Parsers.hymnRef(t)
                        hits += Triple(t, a.title) { if (ref != null) playOrOpen(ref.book, ref.number) else playNow(a) }
                    }
                }
            }
            runOnUiThread {
                if (albums.isEmpty()) albums = al
                results.removeAllViews()
                if (hits.isEmpty()) results.addView(ui.text(getString(R.string.no_results_index), 18f).apply {
                    setPadding(ui.dp(14), ui.dp(8), ui.dp(14), 0)
                })
                hits.take(200).forEach { (label, sub, action) ->
                    val b = ui.button("$label\n$sub", primary = false, sp = 18f, height = 64) { action() }
                    b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    b.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                    results.addView(b, ui.fullLp())
                }
            }
        }
    }

    /** Titles for a volume only if already cached (search must not trigger 30 page loads). */
    private fun cachedTitles(v: Volume): Map<Int, String>? =
        if (java.io.File(cacheDir, "catalog/vol-${v.courseId}.json").isFile) catalog.unitTitles(v) else null

    private fun buildIndex(results: LinearLayout) {
        results.removeAllViews()
        val status = ui.text(getString(R.string.loading), 19f).apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), 0) }
        results.addView(status)
        io.execute {
            val vols = catalog.volumes()
            val al = runCatching { catalog.albums() }.getOrDefault(emptyList())
            val total = vols.size + al.size
            var done = 0
            var failed = 0
            for (v in vols) {
                if (runCatching { catalog.unitTitles(v) }.isFailure) failed++
                done++
                runOnUiThread { status.text = getString(R.string.index_progress, done, total) }
            }
            for (a in al) {
                if (runCatching { catalog.albumDetail(a) }.isFailure) failed++
                done++
                runOnUiThread { status.text = getString(R.string.index_progress, done, total) }
            }
            runOnUiThread {
                if (albums.isEmpty()) albums = al
                status.text = getString(R.string.index_done, total - failed, failed)
            }
        }
    }

    // ------------------------------------------------------------------ 最愛 (favorites + recent)

    private fun buildFavorites(): View {
        val col = ui.column().apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) }
        val favs = favorites.all()
        col.addView(ui.text(getString(R.string.tab_favorites), 22f, bold = true).apply { setPadding(ui.dp(14), ui.dp(6), 0, ui.dp(2)) })
        if (favs.isEmpty()) col.addView(ui.text(getString(R.string.no_favorites), 18f, color = R.color.muted).apply {
            setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4))
        })
        favs.forEach { f ->
            val row = ui.row()
            val label = when (f.kind) {
                Favorite.KIND_HYMN -> getString(R.string.hymn_title, f.book!!.zh, f.number)
                Favorite.KIND_LOCAL -> "📱 " + f.title
                else -> f.title
            }
            val open = ui.button(label, primary = true, sp = 19f, height = 64) {
                when (f.kind) {
                    Favorite.KIND_HYMN -> playOrOpen(f.book!!, f.number)
                    Favorite.KIND_LOCAL -> {
                        val lib = LocalLibrary.get(this)
                        val song = lib.byUri(f.path)
                        if (song == null) toast(getString(R.string.local_missing)) else playLocal(song, lib.playable().filter { it.book == song.book })
                    }
                    else -> playNow(Album(f.title, f.path))
                }
            }
            row.addView(open, ui.weightLp(4f))
            row.addView(ui.button("✕", primary = false, sp = 22f, height = 64) {
                AlertDialog.Builder(this).setMessage(getString(R.string.fav_remove_q, label))
                    .setPositiveButton(R.string.remove) { _, _ -> favorites.toggle(f); showTab(4) }
                    .setNegativeButton(R.string.cancel, null).show()
            }, ui.weightLp(1f))
            col.addView(row)
        }
        val hist = History.all(this)
        if (hist.isNotEmpty()) {
            col.addView(ui.text(getString(R.string.recent), 22f, bold = true).apply { setPadding(ui.dp(14), ui.dp(14), 0, ui.dp(2)) })
            hist.forEach { (b, n) ->
                col.addView(ui.button(getString(R.string.hymn_title, b.zh, n), primary = false, sp = 19f, height = 60) { playOrOpen(b, n) }, ui.fullLp())
            }
        }
        return scroll(col)
    }

    // ------------------------------------------------------------------ player bar

    private fun buildPlayerBar(): View {
        val bar = ui.column().apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(4))
        }
        nowTitle = ui.text("", 18f, bold = true).apply { maxLines = 2 }
        nowStatus = ui.text("", 15f, color = R.color.muted).apply { maxLines = 2 }
        bar.addView(nowTitle)
        bar.addView(nowStatus)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ui.button("⏮", primary = false, sp = 24f, height = 64) { control(PlayerService.ACTION_PREV) }, ui.weightLp())
        playButton = ui.button("▶", primary = true, sp = 24f, height = 64) { control(PlayerService.ACTION_TOGGLE) }
        row.addView(playButton, ui.weightLp(1.6f))
        row.addView(ui.button("⏭", primary = false, sp = 24f, height = 64) { control(PlayerService.ACTION_NEXT) }, ui.weightLp())
        row.addView(ui.button("⏹", primary = false, sp = 24f, height = 64) { control(PlayerService.ACTION_STOP) }, ui.weightLp())
        row.addView(ui.button(getString(R.string.queue), primary = false, sp = 18f, height = 64) { showQueue() }, ui.weightLp(1.2f))
        bar.addView(row)
        return FrameLayout(this).apply { setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2)); addView(bar) }
    }

    private fun control(action: String) {
        if (Player.queue.isEmpty()) return toast(getString(R.string.queue_empty))
        if (action == PlayerService.ACTION_TOGGLE && Player.status == Player.Status.IDLE) {
            askNotificationPermission()
            PlayerService.send(this, PlayerService.ACTION_PLAY_INDEX, maxOf(Player.index, 0))
        } else if (Player.status != Player.Status.IDLE || action == PlayerService.ACTION_NEXT || action == PlayerService.ACTION_PREV) {
            if (Player.status == Player.Status.IDLE) {
                val i = (Player.index + if (action == PlayerService.ACTION_NEXT) 1 else -1).coerceIn(0, Player.queue.size - 1)
                PlayerService.send(this, PlayerService.ACTION_PLAY_INDEX, i)
            } else PlayerService.send(this, action)
        }
    }

    private fun refreshPlayer() {
        if (!::nowTitle.isInitialized) return
        val cur = Player.current
        nowTitle.text = cur?.title?.let { getString(R.string.now_playing, Player.index + 1, Player.queue.size, it) }
            ?: getString(if (Player.queue.isEmpty()) R.string.nothing_playing else R.string.queue_ready, Player.queue.size)
        nowStatus.text = Player.message.ifEmpty {
            when (Player.status) {
                Player.Status.PLAYING -> getString(R.string.status_playing)
                Player.Status.PAUSED -> getString(R.string.status_paused)
                else -> getString(R.string.streamed_from)
            }
        }
        playButton.text = if (Player.status == Player.Status.PLAYING || Player.status == Player.Status.LOADING) "⏸" else "▶"
    }

    private fun showQueue() {
        if (Player.queue.isEmpty()) return toast(getString(R.string.queue_empty))
        val items = Player.queue.mapIndexed { i, it -> (if (i == Player.index) "▶ " else "${i + 1}. ") + it.title }
        AlertDialog.Builder(this).setTitle(R.string.queue)
            .setItems(items.toTypedArray()) { _, which ->
                askNotificationPermission()
                PlayerService.send(this, PlayerService.ACTION_PLAY_INDEX, which)
            }
            .setNeutralButton(R.string.clear_queue) { _, _ ->
                PlayerService.send(this, PlayerService.ACTION_STOP)
                Player.queue.clear(); Player.index = -1; Player.changed()
            }
            .setNegativeButton(R.string.close, null).show()
    }

    // ------------------------------------------------------------------ misc

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_text)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.open_official) { _, _ -> openUrl(Sites.GOODLAND_INDEX) }
            .show()
    }

    private fun openUrl(u: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
            .onFailure { toast(getString(R.string.no_browser)) }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val p = getSharedPreferences("app", MODE_PRIVATE)
            if (!p.getBoolean("asked_notif", false)) {
                p.edit().putBoolean("asked_notif", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
}
