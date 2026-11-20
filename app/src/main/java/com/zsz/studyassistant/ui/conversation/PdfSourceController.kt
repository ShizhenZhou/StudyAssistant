package com.zsz.studyassistant.ui

import androidx.compose.runtime.*
import com.zsz.studyassistant.data.ConversationDocument
import com.zsz.studyassistant.data.PdfSourceSession

/** Screen-owned lookup state; closing the dialog retains only the bounded page text cache. */
internal class PdfSourceController {
    internal data class Request(val text: String, val documents: List<ConversationDocument>)
    internal val session = PdfSourceSession()
    internal var request by mutableStateOf<Request?>(null)
        private set

    fun find(text: String, documents: List<ConversationDocument>) {
        request = Request(text, documents.toList())
    }

    fun dismiss() { request = null }
}

@Composable
internal fun rememberPdfSourceController(conversationKey: String?): PdfSourceController {
    val controller = remember(conversationKey) { PdfSourceController() }
    DisposableEffect(controller) {
        onDispose { controller.session.dispose() }
    }
    // A conversation change tears down the old lookup/preview effects immediately.
    key(controller) {
        controller.request?.let { request ->
            PdfSourceSearchDialog(request.text, request.documents, controller.session, controller::dismiss)
        }
    }
    return controller
}
