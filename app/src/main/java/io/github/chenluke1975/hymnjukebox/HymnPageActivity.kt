package io.github.chenluke1975.hymnjukebox

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Shows the official 影音美地 page of the volume that holds the chosen hymn, inside a plain
 * WebView. The site's own player plays it, after the user signs in with their own account.
 * The app only scrolls to / taps "第N首" on that official page; it never extracts media.
 */
class HymnPageActivity : Activity() {

    companion object {
        const val EXTRA_BOOK = "book"
        const val EXTRA_NUMBER = "number"
    }

    private lateinit var web: WebView
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar
    private lateinit var catalog: Catalog
    private val io = Executors.newSingleThreadExecutor()
    private var book = Book.HYMNS
    private var number = 1
    private var volumes: List<Volume> = Parsers.FALLBACK_VOLUMES
    private var loadedVolume: Volume? = null
    private var hymnTitle = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        catalog = Catalog(this)
        book = intent.getStringExtra(EXTRA_BOOK)?.let { runCatching { Book.valueOf(it) }.getOrNull() } ?: Book.HYMNS
        number = intent.getIntExtra(EXTRA_NUMBER, 1)

        val ui = Ui(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = ui.text("", 24f, bold = true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(4)) }
        root.addView(title)
        val row = ui.row()
        row.addView(ui.button(getString(R.string.prev_hymn), primary = false) { go(number - 1) }, ui.weightLp())
        row.addView(ui.button(getString(R.string.next_hymn), primary = false) { go(number + 1) }, ui.weightLp())
        row.addView(ui.button(getString(R.string.close), primary = true) { finish() }, ui.weightLp())
        root.addView(row)
        root.addView(ui.text(getString(R.string.goodland_note), 15f).apply {
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(6))
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(6)))

        web = WebView(this)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true) // site's own video player frame
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            textZoom = 125
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.addJavascriptInterface(Bridge(), "HymnJukebox")
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                progress.visibility = if (p >= 100) View.GONE else View.VISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (!req.isForMainFrame) return false
                if (Sites.isOfficial(u)) return false
                // e.g. line:// or intent:// links on the official site: hand to the system.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                if (url != null && loadedVolume != null && url.contains("/course-detail/${loadedVolume!!.courseId}")) {
                    selectOnPage()
                }
            }
        }
        root.addView(FrameLayout(this).apply { addView(web) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (web.canGoBack()) web.goBack() else finish()
            }
        }

        io.execute {
            val v = catalog.volumes()
            runOnUiThread { volumes = v; go(number) }
        }
        updateTitle()
    }

    private fun updateTitle() {
        title.text = getString(R.string.hymn_title, book.zh, number) + if (hymnTitle.isNotEmpty()) "\n$hymnTitle" else ""
    }

    private fun go(n: Int) {
        if (n < 1) return
        val v = Parsers.findVolume(volumes, book, n)
        if (v == null) {
            Toast.makeText(this, getString(R.string.no_such_hymn, book.zh, n), Toast.LENGTH_LONG).show()
            if (loadedVolume == null) web.loadUrl(Sites.GOODLAND_INDEX)
            return
        }
        number = n
        hymnTitle = ""
        updateTitle()
        History.add(this, book, n)
        if (loadedVolume?.courseId == v.courseId && web.url?.contains("/course-detail/${v.courseId}") == true) {
            selectOnPage()
        } else {
            loadedVolume = v
            web.loadUrl(v.url)
        }
    }

    /** Scrolls the official page to "第N首", highlights it and taps it (as the user would). */
    private fun selectOnPage() {
        val js = """
            (function(n){
              var links = document.querySelectorAll('a[title]');
              var hit = null;
              for (var i=0;i<links.length;i++){
                var m = /^第(\d+)首/.exec(links[i].getAttribute('title')||'');
                if (m && parseInt(m[1],10)===n){ hit = links[i]; break; }
              }
              document.querySelectorAll('.hymnjukebox-hit').forEach(function(e){e.classList.remove('hymnjukebox-hit');e.style.outline='';e.style.background='';});
              if (!hit) { HymnJukebox.found(false, ''); return; }
              var tab = document.querySelector('a[href="#tab-item2"]'); if (tab) tab.click();
              var row = hit.closest('tr') || hit;
              row.classList.add('hymnjukebox-hit');
              row.style.outline='4px solid #F2B84B'; row.style.background='#FFF4D6';
              row.scrollIntoView({block:'center'});
              var t = (hit.getAttribute('title')||'').replace(/^第\d+首[.．]?\s*/, '');
              HymnJukebox.found(true, t);
              try { hit.click(); } catch(e) {}
            })($number);
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun found(ok: Boolean, t: String) {
            runOnUiThread {
                hymnTitle = t.trim()
                updateTitle()
                if (!ok) Toast.makeText(this@HymnPageActivity, R.string.not_on_page, Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("GestureBackNavigation")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        io.shutdownNow()
        web.destroy()
        super.onDestroy()
    }
}
