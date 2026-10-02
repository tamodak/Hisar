package org.tamodak.hisar.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.WebView
import org.tamodak.hisar.data.repository.FilterRepository

/**
 * A WebView whose keyboard input is checked before any page sees it.
 *
 * Every on-screen keyboard (Gboard, Samsung, SwiftKey, voice typing) talks to a text field through
 * an [InputConnection]. Chromium hands IME requests to the WebView's [onCreateInputConnection],
 * so wrapping the connection here sees each word as it is committed, and the Go/Search/Enter
 * that submits it, on every site, whatever the page does with the text afterwards (YouTube
 * sends its searches as binary protobuf no request filter can read).
 *
 * Words are judged once finished — committed, or submitted — never letter by letter: "analysis"
 * passes through "anal", Turkish "gayet" through "gay". A finished blocked word is removed from
 * the field before the page receives it; a submit with one is swallowed and the field cleared.
 *
 * The connection is used on Chromium's IME thread, so everything here only reads the (immutable)
 * filters and posts results back.
 */
@SuppressLint("ViewConstructor") // Created only by WebViewFactory, never inflated.
class GuardedWebView(
    context: Context,
    private val filters: FilterRepository,
    private val onBlocked: () -> Unit,
) : WebView(context) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return GuardedInputConnection(connection)
    }

    private inner class GuardedInputConnection(target: InputConnection) : InputConnectionWrapper(target, true) {
        /** The word the keyboard is composing, which the field text already contains. */
        private var composing: CharSequence = ""

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = text ?: ""
            return super.setComposingText(text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            // Finishing a composition commits it as typed: the same check as a commit.
            if (composing.isNotEmpty() && wouldBlock(composing)) return refuse()
            composing = ""
            return super.finishComposingText()
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text != null && wouldBlock(text)) return refuse()
            composing = ""
            return super.commitText(text, newCursorPosition)
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            if (fieldBlocked()) return clearField()
            return super.performEditorAction(editorAction)
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER || event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (enter && fieldBlocked()) {
                // Swallow both halves of the key press; clear once.
                return if (event?.action == KeyEvent.ACTION_DOWN) clearField() else true
            }
            return super.sendKeyEvent(event)
        }

        /** The field as it would read with [text] committed in place of the composing word. */
        private fun wouldBlock(text: CharSequence): Boolean {
            val guard = filters.guardOrNull ?: return false
            val before = getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString().orEmpty().removeSuffix(composing.toString())
            val after = getTextAfterCursor(CONTEXT_CHARS, 0)?.toString().orEmpty()
            return !guard.isClean(before + text + " " + after)
        }

        private fun fieldBlocked(): Boolean {
            val guard = filters.guardOrNull ?: return false
            val before = getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString().orEmpty()
            val after = getTextAfterCursor(CONTEXT_CHARS, 0)?.toString().orEmpty()
            return !guard.isClean("$before $after")
        }

        /** Drops the composing word: it never reaches the page. */
        private fun refuse(): Boolean {
            super.setComposingText("", 1)
            super.finishComposingText()
            composing = ""
            post(onBlocked)
            return true
        }

        /** Empties the field and swallows the submit. */
        private fun clearField(): Boolean {
            super.setComposingText("", 1)
            super.finishComposingText()
            composing = ""
            val before = getTextBeforeCursor(CONTEXT_CHARS, 0)?.length ?: 0
            val after = getTextAfterCursor(CONTEXT_CHARS, 0)?.length ?: 0
            super.deleteSurroundingText(before, after)
            post(onBlocked)
            return true
        }
    }

    private companion object {
        /** Enough of the field to see whole phrases around the cursor. */
        const val CONTEXT_CHARS = 2_000
    }
}
