package com.gabs.dynamicisland

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class IslandService : Service() {

    companion object {
        const val MODE_IDLE = 0
        const val MODE_MEDIA_COMPACT = 1
        const val MODE_MEDIA_EXPANDED = 2
        const val MODE_TRANSIENT = 3
        const val MODE_NAV_COMPACT = 4
        const val MODE_NAV_EXPANDED = 5
        private val NAV_PKGS = setOf(
            "com.google.android.apps.maps", "com.waze", "net.osmand", "net.osmand.plus", "com.here.app.maps"
        )
        private const val CH = "island_service"
        private val GRAY = 0xFFAAAAAA.toInt()
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var island: IslandView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var receiverRegistered = false

    private var mode = -1
    private var transientAction: PendingIntent? = null
    private var lastNotifKey = ""
    private var lastNotifTime = 0L

    // ---- media ----
    private var msm: MediaSessionManager? = null
    private var listenerCn: ComponentName? = null
    private var mediaHooked = false
    private var media: MediaController? = null
    private var mediaCb: MediaController.Callback? = null
    private var demo = false
    private var hasMedia = false
    private var playing = false
    private var title = ""
    private var artist = ""
    private var art: Bitmap? = null
    private var accent = 0xFFFF2D55.toInt()
    private var duration = 0L

    // ---- navigasi ----
    private var navActive = false
    private var navKey = ""
    private var navTitle = ""
    private var navText = ""
    private var navSub = ""
    private var navIcon: Drawable? = null
    private var navIntent: PendingIntent? = null
    private var navLastSeen = 0L

    // referensi view
    private var cArt: ImageView? = null
    private var cEq: EqView? = null
    private var exTitle: TextView? = null
    private var exArtist: TextView? = null
    private var exArt: ImageView? = null
    private var exPlay: GlyphView? = null
    private var exEq: EqView? = null
    private var exProg: ProgressLine? = null
    private var nArt: ImageView? = null
    private var nTitle: TextView? = null
    private var nxArt: ImageView? = null
    private var nxTitle: TextView? = null
    private var nxText: TextView? = null
    private var nxSub: TextView? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list) }

    private val collapseRun = Runnable { collapseNow() }

    private val progressRun = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) showCharging()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        IslandBus.service = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createIsland()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerReceiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(powerReceiver, f)
        }
        receiverRegistered = true
        startMedia()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try { unregisterReceiver(powerReceiver) } catch (e: Exception) { }
        }
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { }
        mediaCb?.let { cb -> try { media?.unregisterCallback(cb) } catch (e: Exception) { } }
        island?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        island = null
        if (IslandBus.service === this) IslandBus.service = null
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "Dynamic Island", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH)
            .setContentTitle("Dynamic Island aktif")
            .setContentText("Ketuk untuk membuka pengaturan")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ------------------------------------------------------------------ window

    private fun idleW() = dp(Prefs.getInt(this, Prefs.W, 126))
    private fun idleH() = dp(Prefs.getInt(this, Prefs.H, 37))
    private fun expW() = minOf(screenWidth() - dp(16), dp(380))

    private fun createIsland() {
        val v = IslandView(this)
        v.onTap = { onIslandTap() }
        v.onSwipeUp = { collapseNow() }
        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = dp(Prefs.getInt(this, Prefs.Y, 12))
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        lp = p
        island = v
        wm.addView(v, p)
        showBase(true)
    }

    private fun setVisible(v: Boolean) {
        island?.animate()?.alpha(if (v) 1f else 0f)?.setDuration(200)?.start()
    }

    // ------------------------------------------------------------------ API publik (dipanggil Activity / Listener)

    fun reload() {
        val i = island ?: return
        lp?.let {
            it.y = dp(Prefs.getInt(this, Prefs.Y, 12))
            try { wm.updateViewLayout(i, it) } catch (e: Exception) { }
        }
        if (mode == MODE_IDLE || mode == MODE_MEDIA_COMPACT || mode == MODE_NAV_COMPACT) showBase(true)
        startMedia()
    }

    fun testNotif() {
        showNotif(
            getDrawable(R.drawable.ic_launcher), "Dynamic Island",
            "Halo, Gabs!", "Ini contoh notifikasi yang muncul di Dynamic Island.", null
        )
    }

    fun testCharge() = showCharging()

    fun testNav() {
        if (navActive && navKey == "demo") {
            navActive = false
            navIcon = null
            collapseNow()
            return
        }
        navActive = true
        navKey = "demo"
        navTitle = "300 m"
        navText = "Belok kanan ke Jl. Sudirman"
        navSub = "12 mnt • 4,2 km"
        navIcon = ArrowDrawable()
        navIntent = null
        showNavExpanded()
    }

    fun testMusic() {
        if (demo && hasMedia && media == null) {
            demo = false
            hasMedia = false
            playing = false
            collapseNow()
            return
        }
        demo = true
        media = null
        hasMedia = true
        playing = true
        title = "Lagu Contoh"
        artist = "Dynamic Island"
        art = null
        duration = 0L
        showMediaExpanded()
    }

    fun onNotif(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 &&
            (n.category == "navigation" || sbn.packageName in NAV_PKGS)
        ) {
            handler.post { onNav(sbn) }
            return
        }
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (ongoing && n.category != Notification.CATEGORY_CALL) return
        val ex = n.extras
        val ttl = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val txt = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (ttl.isEmpty() && txt.isEmpty()) return

        val key = sbn.key + ttl + txt
        val now = SystemClock.elapsedRealtime()
        if (key == lastNotifKey && now - lastNotifTime < 3000) return
        lastNotifKey = key
        lastNotifTime = now

        val pm = packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        var icon: Drawable? = try { n.getLargeIcon()?.loadDrawable(this) } catch (e: Exception) { null }
        if (icon == null) {
            icon = try { pm.getApplicationIcon(sbn.packageName) } catch (e: Exception) { null }
        }
        val pi = n.contentIntent
        handler.post { showNotif(icon, appName, ttl, txt, pi) }
    }

    // ------------------------------------------------------------------ state: dasar (idle / media compact)

    private fun showBase(force: Boolean = false) {
        val isl = island ?: return
        val prev = mode
        val nm = when {
            navActive -> MODE_NAV_COMPACT
            hasMedia && playing -> MODE_MEDIA_COMPACT
            else -> MODE_IDLE
        }
        mode = nm
        when (nm) {
            MODE_NAV_COMPACT -> {
                setVisible(true)
                if (!force && prev == MODE_NAV_COMPACT && nArt != null) {
                    bindNavCompact()
                    return
                }
                isl.morph(idleW() + dp(170), idleH(), navCompactView())
            }
            MODE_MEDIA_COMPACT -> {
                setVisible(true)
                if (!force && prev == MODE_MEDIA_COMPACT && cArt != null) {
                    bindCompact()
                    return
                }
                isl.morph(idleW() + dp(90), idleH(), compactMediaView())
            }
            else -> {
                setVisible(Prefs.getBool(this, Prefs.IDLE, true))
                if (!force && prev == MODE_IDLE) return
                isl.morph(idleW(), idleH(), null)
            }
        }
    }

    private fun collapseNow() {
        handler.removeCallbacks(collapseRun)
        stopProgress()
        showBase(true)
    }

    private fun scheduleCollapse(ms: Long) {
        handler.removeCallbacks(collapseRun)
        handler.postDelayed(collapseRun, ms)
    }

    private fun onIslandTap() {
        when (mode) {
            MODE_TRANSIENT -> {
                fire(transientAction)
                collapseNow()
            }
            MODE_MEDIA_EXPANDED -> {
                fire(media?.sessionActivity)
                collapseNow()
            }
            MODE_NAV_COMPACT -> showNavExpanded()
            MODE_NAV_EXPANDED -> {
                fire(navIntent)
                collapseNow()
            }
            else -> if (hasMedia) showMediaExpanded()
        }
    }

    private fun fire(pi: PendingIntent?) {
        if (pi == null) return
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = ActivityOptions.makeBasic()
                o.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                pi.send(this, 0, null, null, null, null, o.toBundle())
            } else {
                pi.send()
            }
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ state: sementara (notif / charging)

    private fun enterTransient() {
        mode = MODE_TRANSIENT
        setVisible(true)
        stopProgress()
        cArt = null
        cEq = null
        nArt = null
        nTitle = null
    }

    private fun showNotif(icon: Drawable?, app: String, ttl: String, txt: String, pi: PendingIntent?) {
        val isl = island ?: return
        enterTransient()
        transientAction = pi
        val h = if (txt.isBlank()) dp(72) else dp(94)
        isl.morph(expW(), h, notifView(icon, app, ttl, txt))
        scheduleCollapse(5000)
    }

    private fun showCharging() {
        val isl = island ?: return
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val sc = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (sc > 0) lvl * 100 / sc else lvl
        enterTransient()
        transientAction = null
        isl.morph(minOf(expW(), dp(300)), dp(64), chargingView(pct))
        scheduleCollapse(3500)
    }

    // ------------------------------------------------------------------ state: media expanded

    private fun showMediaExpanded() {
        val isl = island ?: return
        mode = MODE_MEDIA_EXPANDED
        setVisible(true)
        cArt = null
        cEq = null
        nArt = null
        nTitle = null
        isl.morph(expW(), dp(148), mediaExpandedView())
        bindExpanded()
        scheduleCollapse(7000)
        handler.removeCallbacks(progressRun)
        handler.post(progressRun)
    }

    private fun stopProgress() {
        handler.removeCallbacks(progressRun)
    }

    private fun updateProgress() {
        val bar = exProg ?: return
        val st = media?.playbackState
        if (media == null || st == null || duration <= 0L) {
            bar.fraction = if (demo) 0.35f else 0f
            return
        }
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        bar.fraction = (pos.toFloat() / duration).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ media session

    fun startMedia() {
        if (mediaHooked) return
        try {
            val cn = ComponentName(this, NotifListener::class.java)
            val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
            msm = m
            listenerCn = cn
            mediaHooked = true
            onSessions(m.getActiveSessions(cn))
        } catch (e: SecurityException) {
            // akses notifikasi belum diizinkan
        }
    }

    private fun onSessions(list: List<MediaController>?) {
        val all = list ?: emptyList()
        val pick = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull()
        val old = media
        if (old != null && pick != null && old.sessionToken == pick.sessionToken) {
            readMedia()
            return
        }
        if (old != null) {
            mediaCb?.let { cb -> try { old.unregisterCallback(cb) } catch (e: Exception) { } }
        }
        media = pick
        if (pick != null) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { readMedia() }
                override fun onMetadataChanged(metadata: MediaMetadata?) { readMedia() }
                override fun onSessionDestroyed() {
                    try { onSessions(msm?.getActiveSessions(listenerCn)) } catch (e: Exception) { }
                }
            }
            mediaCb = cb
            pick.registerCallback(cb, handler)
        }
        readMedia()
    }

    private fun readMedia() {
        val c = media
        if (c == null) {
            if (!demo) {
                hasMedia = false
                playing = false
            }
            afterMediaChange()
            return
        }
        demo = false
        val md = c.metadata
        val st = c.playbackState
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp !== art) {
            art = bmp
            accent = computeAccent(bmp)
        }
        duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        playing = st?.state == PlaybackState.STATE_PLAYING
        hasMedia = title.isNotEmpty()
        afterMediaChange()
    }

    private fun afterMediaChange() {
        when (mode) {
            MODE_MEDIA_EXPANDED -> bindExpanded()
            MODE_TRANSIENT, MODE_NAV_EXPANDED -> {}
            else -> showBase()
        }
    }

    private fun computeAccent(b: Bitmap?): Int {
        val def = 0xFFFF2D55.toInt()
        if (b == null) return def
        return try {
            val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
            val hsv = FloatArray(3)
            Color.colorToHSV(px, hsv)
            hsv[1] = maxOf(hsv[1], 0.5f)
            hsv[2] = maxOf(hsv[2], 0.85f)
            Color.HSVToColor(hsv)
        } catch (e: Exception) {
            def
        }
    }

    // ------------------------------------------------------------------ pembuat view

    private fun tv(t: String, sp: Float, color: Int, bold: Boolean = false, lines: Int = 1): TextView =
        TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }

    private fun artDrawable(): Drawable {
        val b = art
        return if (b != null) BitmapDrawable(resources, b)
        else GradientDrawable().apply { setColor(accent) }
    }

    private fun compactMediaView(): View {
        val f = FrameLayout(this)
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundOutline(dp(6).toFloat())
        }
        f.addView(
            iv,
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { leftMargin = dp(10) }
        )
        val eq = EqView(this)
        f.addView(
            eq,
            FrameLayout.LayoutParams(dp(18), dp(14), Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { rightMargin = dp(14) }
        )
        cArt = iv
        cEq = eq
        bindCompact()
        return f
    }

    private fun bindCompact() {
        cArt?.setImageDrawable(artDrawable())
        cEq?.color = accent
        cEq?.active = playing
    }

    private fun notifView(icon: Drawable?, app: String, ttl: String, txt: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundOutline(dp(11).toFloat())
            setImageDrawable(icon ?: GradientDrawable().apply { setColor(0xFF3A3A3C.toInt()) })
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        col.addView(tv(app, 11f, GRAY))
        col.addView(tv(ttl, 15f, Color.WHITE, true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        if (txt.isNotBlank()) {
            col.addView(tv(txt, 14f, 0xFFD1D1D6.toInt(), false, 2), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(1) })
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            .apply { leftMargin = dp(12) })
        return row
    }

    private fun chargingView(pct: Int): View {
        val green = Color.parseColor("#30D158")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val b = BatteryView(this)
        b.level = pct
        row.addView(b, LinearLayout.LayoutParams(dp(36), dp(18)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("Mengisi Daya", 15f, Color.WHITE, true))
        col.addView(tv("Terhubung ke charger", 12f, GRAY), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(12) })
        row.addView(tv("$pct%", 22f, green, true))
        return row
    }

    private fun mediaExpandedView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val a = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundOutline(dp(10).toFloat())
        }
        top.addView(a, LinearLayout.LayoutParams(dp(56), dp(56)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = tv("", 16f, Color.WHITE, true)
        val ar = tv("", 14f, GRAY)
        col.addView(t)
        col.addView(ar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(3) })
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(12); rightMargin = dp(8) })
        val eq = EqView(this)
        top.addView(eq, LinearLayout.LayoutParams(dp(24), dp(20)))
        root.addView(top)

        val prog = ProgressLine(this)
        root.addView(prog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
            .apply { topMargin = dp(12) })

        val ctr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = GlyphView(this, GlyphView.PREV)
        val play = GlyphView(this, GlyphView.PLAY)
        val next = GlyphView(this, GlyphView.NEXT)
        prev.setOnClickListener {
            media?.transportControls?.skipToPrevious()
            scheduleCollapse(7000)
        }
        next.setOnClickListener {
            media?.transportControls?.skipToNext()
            scheduleCollapse(7000)
        }
        play.setOnClickListener {
            val c = media
            if (c != null) {
                if (playing) c.transportControls.pause() else c.transportControls.play()
            } else {
                playing = !playing
                bindExpanded()
            }
            scheduleCollapse(7000)
        }
        ctr.addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(22) })
        ctr.addView(play, LinearLayout.LayoutParams(dp(50), dp(50)))
        ctr.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(22) })
        root.addView(ctr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        exTitle = t
        exArtist = ar
        exArt = a
        exPlay = play
        exEq = eq
        exProg = prog
        return root
    }

    // ------------------------------------------------------------------ navigasi (panah belokan Maps / Waze)

    fun onNotifRemoved(sbn: StatusBarNotification) {
        val k = sbn.key
        handler.postDelayed({
            // Maps kadang menghapus lalu memasang ulang notifikasi; tunggu sebentar
            if (navActive && navKey == k && SystemClock.elapsedRealtime() - navLastSeen >= 1400) {
                navActive = false
                navIcon = null
                if (mode == MODE_NAV_EXPANDED) collapseNow() else afterNavChange()
            }
        }, 1500)
    }

    private fun onNav(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras
        val ttl = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val txt = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        if (ttl.isEmpty() && txt.isEmpty()) return
        var icon: Drawable? = try { n.getLargeIcon()?.loadDrawable(this) } catch (e: Exception) { null }
        if (icon == null) {
            icon = try {
                n.smallIcon?.loadDrawable(this)?.mutate()?.apply { setTint(Color.WHITE) }
            } catch (e: Exception) {
                null
            }
        }
        navActive = true
        navKey = sbn.key
        navTitle = ttl
        navText = txt
        navSub = sub
        navIcon = icon ?: ArrowDrawable()
        navIntent = n.contentIntent
        navLastSeen = SystemClock.elapsedRealtime()
        afterNavChange()
    }

    private fun afterNavChange() {
        when (mode) {
            MODE_NAV_EXPANDED -> bindNavExpanded()
            MODE_TRANSIENT, MODE_MEDIA_EXPANDED -> {}
            else -> showBase()
        }
    }

    private fun navIconDrawable(): Drawable? = navIcon?.constantState?.newDrawable()?.mutate() ?: navIcon

    private fun showNavExpanded() {
        val isl = island ?: return
        mode = MODE_NAV_EXPANDED
        setVisible(true)
        stopProgress()
        cArt = null
        cEq = null
        nArt = null
        nTitle = null
        isl.morph(expW(), dp(96), navExpandedView())
        bindNavExpanded()
        scheduleCollapse(6000)
    }

    private fun navCompactView(): View {
        val f = FrameLayout(this)
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        f.addView(
            iv,
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { leftMargin = dp(10) }
        )
        val t = tv("", 12f, Color.WHITE, true).apply { gravity = Gravity.END }
        f.addView(
            t,
            FrameLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { rightMargin = dp(12) }
        )
        nArt = iv
        nTitle = t
        bindNavCompact()
        return f
    }

    private fun bindNavCompact() {
        nArt?.setImageDrawable(navIconDrawable())
        nTitle?.text = navTitle
    }

    private fun navExpandedView(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            outlineProvider = roundOutline(dp(12).toFloat())
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(60), dp(60)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val t = tv("", 21f, Color.WHITE, true)
        val s = tv("", 14f, 0xFFD1D1D6.toInt(), false, 2)
        val u = tv("", 12f, GRAY)
        col.addView(t)
        col.addView(s, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        col.addView(u, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(3) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            .apply { leftMargin = dp(14) })
        nxArt = iv
        nxTitle = t
        nxText = s
        nxSub = u
        return row
    }

    private fun bindNavExpanded() {
        nxArt?.setImageDrawable(navIconDrawable())
        nxTitle?.text = navTitle
        nxText?.text = navText
        nxText?.visibility = if (navText.isBlank()) View.GONE else View.VISIBLE
        nxSub?.text = navSub
        nxSub?.visibility = if (navSub.isBlank()) View.GONE else View.VISIBLE
    }

    private fun bindExpanded() {
        exTitle?.text = if (title.isEmpty()) "Tidak ada judul" else title
        exArtist?.text = artist
        exArt?.setImageDrawable(artDrawable())
        exPlay?.setGlyph(if (playing) GlyphView.PAUSE else GlyphView.PLAY)
        exEq?.color = accent
        exEq?.active = playing
        updateProgress()
    }
}
