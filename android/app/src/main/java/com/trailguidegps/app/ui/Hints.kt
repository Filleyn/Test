package com.trailguidegps.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One-time hints, each shown until it's dismissed or the thing it points to has been used. */
object Hints {
    const val LONG_PRESS = "long_press"
    const val ROUTE_MORE = "route_more"

    private fun prefs(c: Context) = c.getSharedPreferences("hints", Context.MODE_PRIVATE)
    fun seen(c: Context, key: String) = prefs(c).getBoolean(key, false)
    fun markSeen(c: Context, key: String) = prefs(c).edit().putBoolean(key, true).apply()
    /** Shows every hint again (from Settings → Quick tips). */
    fun reset(c: Context) = prefs(c).edit().clear().apply()
}

/** A small tip line with a light bulb and "Got it". */
@Composable
fun HintRow(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(TG.Accent.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = TG.Accent, modifier = Modifier.padding(end = 8.dp))
        Text(text, fontSize = 13.sp, color = TG.Ink, lineHeight = 17.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        TextButton(onClick = onDismiss) { Text("Got it", color = TG.Accent, fontSize = 13.sp) }
    }
}
