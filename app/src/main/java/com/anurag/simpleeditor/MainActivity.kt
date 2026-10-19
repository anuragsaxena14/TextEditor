package com.anurag.simpleeditor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

class MainActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var matchCase: CheckBox
    private lateinit var matchInfo: TextView

    // File state
    private var currentUri: Uri? = null
    private var fileName = DEFAULT_NAME
    private var dirty = false
    private var loading = false          // true while we set text programmatically
    private var afterSaveAction: (() -> Unit)? = null

    // Search state
    private var matches: List<Int> = emptyList()
    private var matchIndex = -1
    private var queryLength = 0
    private var searchStale = true
    private val highlightSpans = mutableListOf<BackgroundColorSpan>()
    private var currentSpan: BackgroundColorSpan? = null
    private val highlightColor = Color.parseColor("#66FFEB3B")
    private val currentColor = Color.parseColor("#FFFF9800")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editor = findViewById(R.id.editor)
        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        matchCase = findViewById(R.id.matchCase)
        matchInfo = findViewById(R.id.matchInfo)

        editor.addTextChangedListener(object : SimpleWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (loading) return
                if (!dirty) {
                    dirty = true
                    updateTitle()
                }
                // Text changed, so old match positions are no longer valid
                if (!searchStale) {
                    clearHighlights()
                    searchStale = true
                    matchInfo.text = ""
                }
            }
        })

        searchInput.addTextChangedListener(object : SimpleWatcher() {
            override fun afterTextChanged(s: Editable?) {
                runSearch()
                goToNearest()
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                findNext(); true
            } else false
        }
        matchCase.setOnCheckedChangeListener { _, _ ->
            runSearch()
            goToNearest()
        }
        findViewById<ImageButton>(R.id.nextBtn).setOnClickListener { findNext() }
        findViewById<ImageButton>(R.id.prevBtn).setOnClickListener { findPrev() }
        findViewById<ImageButton>(R.id.closeBtn).setOnClickListener { hideSearch() }

        if (savedInstanceState == null) handleIncomingIntent(intent)
        updateTitle()
    }

    // ---------- State across recreation (e.g. dark-mode switch) ----------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_URI, currentUri?.toString())
        outState.putString(KEY_NAME, fileName)
        outState.putBoolean(KEY_DIRTY, dirty)
    }

    override fun onRestoreInstanceState(state: Bundle) {
        loading = true
        super.onRestoreInstanceState(state)   // EditText restores its own text here
        loading = false
        currentUri = state.getString(KEY_URI)?.let(Uri::parse)
        fileName = state.getString(KEY_NAME) ?: DEFAULT_NAME
        dirty = state.getBoolean(KEY_DIRTY)
        updateTitle()
    }

    private fun handleIncomingIntent(i: Intent?) {
        val uri = i?.data ?: return
        if (i.action == Intent.ACTION_VIEW || i.action == Intent.ACTION_EDIT) loadFile(uri)
    }

    // ---------- Menu & shortcuts ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_find -> showSearch()
            R.id.action_save -> save()
            R.id.action_save_as -> saveAs()
            R.id.action_open -> confirmDiscard { openPicker() }
            R.id.action_new -> confirmDiscard { newFile() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // Ctrl+F / Ctrl+S / Ctrl+O with a hardware keyboard
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_F -> { showSearch(); return true }
                KeyEvent.KEYCODE_S -> { save(); return true }
                KeyEvent.KEYCODE_O -> { confirmDiscard { openPicker() }; return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (searchBar.visibility == View.VISIBLE) {
            hideSearch()
            return
        }
        confirmDiscard { finish() }
    }

    // ---------- File operations ----------

    private fun confirmDiscard(action: () -> Unit) {
        if (!dirty) {
            action(); return
        }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save changes to $fileName?")
            .setPositiveButton("Save") { _, _ ->
                afterSaveAction = action
                save()
            }
            .setNegativeButton("Discard") { _, _ -> action() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun newFile() {
        setEditorText("")
        currentUri = null
        fileName = DEFAULT_NAME
        dirty = false
        resetSearch()
        updateTitle()
    }

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"   // allow any file; .log, .ini etc. often have odd MIME types
        }
        startActivityForResult(i, REQ_OPEN)
    }

    private fun saveAs() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        startActivityForResult(i, REQ_SAVE_AS)
    }

    private fun save() {
        val uri = currentUri
        if (uri == null) {
            saveAs(); return
        }
        if (writeTo(uri)) {
            markSaved()
        } else {
            toast("Can't write to this file. Choose where to save it.")
            saveAs()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            afterSaveAction = null   // user cancelled
            return
        }
        when (requestCode) {
            REQ_OPEN -> {
                keepPermission(uri)
                loadFile(uri)
            }
            REQ_SAVE_AS -> {
                keepPermission(uri)
                if (writeTo(uri)) {
                    currentUri = uri
                    fileName = queryName(uri) ?: fileName
                    markSaved()
                } else {
                    afterSaveAction = null
                }
            }
        }
    }

    private fun loadFile(uri: Uri) {
        val size = querySize(uri)
        if (size != null && size > MAX_BYTES) {
            toast("File is too large (limit is 2 MB)")
            return
        }
        try {
            val text = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw IOException("Cannot open file")

            setEditorText(text)
            editor.setSelection(0)
            currentUri = uri
            fileName = queryName(uri) ?: DEFAULT_NAME
            dirty = false
            resetSearch()
            updateTitle()
            if (searchBar.visibility == View.VISIBLE) {
                runSearch()
                goToNearest()
            }
        } catch (e: Exception) {
            toast("Could not open file: ${e.message}")
        }
    }

    private fun writeTo(uri: Uri): Boolean {
        return try {
            val bytes = editor.text.toString().toByteArray(Charsets.UTF_8)
            // "wt" = write + truncate, so old content beyond the new end is removed
            val out = contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Cannot write file")
            out.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            toast("Save failed: ${e.message}")
            false
        }
    }

    private fun markSaved() {
        dirty = false
        updateTitle()
        toast("Saved")
        val next = afterSaveAction
        afterSaveAction = null
        next?.invoke()
    }

    private fun keepPermission(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, rw)
        } catch (e: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { /* not persistable; fine for this session */ }
        }
    }

    private fun queryName(uri: Uri): String? {
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        return name ?: uri.lastPathSegment
    }

    private fun querySize(uri: Uri): Long? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun setEditorText(text: String) {
        loading = true
        try {
            editor.setText(text)
        } finally {
            loading = false
        }
    }

    private fun updateTitle() {
        actionBar?.title = (if (dirty) "• " else "") + fileName
    }

    // ---------- Search ----------

    private fun showSearch() {
        searchBar.visibility = View.VISIBLE
        searchInput.requestFocus()
        searchInput.selectAll()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        if (searchInput.text.isNotEmpty()) {
            runSearch()
            goToNearest()
        }
    }

    private fun hideSearch() {
        searchBar.visibility = View.GONE
        resetSearch()
        editor.requestFocus()
    }

    private fun resetSearch() {
        clearHighlights()
        matches = emptyList()
        matchIndex = -1
        searchStale = true
        matchInfo.text = ""
    }

    /** Finds every occurrence of the query and highlights them. */
    private fun runSearch() {
        clearHighlights()
        matches = emptyList()
        matchIndex = -1
        searchStale = false

        val query = searchInput.text.toString()
        queryLength = query.length
        if (query.isEmpty()) {
            matchInfo.text = ""
            return
        }

        val text = editor.text.toString()
        val ignoreCase = !matchCase.isChecked
        val found = ArrayList<Int>()
        var i = text.indexOf(query, 0, ignoreCase)
        while (i >= 0) {
            found.add(i)
            i = text.indexOf(query, i + query.length, ignoreCase)
        }
        matches = found

        val spannable: Spannable = editor.text
        for (start in found.take(MAX_HIGHLIGHTS)) {
            val span = BackgroundColorSpan(highlightColor)
            spannable.setSpan(span, start, start + queryLength, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            highlightSpans.add(span)
        }
        updateMatchInfo()
    }

    /** Jumps to the first match at or after the cursor. */
    private fun goToNearest() {
        if (matches.isEmpty()) {
            updateMatchInfo(); return
        }
        val cursor = editor.selectionStart.coerceAtLeast(0)
        val idx = matches.indexOfFirst { it >= cursor }
        selectMatch(if (idx >= 0) idx else 0)
    }

    private fun findNext() {
        if (searchStale) {
            runSearch()
            if (matches.isEmpty()) return
            val cursor = editor.selectionEnd.coerceAtLeast(0)
            val idx = matches.indexOfFirst { it >= cursor }
            selectMatch(if (idx >= 0) idx else 0)
            return
        }
        if (matches.isEmpty()) return
        selectMatch((matchIndex + 1) % matches.size)
    }

    private fun findPrev() {
        if (searchStale) {
            runSearch()
            if (matches.isEmpty()) return
            val cursor = editor.selectionStart.coerceAtLeast(0)
            val idx = matches.indexOfLast { it < cursor }
            selectMatch(if (idx >= 0) idx else matches.size - 1)
            return
        }
        if (matches.isEmpty()) return
        selectMatch(if (matchIndex <= 0) matches.size - 1 else matchIndex - 1)
    }

    private fun selectMatch(index: Int) {
        matchIndex = index
        val start = matches[index]
        val end = start + queryLength
        val text = editor.text

        currentSpan?.let { text.removeSpan(it) }
        val span = BackgroundColorSpan(currentColor)
        text.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        currentSpan = span

        editor.setSelection(start, end)
        editor.post { editor.bringPointIntoView(start) }
        updateMatchInfo()
    }

    private fun updateMatchInfo() {
        matchInfo.text = when {
            searchInput.text.isEmpty() -> ""
            matches.isEmpty() -> "0/0"
            else -> "${matchIndex + 1}/${matches.size}"
        }
    }

    private fun clearHighlights() {
        val text = editor.text
        highlightSpans.forEach { text.removeSpan(it) }
        highlightSpans.clear()
        currentSpan?.let { text.removeSpan(it) }
        currentSpan = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private abstract class SimpleWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    companion object {
        private const val REQ_OPEN = 1
        private const val REQ_SAVE_AS = 2
        private const val MAX_BYTES = 2L * 1024 * 1024
        private const val MAX_HIGHLIGHTS = 1000
        private const val DEFAULT_NAME = "Untitled.txt"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_DIRTY = "dirty"
    }
}
