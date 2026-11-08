package com.gabs.dynamicisland

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.roundToInt

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.screenWidth(): Int = resources.displayMetrics.widthPixels

fun roundOutline(r: Float): ViewOutlineProvider = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, r)
    }
}

object Prefs {
    const val W = "w"
    const val H = "h"
    const val Y = "y"
    const val IDLE = "idle"

    private fun sp(c: Context) = c.getSharedPreferences("island", Context.MODE_PRIVATE)
    fun getInt(c: Context, k: String, d: Int): Int = sp(c).getInt(k, d)
    fun putInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun getBool(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}

object IslandBus {
    @Volatile
    var service: IslandService? = null
}
