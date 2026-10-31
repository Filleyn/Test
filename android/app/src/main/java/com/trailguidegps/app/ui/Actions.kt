package com.trailguidegps.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One button in an action grid: an icon over a short label. */
data class Action(val icon: ImageVector, val label: String, val tint: Color = TG.Accent, val onClick: () -> Unit)

/**
 * Icon buttons in even columns, wrapping onto more rows. Easier to scan than a row of
 * text links, and every button is a comfortable 48 dp or more to tap.
 */
@Composable
fun ActionGrid(actions: List<Action>, modifier: Modifier = Modifier, columns: Int = 4) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { a -> ActionCell(a, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ActionCell(a: Action, modifier: Modifier) {
    Column(
        modifier.heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = a.label, onClick = a.onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).background(a.tint.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(a.icon, contentDescription = null, tint = a.tint, modifier = Modifier.size(22.dp))
        }
        Text(a.label, fontSize = 11.5.sp, color = TG.Ink, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp))
    }
}

/** A small rounded button with an icon, for one or two secondary actions in a line. */
@Composable
fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 36.dp).border(1.dp, TG.Line, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TG.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = TG.Ink, maxLines = 1)
    }
}
