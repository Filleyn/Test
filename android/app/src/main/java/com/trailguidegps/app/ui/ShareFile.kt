package com.trailguidegps.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Opens the share sheet for one file the app made (trip plan PDF, GPX, backup). */
object ShareFile {
    /**
     * The read permission travels with the attachment (ClipData), and is also granted
     * directly to every app that can take the file. Email apps often open the attachment
     * after the share sheet has closed, or from another part of the app, where the
     * share sheet's one-time permission doesn't reach; file apps like OneDrive read it
     * straight away, which is why those worked.
     */
    fun send(context: Context, uri: Uri, mime: String, title: String, subject: String? = null, text: String? = null) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        text?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
        send.clipData = ClipData.newUri(context.contentResolver, "file", uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY).forEach {
                runCatching { context.grantUriPermission(it.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
