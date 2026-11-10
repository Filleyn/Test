package com.trailguidegps.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the share sheet for one file the app made (trip plan PDF, GPX, backup). */
object ShareFile {
    /**
     * Only the app you pick in the share sheet gets to read the file: the read permission
     * travels with the attachment (the clip and the flag), and isn't handed to anything else.
     */
    fun send(context: Context, uri: Uri, mime: String, title: String, subject: String? = null, text: String? = null) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        text?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
        send.clipData = ClipData.newUri(context.contentResolver, "file", uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
