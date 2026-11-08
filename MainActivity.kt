package com.gabs.dynamicisland

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvOverlay: TextView
    private lateinit var tvNotif: TextView
    private lateinit var root: LinearLayout
    private val gray = 0xFF9A9AA0.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val sv = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(40))
        }
        sv.addView(root)

        add(label("Dynamic Island", 30f, Color.WHITE, true), 0)
        add(label("by GABS • untuk Android", 14f, gray, false), 2)

        add(label("1. Izin", 18f, Color.WHITE, true), 28)
        tvOverlay = label("", 14f, Color.WHITE, false)
        tvNotif = label("", 14f, Color.WHITE, false)
        add(tvOverlay, 8)
        add(tvNotif, 4)
        add(btn("Izinkan tampil di atas aplikasi lain") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }, 10)
        add(btn("Izinkan akses notifikasi") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, 8)

        add(label("2. Jalankan", 18f, Color.WHITE, true), 28)
        add(btn("▶  Aktifkan Island") {
            if (!Settings.canDrawOverlays(this)) {
                toast("Izinkan overlay dulu")
                return@btn
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            startForegroundService(Intent(this, IslandService::class.java))
        }, 10)
        add(btn("■  Matikan Island") {
            stopService(Intent(this, IslandService::class.java))
        }, 8)

        add(label("3. Atur ukuran & posisi", 18f, Color.WHITE, true), 28)
        add(label("Sesuaikan sampai pil hitam pas menutup lubang kamera HP Anda.", 13f, gray, false), 6)
        slider("Lebar pil", Prefs.W, 40, 240, 126)
        slider("Tinggi pil", Prefs.H, 20, 70, 37)
        slider("Posisi dari atas", Prefs.Y, 0, 90, 12)
        val sw = Switch(this).apply {
            text = "Tampilkan pil hitam saat idle"
            setTextColor(Color.WHITE)
            isChecked = Prefs.getBool(this@MainActivity, Prefs.IDLE, true)
            setOnCheckedChangeListener { _, c ->
                Prefs.putBool(this@MainActivity, Prefs.IDLE, c)
                IslandBus.service?.reload()
            }
        }
        add(sw, 14)

        add(label("4. Coba", 18f, Color.WHITE, true), 28)
        add(btn("Tes notifikasi") { withService { it.testNotif() } }, 10)
        add(btn("Tes mengisi daya") { withService { it.testCharge() } }, 8)
        add(btn("Tes musik (ketuk lagi untuk berhenti)") { withService { it.testMusic() } }, 8)
        add(btn("Tes navigasi / panah Maps (ketuk lagi untuk berhenti)") { withService { it.testNav() } }, 8)

        add(label(
            "Tips: matikan optimasi baterai untuk aplikasi ini agar island tidak dimatikan sistem. " +
                    "Tap island = buka / tutup, geser ke atas = tutup, tap notifikasi = buka aplikasinya.",
            12f, gray, false
        ), 28)

        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        tvOverlay.text = (if (ov) "✅" else "❌") + "  Izin tampil di atas aplikasi lain"
        tvNotif.text = (if (nl) "✅" else "❌") + "  Akses notifikasi & musik"
    }

    private fun withService(block: (IslandService) -> Unit) {
        val s = IslandBus.service
        if (s == null) toast("Aktifkan island dulu") else block(s)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun add(v: View, top: Int) {
        root.addView(
            v,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(top) }
        )
    }

    private fun label(t: String, sp: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    private fun btn(t: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF2C2C2E.toInt())
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { onClick() }
        }

    private fun slider(name: String, key: String, lo: Int, hi: Int, def: Int) {
        val cur = Prefs.getInt(this, key, def)
        val t = label("$name: ${cur}dp", 14f, Color.WHITE, false)
        val sb = SeekBar(this).apply {
            max = hi - lo
            progress = cur - lo
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + lo
                t.text = "$name: ${v}dp"
                Prefs.putInt(this@MainActivity, key, v)
                if (fromUser) IslandBus.service?.reload()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        add(t, 14)
        add(sb, 2)
    }
}
