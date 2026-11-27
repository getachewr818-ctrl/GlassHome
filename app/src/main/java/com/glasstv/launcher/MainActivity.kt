package com.glasstv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
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
    private lateinit var featured: LinearLayout
    private lateinit var favRow: LinearLayout
    private lateinit var recentRow: LinearLayout
    private lateinit var allRow: LinearLayout

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

    private fun title(t: String) = content.addView(tv(t, 20f, 210, true).apply { setPadding(dp(8), dp(24), 0, 0) })

    private fun buildUi() {
        val root = FrameLayout(this)
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(bg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(View(this).apply { setBackgroundColor(Color.argb(90, 0, 0, 0)) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            setPadding(dp(56), dp(32), dp(56), dp(56))
        }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val timeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextClock(this).apply {
            textSize = 64f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        applyClock()
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"; format24Hour = "EEEE, d MMMM"
            textSize = 20f; setTextColor(Color.argb(200, 255, 255, 255))
        }
        timeCol.addView(clock); timeCol.addView(date)
        weather = tv("Loading weather…", 18f, 230, false).apply {
            setPadding(dp(24), dp(14), dp(24), dp(14)); background = glass(20, false)
        }
        val searchBar = tv("Search apps or the web", 18f, 200, false).apply {
            setPadding(dp(28), dp(16), dp(28), dp(16)); gravity = Gravity.CENTER_VERTICAL
        }
        glassify(searchBar, 40, { openSearch() })
        val settingsBtn = tv("Settings", 18f, 230, false).apply { setPadding(dp(28), dp(16), dp(28), dp(16)) }
        glassify(settingsBtn, 40, { openSettings() })
        header.addView(timeCol)
        header.addView(weather, lp(WRAP_CONTENT, WRAP_CONTENT, l = 40))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(searchBar, lp(dp(380), WRAP_CONTENT))
        header.addView(settingsBtn, lp(WRAP_CONTENT, WRAP_CONTENT, l = 16))
        content.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        featured = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(44), dp(24), dp(44), dp(24)) }
        glassify(featured, 36, { featuredApp?.let { launch(it) } }, null, 1.03f)
        content.addView(featured, lp(MATCH_PARENT, dp(220), t = 28))

        title("Favorites"); favRow = addRow()
        title("Recently used"); recentRow = addRow()
        title("All apps"); allRow = addRow()
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when { h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }
    }

    private fun fillFeatured() {
        featured.removeAllViews()
        val a = featuredApp
        if (a != null) featured.addView(ImageView(this).apply {
            setImageDrawable(a.icon)
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(112)).apply { rightMargin = dp(36) }
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("${greeting().uppercase()}  ·  CONTINUE", 14f, 170, false).apply { letterSpacing = 0.15f })
        col.addView(tv(a?.label ?: "Welcome", 40f, 255, true))
        col.addView(tv("Press OK to open  ·  Menu button for settings", 16f, 150, false).apply { setPadding(0, dp(8), 0, 0) })
        featured.addView(col)
    }

    private fun appCard(a: AppEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(14), dp(18), dp(14), dp(14))
            layoutParams = lp(dp(150), dp(150), 8, 4, 8, 4)
        }
        card.addView(ImageView(this).apply {
            setImageDrawable(a.icon); layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        })
        card.addView(tv(a.label, 14f, 240, false).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
        })
        glassify(card, 24, { launch(a) }, { appMenu(a) })
        return card
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