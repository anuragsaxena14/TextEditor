package com.anurag.simpleeditor

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.AEADBadTagException

class MainActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var matchCase: CheckBox
    private lateinit var matchInfo: TextView

    // ---- File state ----
    private var currentUri: Uri? = null
    private var fileName = DEFAULT_NAME
    private var dirty = false
    private var loading = false              // true while text is set programmatically
    private var afterSaveAction: (() -> Unit)? = null
    private var pickerOpen = false           // our own file picker is on screen

    // ---- Encryption state ----
    // The key exists only in memory while an encrypted file is open; wiped on lock.
    private var vaultKey: ByteArray? = null
    private var vaultSalt: ByteArray? = null
    private var vaultIterations = VaultCrypto.ITERATIONS
    private val isVault get() = vaultKey != null
    private var lockedUri: Uri? = null       // encrypted file waiting to be unlocked
    private val isLocked get() = lockedUri != null && !isVault
    private var pendingEncrypt: PendingEncrypt? = null
    private var unlockPromptShowing = false

    private class PendingEncrypt(val originalUri: Uri?, val originalName: String)

    // ---- Timers & clipboard ----
    private val handler = Handler(Looper.getMainLooper())
    private val idleLock = Runnable {
        if (pickerOpen) resetIdleTimer()
        else if (isVault) { lock(); toast("Locked after inactivity") }
    }
    private val clearClipboard = Runnable { wipeClipboard() }
    private lateinit var clipboard: ClipboardManager
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    private var lastSensitiveSet = 0L

    // ---- Search state ----
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
        // Block screenshots, screen recording and the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        editor = findViewById(R.id.editor)
        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        matchCase = findViewById(R.id.matchCase)
        matchInfo = findViewById(R.id.matchInfo)

        // Ask keyboards not to learn from what is typed here ("incognito" mode in Gboard etc.)
        editor.imeOptions = editor.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        searchInput.imeOptions = searchInput.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        // In encrypted files, hide Share / Translate / web-search from the selection menu.
        editor.customSelectionActionModeCallback = selectionFilter

        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.addPrimaryClipChangedListener(clipListener)

        editor.addTextChangedListener(object : SimpleWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (loading) return
                resetIdleTimer()
                if (!dirty) {
                    dirty = true
                    updateTitle()
                }
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

    override fun onResume() {
        super.onResume()
        if (isLocked && !unlockPromptShowing && !pickerOpen) unlock()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (home, app switch, screen off) locks an encrypted file.
        if (isVault && !pickerOpen) lock()
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacks(idleLock)
        // clearClipboard stays scheduled on purpose, so a copied secret is still wiped.
        wipeKey()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetIdleTimer()
        return super.dispatchKeyEvent(event)
    }

    // ---------- Saved state (never contains decrypted text) ----------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val lockTarget = if (isVault) currentUri else lockedUri
        if (lockTarget != null) {
            outState.putString(KEY_LOCKED_URI, lockTarget.toString())
            outState.putString(KEY_NAME, fileName)
        } else {
            outState.putString(KEY_URI, currentUri?.toString())
            outState.putString(KEY_NAME, fileName)
            outState.putBoolean(KEY_DIRTY, dirty)
        }
    }

    override fun onRestoreInstanceState(state: Bundle) {
        loading = true
        super.onRestoreInstanceState(state)
        loading = false
        val locked = state.getString(KEY_LOCKED_URI)
        fileName = state.getString(KEY_NAME) ?: DEFAULT_NAME
        if (locked != null) {
            lockedUri = Uri.parse(locked)
            currentUri = null
            dirty = false
            setEditorText("")
            showLockedUi()
        } else {
            currentUri = state.getString(KEY_URI)?.let(Uri::parse)
            dirty = state.getBoolean(KEY_DIRTY)
        }
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

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_lock).isVisible = isVault
        menu.findItem(R.id.action_unlock).isVisible = isLocked
        menu.findItem(R.id.action_find).isVisible = !isLocked
        menu.findItem(R.id.action_save).isVisible = !isLocked
        menu.findItem(R.id.action_save_as).isVisible = !isLocked
        menu.findItem(R.id.action_encrypt).isVisible = !isVault && !isLocked
        menu.findItem(R.id.action_change_password).isVisible = isVault
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_find -> showSearch()
            R.id.action_save -> save()
            R.id.action_save_as -> saveAs()
            R.id.action_open -> confirmDiscard { openPicker() }
            R.id.action_new -> confirmDiscard { newFile() }
            R.id.action_new_encrypted -> confirmDiscard { newFile(); startEncryptFlow(null) }
            R.id.action_encrypt -> startEncryptFlow(currentUri)
            R.id.action_lock -> lock()
            R.id.action_unlock -> unlock()
            R.id.action_change_password -> changePassword()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed && !isLocked) {
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

    // ---------- Opening & saving ----------

    private fun confirmDiscard(action: () -> Unit) {
        if (!dirty) {
            action(); return
        }
        secure(
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
        )
    }

    private fun newFile() {
        wipeKey()
        setEditorText("")
        currentUri = null
        lockedUri = null
        fileName = DEFAULT_NAME
        dirty = false
        showUnlockedUi()
        resetSearch()
        updateTitle()
    }

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        pickerOpen = true
        startActivityForResult(i, REQ_OPEN)
    }

    private fun saveAs() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (isVault) "application/octet-stream" else "text/plain"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        pickerOpen = true
        startActivityForResult(i, REQ_SAVE_AS)
    }

    private fun save() {
        if (isLocked) return
        val uri = currentUri
        if (uri == null) {
            saveAs(); return
        }
        if (writeTo(uri)) markSaved() else afterSaveAction = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pickerOpen = false
        resetIdleTimer()
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            afterSaveAction = null
            pendingEncrypt?.let { p ->
                // Encryption was cancelled: go back to the unencrypted document.
                pendingEncrypt = null
                wipeKey()
                fileName = p.originalName
                updateTitle()
                toast("Encryption cancelled")
            }
            return
        }
        when (requestCode) {
            REQ_OPEN -> {
                keepPermission(uri)
                loadFile(uri)
            }
            REQ_SAVE_AS -> {
                keepPermission(uri)
                val p = pendingEncrypt
                pendingEncrypt = null
                if (writeTo(uri)) {
                    currentUri = uri
                    fileName = queryName(uri) ?: fileName
                    markSaved()
                    if (p?.originalUri != null && p.originalUri != uri) {
                        offerDeleteOriginal(p.originalUri, p.originalName)
                    }
                } else {
                    afterSaveAction = null
                    if (p != null) {
                        wipeKey()
                        fileName = p.originalName
                        updateTitle()
                    }
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
        val data = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Cannot open file")
        } catch (e: Exception) {
            toast("Could not open file: ${e.message}")
            return
        }

        if (VaultCrypto.isEncrypted(data)) {
            openEncrypted(uri, data)
        } else {
            wipeKey()
            showLoaded(uri, data.toString(Charsets.UTF_8))
        }
    }

    private fun openEncrypted(uri: Uri, data: ByteArray) {
        val name = queryName(uri) ?: "file"
        promptPassword("Unlock $name") { pw ->
            runInBackground("Unlocking…", work = {
                try {
                    val h = VaultCrypto.readHeader(data)
                    val key = VaultCrypto.deriveKey(pw, h.salt, h.iterations)
                    try {
                        Triple(key, h, VaultCrypto.decrypt(data, key))
                    } catch (e: Exception) {
                        key.fill(0); throw e
                    }
                } finally {
                    pw.fill('\u0000')
                }
            }, done = { (key, h, plain) ->
                val text = String(plain, Charsets.UTF_8)
                plain.fill(0)
                wipeKey()
                setVault(key, h.salt, h.iterations)
                showLoaded(uri, text)
            }, failed = { e ->
                if (e is AEADBadTagException) {
                    toast("Wrong password, or the file is damaged")
                    openEncrypted(uri, data)          // ask again
                } else {
                    toast("Could not unlock: ${e.message}")
                }
            })
        }
    }

    private fun showLoaded(uri: Uri, text: String) {
        setEditorText(text)
        editor.setSelection(0)
        currentUri = uri
        lockedUri = null
        fileName = queryName(uri) ?: DEFAULT_NAME
        dirty = false
        showUnlockedUi()
        resetSearch()
        updateTitle()
        if (searchBar.visibility == View.VISIBLE) {
            runSearch()
            goToNearest()
        }
    }

    /**
     * Writes the editor content to [uri]. For encrypted files it:
     *  1. encrypts, 2. checks the result decrypts back to the same text,
     *  3. writes, 4. reads the file back and checks it matches byte for byte.
     */
    private fun writeTo(uri: Uri, quiet: Boolean = false): Boolean {
        var plain: ByteArray? = null
        return try {
            plain = editor.text.toString().toByteArray(Charsets.UTF_8)
            val key = vaultKey
            val salt = vaultSalt
            val bytes = if (key != null && salt != null) {
                val enc = VaultCrypto.encrypt(plain, key, salt, vaultIterations)
                val check = VaultCrypto.decrypt(enc, key)
                val same = check.contentEquals(plain)
                check.fill(0)
                if (!same) throw GeneralSecurityException("Encryption self-check failed; file not written")
                enc
            } else {
                plain
            }

            val out = contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Cannot write file")
            out.use { it.write(bytes) }

            if (key != null) {
                val back = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (back == null || !back.contentEquals(bytes)) {
                    throw IOException("The saved file could not be verified. Restore it from your backup if it won't open.")
                }
            }
            true
        } catch (e: Exception) {
            if (!quiet) {
                secure(
                    AlertDialog.Builder(this)
                        .setTitle("Save failed")
                        .setMessage(e.message ?: e.toString())
                        .setPositiveButton("Save elsewhere…") { _, _ -> saveAs() }
                        .setNegativeButton("OK", null)
                        .show()
                )
            }
            false
        } finally {
            plain?.fill(0)
        }
    }

    private fun markSaved() {
        dirty = false
        updateTitle()
        toast(if (isVault) "Saved (encrypted)" else "Saved")
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
            } catch (_: Exception) { /* temporary access only; fine */ }
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

    // ---------- Encryption flows ----------

    /** Encrypts the current text into a new .sev file. [originalUri] is offered for deletion afterwards. */
    private fun startEncryptFlow(originalUri: Uri?) {
        promptNewPassword("Set a password") { pw ->
            val salt = VaultCrypto.newSalt()
            runInBackground("Preparing encryption…", work = {
                try {
                    VaultCrypto.deriveKey(pw, salt, VaultCrypto.ITERATIONS)
                } finally {
                    pw.fill('\u0000')
                }
            }, done = { key ->
                val originalName = fileName
                wipeKey()
                setVault(key, salt, VaultCrypto.ITERATIONS)
                pendingEncrypt = PendingEncrypt(originalUri, originalName)
                if (!fileName.endsWith(".sev")) fileName = "$fileName.sev"
                updateTitle()
                saveAs()
            }, failed = { e -> toast("Encryption failed: ${e.message}") })
        }
    }

    private fun changePassword() {
        val salt = vaultSalt ?: return
        val iterations = vaultIterations
        promptPassword("Current password") { cur ->
            runInBackground("Checking…", work = {
                try {
                    VaultCrypto.deriveKey(cur, salt, iterations)
                } finally {
                    cur.fill('\u0000')
                }
            }, done = { k ->
                val ok = MessageDigest.isEqual(k, vaultKey ?: ByteArray(0))
                k.fill(0)
                if (!ok) {
                    toast("Wrong password")
                } else {
                    askNewPasswordAndReencrypt()
                }
            }, failed = { e -> toast("Error: ${e.message}") })
        }
    }

    private fun askNewPasswordAndReencrypt() {
        promptNewPassword("New password") { pw ->
            val newSalt = VaultCrypto.newSalt()
            runInBackground("Re-encrypting…", work = {
                try {
                    VaultCrypto.deriveKey(pw, newSalt, VaultCrypto.ITERATIONS)
                } finally {
                    pw.fill('\u0000')
                }
            }, done = { newKey ->
                val uri = currentUri
                if (!isVault || uri == null) {
                    newKey.fill(0)
                } else {
                    val oldKey = vaultKey
                    val oldSalt = vaultSalt
                    val oldIter = vaultIterations
                    vaultKey = newKey
                    vaultSalt = newSalt
                    vaultIterations = VaultCrypto.ITERATIONS
                    if (writeTo(uri)) {
                        oldKey?.fill(0)
                        dirty = false
                        updateTitle()
                        toast("Password changed")
                    } else {
                        newKey.fill(0)
                        vaultKey = oldKey
                        vaultSalt = oldSalt
                        vaultIterations = oldIter
                        toast("Password not changed")
                    }
                }
            }, failed = { e -> toast("Error: ${e.message}") })
        }
    }

    private fun offerDeleteOriginal(uri: Uri, name: String) {
        secure(
            AlertDialog.Builder(this)
                .setTitle("Delete the unencrypted original?")
                .setMessage("\"$name\" still exists on your phone without encryption. Delete it now?")
                .setPositiveButton("Delete") { _, _ ->
                    val ok = try {
                        DocumentsContract.deleteDocument(contentResolver, uri)
                    } catch (e: Exception) {
                        false
                    }
                    if (ok) {
                        toast("Original deleted")
                    } else {
                        secure(
                            AlertDialog.Builder(this)
                                .setMessage("Couldn't delete it from here. Please delete \"$name\" with your file manager, and empty its trash/bin if it has one.")
                                .setPositiveButton("OK", null)
                                .show()
                        )
                    }
                }
                .setNegativeButton("Keep", null)
                .show()
        )
    }

    // ---------- Lock / unlock ----------

    private fun setVault(key: ByteArray, salt: ByteArray, iterations: Int) {
        vaultKey = key
        vaultSalt = salt
        vaultIterations = iterations
        editor.isSaveEnabled = false     // decrypted text must never go into saved state
        resetIdleTimer()
    }

    private fun wipeKey() {
        vaultKey?.fill(0)
        vaultKey = null
        vaultSalt = null
        handler.removeCallbacks(idleLock)
        editor.isSaveEnabled = true
    }

    private fun lock() {
        if (!isVault) return
        val uri = currentUri
        if (dirty && uri != null) {
            if (!writeTo(uri, quiet = true)) toast("Couldn't save your last changes before locking")
        }
        lockedUri = uri
        wipeKey()
        hideSearch()
        searchInput.setText("")
        setEditorText("")
        currentUri = null
        dirty = false
        if (lockedUri != null) showLockedUi() else {
            fileName = DEFAULT_NAME
            showUnlockedUi()
        }
        updateTitle()
    }

    private fun unlock() {
        val uri = lockedUri ?: return
        loadFile(uri)
    }

    private fun showLockedUi() {
        editor.isEnabled = false
        editor.hint = "Locked. Tap ⋮ → Unlock."
    }

    private fun showUnlockedUi() {
        editor.isEnabled = true
        editor.hint = null
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(idleLock)
        if (isVault) handler.postDelayed(idleLock, IDLE_LOCK_MS)
    }

    // ---------- Clipboard protection ----------

    private fun onClipChanged() {
        if (!isVault || !hasWindowFocus()) return
        // Android 13+: mark the copied text as sensitive so its preview is hidden.
        if (Build.VERSION.SDK_INT >= 33 &&
            SystemClock.elapsedRealtime() - lastSensitiveSet > 1000
        ) {
            val clip = clipboard.primaryClip
            if (clip != null &&
                clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) != true
            ) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
                lastSensitiveSet = SystemClock.elapsedRealtime()
                clipboard.setPrimaryClip(clip)
            }
        }
        handler.removeCallbacks(clearClipboard)
        handler.postDelayed(clearClipboard, CLIPBOARD_CLEAR_MS)
    }

    private fun wipeClipboard() {
        try {
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) { }
    }

    /** In encrypted files, the selection menu offers only cut/copy/paste/select all. */
    private val selectionFilter = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            filterSelectionMenu(menu); return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            filterSelectionMenu(menu); return true
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    private fun filterSelectionMenu(menu: Menu) {
        if (!isVault) return
        val allowed = setOf(
            android.R.id.cut, android.R.id.copy, android.R.id.paste,
            android.R.id.pasteAsPlainText, android.R.id.selectAll
        )
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.itemId !in allowed) item.isVisible = false
        }
    }

    // ---------- Dialog helpers ----------

    private fun promptPassword(title: String, onOk: (CharArray) -> Unit) {
        val input = passwordField("Password")
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(padded(input))
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { unlockPromptShowing = false }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.isEmpty()) return@setOnClickListener
                val pw = input.takeChars()
                dlg.dismiss()
                onOk(pw)
            }
        }
        unlockPromptShowing = true
        secure(dlg)
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    private fun promptNewPassword(title: String, onOk: (CharArray) -> Unit) {
        val pw1 = passwordField("Password")
        val pw2 = passwordField("Repeat password")
        val note = TextView(this).apply {
            text = "At least $MIN_PASSWORD characters. A long passphrase is best.\n" +
                "If you forget it, the file CANNOT be recovered."
            textSize = 13f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pw1); addView(pw2); addView(note)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(padded(box))
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pw1.text.length < MIN_PASSWORD) {
                    pw1.error = "Use at least $MIN_PASSWORD characters"
                    return@setOnClickListener
                }
                val a = pw1.takeChars()
                val b = pw2.takeChars()
                val same = a.contentEquals(b)
                b.fill('\u0000')
                if (!same) {
                    a.fill('\u0000')
                    pw2.error = "Passwords don't match. Enter both again."
                    pw1.requestFocus()
                    return@setOnClickListener
                }
                dlg.dismiss()
                onOk(a)
            }
        }
        secure(dlg)
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        pw1.requestFocus()
    }

    private fun passwordField(hintText: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        hint = hintText
        isSaveEnabled = false
    }

    private fun padded(v: View): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(v)
        }
    }

    private fun secure(dlg: AlertDialog) {
        dlg.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Runs slow work (key derivation) off the main thread with a "please wait" dialog. */
    private fun <T> runInBackground(
        message: String,
        work: () -> T,
        done: (T) -> Unit,
        failed: (Throwable) -> Unit
    ) {
        val dlg = AlertDialog.Builder(this).setMessage(message).setCancelable(false).create()
        secure(dlg)
        dlg.show()
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dlg.dismiss()
                result.onSuccess(done).onFailure(failed)
            }
        }.start()
    }

    private fun EditText.takeChars(): CharArray {
        val e = text
        val out = CharArray(e.length)
        e.getChars(0, e.length, out, 0)
        e.clear()
        return out
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
        val mark = if (dirty) "• " else ""
        actionBar?.title = when {
            isLocked -> "🔒 Locked: $fileName"
            isVault -> "🔒 $mark$fileName"
            else -> "$mark$fileName"
        }
        invalidateOptionsMenu()
    }

    // ---------- Search ----------

    private fun showSearch() {
        if (isLocked) return
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
        private const val MIN_PASSWORD = 10
        private const val IDLE_LOCK_MS = 3 * 60 * 1000L
        private const val CLIPBOARD_CLEAR_MS = 30 * 1000L
        private const val DEFAULT_NAME = "Untitled.txt"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_DIRTY = "dirty"
        private const val KEY_LOCKED_URI = "locked_uri"
    }
}
