package com.glasstv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.concurrent.thread

data class AppEntry(val pkg: String, val label: String, val icon: Drawable, val intent: Intent)


private class SpotlightArtwork(context: android.content.Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.rgb(34, 34, 67), Color.rgb(86, 79, 119), Color.rgb(18, 25, 45)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null

        p.color = Color.argb(90, 207, 198, 255)
        canvas.drawCircle(w * .68f, h * .18f, h * .43f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * .035f
        p.color = Color.argb(105, 215, 208, 255)
        canvas.save()
        canvas.rotate(-17f, w * .68f, h * .18f)
        canvas.drawOval(w * .48f, h * .05f, w * .91f, h * .32f, p)
        canvas.restore()
        p.style = Paint.Style.FILL

        val far = Path().apply {
            moveTo(0f, h * .78f); lineTo(w * .18f, h * .57f); lineTo(w * .31f, h * .72f)
            lineTo(w * .48f, h * .55f); lineTo(w * .65f, h * .76f); lineTo(w * .82f, h * .60f)
            lineTo(w, h * .73f); lineTo(w, h); lineTo(0f, h); close()
        }
        p.color = Color.rgb(35, 39, 61); canvas.drawPath(far, p)
        val near = Path().apply {
            moveTo(0f, h * .88f); lineTo(w * .19f, h * .74f); lineTo(w * .34f, h * .85f)
            lineTo(w * .55f, h * .70f); lineTo(w * .73f, h * .86f); lineTo(w * .89f, h * .72f)
            lineTo(w, h * .82f); lineTo(w, h); lineTo(0f, h); close()
        }
        p.color = Color.rgb(17, 22, 37); canvas.drawPath(near, p)

        // Small distant astronaut silhouette on the right side of the spotlight.
        p.color = Color.rgb(9, 14, 22)
        val ax = w * .84f
        val ay = h * .45f
        canvas.drawCircle(ax, ay, h * .055f, p)
        canvas.drawRoundRect(ax - h*.07f, ay + h*.04f, ax + h*.065f, ay + h*.34f, h*.035f, h*.035f, p)
        canvas.drawRoundRect(ax - h*.12f, ay + h*.08f, ax - h*.065f, ay + h*.29f, h*.02f, h*.02f, p)
        canvas.drawLine(ax - h*.035f, ay + h*.32f, ax - h*.09f, h*.94f, p.apply { strokeWidth = h*.055f; strokeCap = Paint.Cap.ROUND })
        canvas.drawLine(ax + h*.035f, ay + h*.32f, ax + h*.08f, h*.94f, p)
        canvas.drawLine(ax - h*.06f, ay + h*.12f, ax - h*.13f, ay + h*.30f, p)
        canvas.drawLine(ax + h*.06f, ay + h*.12f, ax + h*.12f, ay + h*.29f, p)
    }
}

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var apps = listOf<AppEntry>()
    private var featuredApp: AppEntry? = null

    private lateinit var scroll: ScrollView
    private lateinit var bg: ImageView
    private lateinit var content: LinearLayout
    private lateinit var clock: TextClock
    private lateinit var weather: TextView
    private lateinit var featured: FrameLayout
    private lateinit var favRow: LinearLayout
    private lateinit var recentRow: LinearLayout
    private lateinit var allRow: LinearLayout
    private lateinit var allTitle: TextView
    private val navViews = linkedMapOf<String, TextView>()

    private val presets = listOf(
        "Midnight Aurora" to listOf("#070B1E", "#2A1B5E", "#0B5D70"),
        "Deep Ocean" to listOf("#02070F", "#0A2A4A", "#136F8F"),
        "Crimson Dusk" to listOf("#0E0509", "#4A1230", "#A33A3A"),
        "Emerald Night" to listOf("#030B0A", "#0C3B32", "#1F7A5C"),
        "Carbon" to listOf("#050505", "#1A1A22", "#34343F")
    )
    private val cities = listOf(
        Triple("Addis Ababa", 9.03, 38.74),
        Triple("Nairobi", -1.29, 36.82),
        Triple("Dubai", 25.20, 55.27),
        Triple("London", 51.51, -0.13),
        Triple("New York", 40.71, -74.01),
        Triple("Mumbai", 19.08, 72.88)
    )

    private val weatherTask = object : Runnable {
        override fun run() { fetchWeather(); ui.postDelayed(this, 30 * 60 * 1000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("glass", MODE_PRIVATE)
        buildUi()
        applyWallpaper()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ui.removeCallbacks(weatherTask); ui.post(weatherTask)
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(weatherTask) }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        scroll.smoothScrollTo(0, 0); featured.requestFocus()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { /* a launcher never exits */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) { openSettings(); return true }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 42 && resultCode == RESULT_OK && uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.edit().putString("wp", "uri:$uri").apply()
            applyWallpaper()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun tv(t: String, sp: Float, alpha: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(Color.argb(alpha, 255, 255, 255))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun glass(radiusDp: Int, focused: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (focused) intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(50, 255, 255, 255))
        else intArrayOf(Color.argb(48, 255, 255, 255), Color.argb(18, 255, 255, 255))
    ).apply {
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(if (focused) 3 else 1), if (focused) Color.argb(235, 255, 255, 255) else Color.argb(64, 255, 255, 255))
    }

    private fun glassify(v: View, radius: Int, onClick: () -> Unit, onLong: (() -> Unit)? = null, scale: Float = 1.08f) {
        v.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), glass(radius, true))
            addState(intArrayOf(), glass(radius, false))
        }
        v.isFocusable = true
        v.isClickable = true
        v.setOnClickListener { onClick() }
        if (onLong != null) v.setOnLongClickListener { onLong(); true }
        v.setOnFocusChangeListener { view, has ->
            val s = if (has) scale else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(160).start()
        }
    }

    private fun addRow(): LinearLayout {
        val h = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(0, dp(14), 0, dp(14))
        }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        h.addView(r)
        content.addView(h, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return r
    }

    private fun title(t: String): TextView = tv(t, 15f, 235, true).apply {
        setPadding(dp(8), dp(14), 0, 0)
        content.addView(this)
    }

    private fun updateNavSelection(name: String) {
        navViews.forEach { (label, item) ->
            val active = label == name
            item.text = (if (active) "⌂  " else "   ") + label
            item.setTextColor(Color.argb(if (active) 255 else 175, 255, 255, 255))
            item.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            item.background = if (active) GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(67, 55, 111))
            } else null
        }
    }

    private fun scrollToSection(target: View) {
        var section = target
        while (section.parent is View && section.parent !== content) {
            section = section.parent as View
        }
        scroll.post { scroll.smoothScrollTo(0, section.top) }
    }

    private fun showCategory(name: String) {
        val terms = when (name) {
            "Movies" -> listOf("netflix", "prime", "disney", "plex", "movie", "cinema", "youtube", "video")
            "TV Shows" -> listOf("netflix", "prime", "disney", "plex", "tv", "youtube", "video")
            "Games" -> listOf("game", "play", "stadia", "xbox", "geforce", "steam")
            else -> emptyList()
        }
        val matches = apps.filter { app -> terms.any { app.label.contains(it, true) } }
        allTitle.text = name
        fill(allRow, matches, "No matching $name apps found")
        scrollToSection(allRow)
    }

    private fun navItem(label: String, selected: Boolean = false, action: () -> Unit = {}): TextView =
        tv((if (selected) "⌂  " else "   ") + label, 12f, if (selected) 255 else 175, selected).apply {
            setPadding(dp(10), dp(10), dp(6), dp(10))
            background = if (selected) GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(67, 55, 111))
            } else null
            isFocusable = true
            isClickable = true
            setOnClickListener { updateNavSelection(label); action() }
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
        }

        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.rgb(23, 21, 45))
            }
        }
        listOf("Home", "Apps", "Movies", "TV Shows", "Games", "Settings").forEachIndexed { index, name ->
            val item = navItem(name, index == 0) {
                when (name) {
                    "Settings" -> openSettings()
                    "Apps" -> { allTitle.text = "All apps"; fill(allRow, apps, "No apps found"); scrollToSection(allRow) }
                    "Home" -> { allTitle.text = "All apps"; refresh(); scroll.smoothScrollTo(0, 0) }
                    "Movies", "TV Shows", "Games" -> showCategory(name)
                }
            }
            navViews[name] = item
            sidebar.addView(item, lp(MATCH_PARENT, WRAP_CONTENT, t = 4))
        }
        sidebar.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        sidebar.addView(navItem("Power") { dialog().setTitle("Power").setItems(arrayOf("Android system settings", "Restart launcher")) { _, i ->
            if (i == 0) safeStart(Intent(Settings.ACTION_SETTINGS)) else refresh()
        }.show() })
        root.addView(sidebar, LinearLayout.LayoutParams(dp(88), MATCH_PARENT).apply {
            setMargins(dp(12), dp(12), dp(12), dp(12))
        })

        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(22), dp(8))
        }
        val brandIcon = tv("⌂", 18f, 255, true).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.rgb(116, 88, 220))
            }
        }
        header.addView(brandIcon, lp(dp(28), dp(28), r = 8))
        val brand = tv("GlassHome", 14f, 245, true)
        header.addView(brand)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val searchBar = tv("⌕  Search movies, apps and more", 13f, 170, false).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.rgb(35, 32, 58))
            }
        }
        glassify(searchBar, 22, { openSearch() }, scale = 1.02f)
        header.addView(searchBar, lp(dp(205), WRAP_CONTENT, r = 14))
        weather = tv("◉", 16f, 220, false).apply { gravity = Gravity.CENTER }
        header.addView(weather, lp(dp(34), WRAP_CONTENT, r = 8))
        val settingsBtn = tv("⚙", 18f, 230, false).apply { gravity = Gravity.CENTER }
        glassify(settingsBtn, 18, { openSettings() }, scale = 1.05f)
        header.addView(settingsBtn, lp(dp(34), dp(34), r = 8))
        val notify = tv("♧", 17f, 230, false).apply { gravity = Gravity.CENTER }
        glassify(notify, 18, { Toast.makeText(this, "No new notifications", Toast.LENGTH_SHORT).show() })
        header.addView(notify, lp(dp(34), dp(34), r = 18))
        clock = TextClock(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        applyClock()
        header.addView(clock, lp(WRAP_CONTENT, WRAP_CONTENT, l = 18))
        main.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(4), dp(4), dp(14), dp(18))
        }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        main.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(main, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        // Place the wallpaper image behind the launcher UI.
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val screen = FrameLayout(this).apply {
            addView(bg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(screen)

        featured = FrameLayout(this).apply {
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat() }
            isFocusable = true
            isClickable = true
        }
        featured.addView(SpotlightArtwork(this), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        featured.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(13, 13, 30), Color.argb(220, 13, 13, 30), Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        glassify(featured, 18, { featuredApp?.let { launch(it) } ?: openSearch() }, null, 1.01f)
        content.addView(featured, lp(MATCH_PARENT, dp(156), t = 4))
        title("Your apps")
        favRow = addRow()
        title("Continue watching")
        recentRow = addRow()
        allTitle = title("All apps")
        allRow = addRow()
        updateNavSelection("Home")
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when { h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }
    }

    private fun fillFeatured() {
        featured.removeViews(2, (featured.childCount - 2).coerceAtLeast(0))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
        }
        copy.addView(tv("GLASSHOME SPOTLIGHT", 8f, 190, true).apply { letterSpacing = 0.18f })
        copy.addView(tv("BEYOND THE\nSILENCE", 25f, 255, true).apply {
            setLineSpacing(0f, 0.92f)
            setPadding(0, dp(5), 0, dp(5))
        })
        copy.addView(tv("2025   •   Sci-fi adventure   •   2h 18m", 9f, 205, false))
        copy.addView(tv("One signal. A thousand worlds waiting.\nDiscover what lies on the other side.", 9f, 205, false).apply {
            setPadding(0, dp(5), 0, 0)
        })
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, 0)
        }
        val watch = tv("▶  Watch now", 10f, 255, true).apply {
            setPadding(dp(13), dp(7), dp(13), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.rgb(202, 183, 255))
            }
            setTextColor(Color.rgb(25, 20, 42))
            isFocusable = true
            setOnClickListener { featuredApp?.let { launch(it) } ?: openSearch() }
        }
        actions.addView(watch)
        val watchlist = tv("＋  Watchlist", 10f, 235, false).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(105, 55, 49, 79))
            }
            isFocusable = true
            setOnClickListener { Toast.makeText(this@MainActivity, "Watchlist coming soon", Toast.LENGTH_SHORT).show() }
        }
        actions.addView(watchlist, lp(WRAP_CONTENT, WRAP_CONTENT, l = 7))
        copy.addView(actions)
        featured.addView(copy, FrameLayout.LayoutParams(dp(300), MATCH_PARENT, Gravity.LEFT or Gravity.CENTER_VERTICAL))
        featured.addView(tv("01 / 04", 8f, 230, false).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            setPadding(0, dp(10), dp(12), 0)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.RIGHT))
        featured.addView(tv("●  •  •", 8f, 210, false).apply {
            gravity = Gravity.BOTTOM or Gravity.RIGHT
            setPadding(0, 0, dp(16), dp(9))
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.RIGHT))
    }

    private fun appCard(a: AppEntry): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = lp(dp(80), WRAP_CONTENT, 4, 2, 4, 2)
        }
        val tile = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(Color.rgb(37, 35, 57))
            }
            isFocusable = true
            isClickable = true
        }
        val icon = ImageView(this).apply {
            setImageDrawable(a.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
        }
        tile.addView(icon)
        glassify(tile, 11, { launch(a) }, { appMenu(a) }, 1.045f)
        wrapper.addView(tile, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        wrapper.addView(tv(a.label, 9f, 220, false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return wrapper
    }

    private fun fill(row: LinearLayout, list: List<AppEntry>, empty: String) {
        row.removeAllViews()
        if (list.isEmpty()) row.addView(tv(empty, 16f, 140, false).apply { setPadding(dp(8), dp(40), 0, dp(40)) })
        else list.forEach { row.addView(appCard(it)) }
    }

    private fun getList(key: String) = (prefs.getString(key, "") ?: "").split(",").filter { it.isNotEmpty() }
    private fun putList(key: String, l: List<String>) = prefs.edit().putString(key, l.joinToString(",")).apply()

    private fun loadApps() {
        val pm = packageManager
        val map = LinkedHashMap<String, AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || map.containsKey(pkg)) continue
                val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
                map[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), intent)
            }
        }
        apps = map.values.sortedBy { it.label.lowercase() }
    }

    private fun refresh() {
        loadApps()
        val byPkg = apps.associateBy { it.pkg }
        val recents = getList("recent").mapNotNull { byPkg[it] }
        val favs = getList("fav").mapNotNull { byPkg[it] }
        featuredApp = recents.firstOrNull() ?: apps.firstOrNull()
        fillFeatured()
        fill(favRow, favs, "Hold OK on any app to pin it here")
        fill(recentRow, recents.take(10), "Apps you open will appear here")
        fill(allRow, apps, "No apps found")
        if (currentFocus == null) featured.requestFocus()
    }

    private fun launch(a: AppEntry) {
        val r = getList("recent").toMutableList()
        r.remove(a.pkg); r.add(0, a.pkg)
        putList("recent", r.take(12))
        safeStart(Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Toast.makeText(this, "Can't open that on this device", Toast.LENGTH_SHORT).show() }
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun appMenu(a: AppEntry) {
        val fav = getList("fav").contains(a.pkg)
        dialog().setTitle(a.label)
            .setItems(arrayOf(if (fav) "Remove from favorites" else "Add to favorites", "App info / uninstall")) { _, i ->
                if (i == 0) {
                    val f = getList("fav").toMutableList()
                    if (!f.remove(a.pkg)) f.add(a.pkg)
                    putList("fav", f); refresh()
                } else safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
            }.show()
    }

    private fun openSearch() {
        val et = EditText(this).apply {
            hint = "App name or web search"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        dialog().setTitle("Search").setView(et)
            .setPositiveButton("Go") { _, _ -> runSearch(et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun runSearch(q: String) {
        if (q.isBlank()) return
        val m = apps.firstOrNull { it.label.contains(q.trim(), true) }
        if (m != null) launch(m)
        else safeStart(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
    }

    private fun openSettings() {
        val city = prefs.getInt("city", 0).coerceIn(cities.indices)
        val h24 = prefs.getBoolean("h24", true)
        val items = arrayOf(
            "Wallpaper…", "Weather location: ${cities[city].first}",
            "Clock format: ${if (h24) "24-hour" else "12-hour"}",
            "Android system settings", "Wi-Fi / network settings"
        )
        dialog().setTitle("Glass Home settings").setItems(items) { _, i ->
            when (i) {
                0 -> wallpaperMenu()
                1 -> dialog().setTitle("Weather location")
                    .setItems(cities.map { it.first }.toTypedArray()) { _, c ->
                        prefs.edit().putInt("city", c).apply(); fetchWeather()
                    }.show()
                2 -> { prefs.edit().putBoolean("h24", !h24).apply(); applyClock() }
                3 -> safeStart(Intent(Settings.ACTION_SETTINGS))
                4 -> safeStart(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }.show()
    }

    private fun wallpaperMenu() {
        val items = (presets.map { it.first } + "Choose an image from storage…").toTypedArray()
        dialog().setTitle("Wallpaper").setItems(items) { _, i ->
            if (i < presets.size) { prefs.edit().putString("wp", "preset:$i").apply(); applyWallpaper() }
            else try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 42)
            } catch (e: Exception) {
                Toast.makeText(this, "No file manager installed on this box", Toast.LENGTH_LONG).show()
            }
        }.show()
    }

    private fun applyClock() {
        val f = if (prefs.getBoolean("h24", true)) "HH:mm" else "h:mm a"
        clock.format12Hour = f; clock.format24Hour = f
    }

    private fun applyWallpaper() {
        val mode = prefs.getString("wp", "preset:0") ?: "preset:0"
        if (mode.startsWith("uri:")) {
            thread {
                try {
                    val uri = Uri.parse(mode.removePrefix("uri:"))
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
                    var s = 1
                    while (o.outWidth / (s * 2) >= 1920) s *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = s }
                    val bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
                    ui.post { bg.setImageBitmap(bmp) }
                } catch (e: Exception) {
                    ui.post { prefs.edit().putString("wp", "preset:0").apply(); applyWallpaper() }
                }
            }
        } else {
            val i = (mode.removePrefix("preset:").toIntOrNull() ?: 0).coerceIn(presets.indices)
            val cols = presets[i].second.map { Color.parseColor(it) }.toIntArray()
            bg.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, cols))
        }
    }

    private fun weatherText(c: Int) = when (c) {
        0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"
        in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"
        in 80..82 -> "Showers"; in 95..99 -> "Thunderstorm"; else -> "—"
    }

    private fun fetchWeather() {
        val c = cities[prefs.getInt("city", 0).coerceIn(cities.indices)]
        thread {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${c.second}&longitude=${c.third}&current=temperature_2m,weather_code")
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 8000; con.readTimeout = 8000
                val cur = JSONObject(con.inputStream.bufferedReader().readText()).getJSONObject("current")
                val t = Math.round(cur.getDouble("temperature_2m"))
                val txt = "${t}°C  ${weatherText(cur.getInt("weather_code"))}  ·  ${c.first}"
                ui.post { weather.text = txt }
            } catch (e: Exception) {
                ui.post { weather.text = "Weather unavailable" }
            }
        }
    }
}