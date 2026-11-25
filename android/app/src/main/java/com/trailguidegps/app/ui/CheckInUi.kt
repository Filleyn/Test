package com.trailguidegps.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailguidegps.app.AppViewModel
import com.trailguidegps.app.data.CheckIn
import com.trailguidegps.app.data.CheckInStore

/** Text your check-in contacts that you're OK, running late or out safely, with where you are. */
@Composable
fun CheckInDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fix by vm.tracker.fix.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    var contacts by remember { mutableStateOf(vm.checkIns.contacts()) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    fun save(list: List<CheckInStore.Contact>) { contacts = list; vm.checkIns.setContacts(list) }

    // Android's own contact picker: it hands back the one number chosen, with no contacts permission.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val c2 = CheckInStore.Contact(c.getString(0) ?: "", c.getString(1) ?: "")
                    if (c2.number.isNotBlank() && contacts.none { it.number == c2.number }) save(contacts + c2)
                }
            }
        }
    }

    val info = fix.let { f ->
        val fl = following
        CheckIn.Info(f?.lat, f?.lon, f?.accuracyM, f?.location?.time,
            fl?.route?.name, fl?.alongM, fl?.route?.stats?.distanceM,
            vm.checkIns.backBy, vm.checkIns.callBy)
    }
    fun send(kind: CheckIn.Kind) {
        val to = CheckIn.recipients(contacts.map { it.number }, android.os.Build.MANUFACTURER.equals("samsung", true))
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).putExtra("sms_body", CheckIn.message(kind, info))
        if (runCatching { context.startActivity(i) }.isFailure) vm.showMessage("No messaging app to send the check-in.")
        else if (kind == CheckIn.Kind.OUT) vm.tripFinished()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check in") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (contacts.isEmpty()) "Add the people who should hear from you. The text opens in your messaging app for you to send."
                    else "Sends to:", fontSize = 13.sp, color = TG.Muted, lineHeight = 17.sp)
                contacts.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name.ifBlank { c.number }, fontSize = 15.sp, color = TG.Ink)
                            if (c.name.isNotBlank()) Text(c.number, fontSize = 12.sp, color = TG.Muted)
                        }
                        IconButton(onClick = { save(contacts - c) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${c.name.ifBlank { c.number }}", tint = TG.Muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                if (adding) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TG.Accent, cursorColor = TG.Accent), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(number, { number = it }, label = { Text("Mobile number") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TG.Accent, cursorColor = TG.Accent), modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(enabled = number.any { it.isDigit() }, onClick = {
                            save(contacts + CheckInStore.Contact(name.trim(), number.trim())); name = ""; number = ""; adding = false
                        }) { Text("Add", color = TG.Accent) }
                        TextButton(onClick = { adding = false }) { Text("Cancel", color = TG.Muted) }
                    }
                } else Row {
                    TextButton(onClick = {
                        runCatching { pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
                            .onFailure { adding = true }
                    }) { Text("Pick from contacts", color = TG.Accent) }
                    TextButton(onClick = { adding = true }) { Text("Type a number", color = TG.Accent) }
                }
                Text(CheckIn.message(CheckIn.Kind.OK, info), fontSize = 12.sp, color = TG.Muted, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 8.dp))
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheckIn.Kind.entries.forEach { k ->
                        Button(onClick = { send(k) }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = if (k == CheckIn.Kind.LATE) TG.Warn else TG.Accent)) {
                            Text(k.label, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (vm.checkIns.backBy > System.currentTimeMillis())
                    Text("You'll get a reminder at your planned out time. \"Out safely\" turns it off.", fontSize = 12.sp, color = TG.Muted,
                        lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
                Text("Texts need a cell signal; some phones can text by satellite. Check yours before a trip.", fontSize = 12.sp, color = TG.Muted,
                    lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = TG.Accent) } },
    )
}
