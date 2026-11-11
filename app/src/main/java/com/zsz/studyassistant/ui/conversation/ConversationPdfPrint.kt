package com.zsz.studyassistant.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintJob
import android.print.PrintManager
import android.view.View
import android.webkit.WebView

/** The system owns PDF writing and destination selection; keep the dedicated WebView alive until finish. */
internal class ConversationPdfPrint(private val view: WebView, name: String, private val onFinished: () -> Unit) {
    private val delegate = view.createPrintDocumentAdapter(name)
    private var finished = false
    private var job: PrintJob? = null
    private val adapter = object : PrintDocumentAdapter() {
        override fun onStart() { if (!finished) delegate.onStart() }
        override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
            if (finished) callback.onLayoutCancelled()
            else delegate.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)
        }
        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
            if (finished) callback.onWriteCancelled()
            else delegate.onWrite(pages, destination, cancellationSignal, callback)
        }
        override fun onFinish() {
            if (finished) return
            finished = true
            try { delegate.onFinish() } finally { onFinished() }
        }
    }

    fun start(context: Context, name: String) {
        var current = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        check(current is Activity && !current.isFinishing && !current.isDestroyed)
        val manager = checkNotNull(current.getSystemService(PrintManager::class.java))
        // Drawing a WebView while its print adapter converts content is undefined.
        view.visibility = View.INVISIBLE
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "PDF", 300, 300))
            .setMinMargins(PrintAttributes.Margins(333, 333, 333, 333))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        job = checkNotNull(manager.print(name, adapter, attributes))
    }

    fun close() {
        if (finished) return
        finished = true
        try { job?.cancel() } finally { delegate.onFinish() }
    }
}
