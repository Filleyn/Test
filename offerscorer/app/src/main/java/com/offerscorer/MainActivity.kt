package com.offerscorer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.offerscorer.ui.C
import com.offerscorer.ui.MileageScreen
import com.offerscorer.ui.OffersScreen
import com.offerscorer.ui.Screen
import com.offerscorer.ui.SettingsScreen
import com.offerscorer.ui.StatusScreen
import com.offerscorer.ui.Ui

class MainActivity : Activity() {

    val ui by lazy { Ui(this) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private lateinit var scroll: ScrollView
    private val tabViews = ArrayList<TextView>()
    private val screens = LinkedHashMap<String, Screen>()
    private var current: Screen? = null

    private var pendingAfterPermission: (() -> Unit)? = null
    private var pendingExport: String? = null

    private val ticker = object : Runnable {
        override fun run() { current?.tick(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG

        screens["Status"] = StatusScreen(this)
        screens["Offers"] = OffersScreen(this)
        screens["Mileage"] = MileageScreen(this)
        screens["Settings"] = SettingsScreen(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            fitsSystemWindows = true
        }
        val title = ui.big("OFFER SCORER", 26f).apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(6)) }
        root.addView(title)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8))
        }
        for (name in screens.keys) {
            val t = TextView(this).apply {
                text = name.uppercase(); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(11), 0, ui.dp(11))
                setOnClickListener { show(name) }
            }
            tabViews.add(t)
            tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) })
        }
        root.addView(tabs)
        root.addView(ui.divider())

        content = FrameLayout(this).apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(40)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        show(savedInstanceState?.getString("tab") ?: "Status")
    }

    fun show(name: String) {
        val s = screens[name] ?: return
        current = s
        content.removeAllViews()
        (s.view.parent as? ViewGroup)?.removeView(s.view)
        content.addView(s.view)
        s.refresh()
        scroll.scrollTo(0, 0)
        screens.keys.forEachIndexed { i, n ->
            val on = n == name
            tabViews[i].setTextColor(if (on) C.ACCENT else C.MUTED)
            tabViews[i].background = if (on) ui.rounded(C.SURFACE, 8, C.LINE) else null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        screens.entries.firstOrNull { it.value === current }?.let { outState.putString("tab", it.key) }
    }

    override fun onResume() {
        super.onResume()
        current?.refresh()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- permissions ----------

    fun withLocationPermission(action: () -> Unit) {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isEmpty()) { action(); return }
        pendingAfterPermission = action
        requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            action?.invoke()
        } else {
            toast("Mileage tracking needs precise location. Allow it in Settings > Apps > Offer Scorer > Permissions.")
        }
    }

    // ---------- saving files ----------

    /** Asks where to save, then writes the text there (Downloads, Google Drive, etc.). */
    @Suppress("DEPRECATION")
    fun saveFile(fileName: String, mime: String, text: String) {
        pendingExport = text
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, fileName)
        startActivityForResult(i, REQ_SAVE)
    }

    @Deprecated("Uses the platform API so the app needs no extra libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE) return
        val text = pendingExport
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || text == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            toast("Saved.")
        } catch (e: Exception) {
            toast("Couldn't save the file: ${e.message}")
        }
    }

    fun openAccessibilitySettings() {
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openAppInfo() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
    }

    companion object {
        private const val REQ_PERMS = 10
        private const val REQ_SAVE = 11
    }
}

/** Keeps a reference so screens can hide/show sections without rebuilding. */
fun View.visible(on: Boolean) { visibility = if (on) View.VISIBLE else View.GONE }
