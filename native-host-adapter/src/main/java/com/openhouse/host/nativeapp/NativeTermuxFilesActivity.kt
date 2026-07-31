package com.openhouse.host.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lightweight SAF file manager for the external Termux Home used by the native APK. */
class NativeTermuxFilesActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val breadcrumbs = mutableListOf<DocumentFile>()
    private lateinit var repository: NativeTermuxHomeRepository
    private lateinit var pathView: TextView
    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var upButton: Button
    private lateinit var newFolderButton: Button
    private lateinit var newFileButton: Button
    private lateinit var grantButton: Button
    private lateinit var adapter: FileListAdapter
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Termux 文件"
        repository = NativeTermuxHomeRepository(applicationContext)
        createUi()
    }

    override fun onResume() {
        super.onResume()
        loadRoot()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        activityScope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (breadcrumbs.size > 1) {
            breadcrumbs.removeAt(breadcrumbs.lastIndex)
            reloadCurrentDirectory()
        } else {
            super.onBackPressed()
        }
    }

    private fun createUi() {
        val padding = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        upButton = Button(this).apply {
            text = "↑"
            contentDescription = "返回上级目录"
            isEnabled = false
            setOnClickListener {
                if (breadcrumbs.size > 1) {
                    breadcrumbs.removeAt(breadcrumbs.lastIndex)
                    reloadCurrentDirectory()
                }
            }
        }
        pathView = TextView(this).apply {
            textSize = 16f
            setPadding(padding, 0, 0, 0)
        }
        navigation.addView(upButton, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        navigation.addView(pathView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(navigation)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        newFolderButton = Button(this).apply {
            text = "新建目录"
            setOnClickListener { promptCreateEntry(directory = true) }
        }
        newFileButton = Button(this).apply {
            text = "新建文本"
            setOnClickListener { promptCreateEntry(directory = false) }
        }
        actions.addView(newFolderButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(newFileButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        statusView = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }
        root.addView(statusView)

        grantButton = Button(this).apply {
            text = "授权 Termux Home"
            visibility = View.GONE
            setOnClickListener {
                startActivity(Intent(this@NativeTermuxFilesActivity, NativeTermuxHomeAccessActivity::class.java))
            }
        }
        root.addView(
            grantButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        adapter = FileListAdapter()
        listView = ListView(this).apply {
            this.adapter = this@NativeTermuxFilesActivity.adapter
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                this@NativeTermuxFilesActivity.adapter.getItem(position)?.let(::open)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                this@NativeTermuxFilesActivity.adapter.getItem(position)?.let(::showEntryActions)
                true
            }
        }
        emptyView = TextView(this).apply {
            text = "此目录为空"
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun loadRoot() {
        loadJob?.cancel()
        loadJob = activityScope.launch {
            setBusy(true, "正在读取 Termux Home…")
            try {
                val root = withContext(Dispatchers.IO) {
                    val uri = repository.persistedTreeUri() ?: return@withContext null
                    DocumentFile.fromTreeUri(applicationContext, uri)?.takeIf {
                        it.exists() && it.isDirectory && it.canRead()
                    }
                }
                if (root == null) {
                    showAuthorizationRequired("尚未授权 Termux Home，或已有授权已经失效。")
                    return@launch
                }
                breadcrumbs.clear()
                breadcrumbs += root
                reloadCurrentDirectory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showAuthorizationRequired(error.message ?: "无法读取 Termux Home。")
            }
        }
    }

    private fun reloadCurrentDirectory() {
        val directory = breadcrumbs.lastOrNull() ?: return
        loadJob?.cancel()
        loadJob = activityScope.launch {
            setBusy(true, "正在读取目录…")
            try {
                val entries = withContext(Dispatchers.IO) {
                    directory.listFiles()
                        .map { document ->
                            FileEntry(
                                document = document,
                                name = document.name ?: "(未命名)",
                                directory = document.isDirectory,
                                size = document.length(),
                            )
                        }
                        .sortedWith(
                            compareByDescending<FileEntry> { it.directory }
                                .thenBy { it.name.lowercase(Locale.ROOT) },
                        )
                }
                adapter.replace(entries)
                pathView.text = breadcrumbPath()
                upButton.isEnabled = breadcrumbs.size > 1
                showContent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error.message ?: "无法读取目录。")
                setBusy(false)
            }
        }
    }

    private fun open(entry: FileEntry) {
        if (entry.directory) {
            breadcrumbs += entry.document
            reloadCurrentDirectory()
        } else {
            openTextEditor(entry.document)
        }
    }

    private fun promptCreateEntry(directory: Boolean) {
        val input = nameInput(if (directory) "新目录" else "新文件.txt")
        AlertDialog.Builder(this)
            .setTitle(if (directory) "新建目录" else "新建文本文件")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ -> createEntry(input.text.toString(), directory) }
            .show()
    }

    private fun createEntry(name: String, directory: Boolean) {
        validateDocumentName(name)?.let {
            toast(it)
            return
        }
        val parent = breadcrumbs.lastOrNull() ?: return
        activityScope.launch {
            setBusy(true, "正在创建…")
            try {
                val created = withContext(Dispatchers.IO) {
                    check(parent.findFile(name) == null) { "同名文件或目录已经存在。" }
                    if (directory) parent.createDirectory(name) else parent.createFile("text/plain", name)
                } ?: error("创建失败，Termux 文件提供器拒绝了该操作。")
                toast("已创建 $name")
                reloadCurrentDirectory()
                if (!directory) openTextEditor(created)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error.message ?: "创建失败。")
                setBusy(false)
            }
        }
    }

    private fun openTextEditor(document: DocumentFile) {
        activityScope.launch {
            setBusy(true, "正在打开文本…")
            try {
                val text = withContext(Dispatchers.IO) {
                    val size = document.length()
                    if (isKnownFileTooLarge(size, MAX_EDIT_BYTES)) {
                        throw FileTooLargeException(MAX_EDIT_BYTES)
                    }
                    val input = requireNotNull(contentResolver.openInputStream(document.uri)) {
                        "无法打开文件。"
                    }
                    input.use { readUtf8TextLimited(it, MAX_EDIT_BYTES) }
                }
                setBusy(false)
                showTextEditor(document, text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error.message ?: "无法打开文本文件。")
                setBusy(false)
            }
        }
    }

    private fun showTextEditor(document: DocumentFile, text: String) {
        val editor = EditText(this).apply {
            setText(text)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 10
            maxLines = 18
            filters = arrayOf(InputFilter.LengthFilter(MAX_EDIT_CHARACTERS))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(document.name ?: "编辑文本")
            .setView(editor)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> saveText(document, editor.text.toString()) }
            .show()
    }

    private fun saveText(document: DocumentFile, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_EDIT_BYTES) {
            toast("文本超过 512 KiB，未保存。")
            return
        }
        activityScope.launch {
            setBusy(true, "正在保存…")
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(contentResolver.openOutputStream(document.uri, "w")) {
                        "无法打开文件进行写入。"
                    }.use { it.write(bytes) }
                }
                toast("已保存 ${document.name ?: "文件"}")
                reloadCurrentDirectory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error.message ?: "保存失败。")
                setBusy(false)
            }
        }
    }

    private fun showEntryActions(entry: FileEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(arrayOf("重命名", "删除")) { _, which ->
                if (which == 0) promptRename(entry) else confirmDelete(entry)
            }
            .show()
    }

    private fun promptRename(entry: FileEntry) {
        val input = nameInput(entry.name)
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> rename(entry, input.text.toString()) }
            .show()
    }

    private fun rename(entry: FileEntry, newName: String) {
        validateDocumentName(newName)?.let {
            toast(it)
            return
        }
        if (newName == entry.name) return
        val parent = breadcrumbs.lastOrNull() ?: return
        activityScope.launch {
            setBusy(true, "正在重命名…")
            try {
                withContext(Dispatchers.IO) {
                    check(parent.findFile(newName) == null) { "同名文件或目录已经存在。" }
                    check(entry.document.renameTo(newName)) { "Termux 文件提供器拒绝了重命名操作。" }
                }
                toast("已重命名为 $newName")
                reloadCurrentDirectory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error.message ?: "重命名失败。")
                setBusy(false)
            }
        }
    }

    private fun confirmDelete(entry: FileEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除 ${entry.name}？")
            .setMessage(if (entry.directory) "目录中的所有内容都会被递归删除。" else "该操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> delete(entry) }
            .show()
    }

    private fun delete(entry: FileEntry) {
        activityScope.launch {
            setBusy(true, "正在删除…")
            try {
                withContext(Dispatchers.IO) {
                    check(deleteDocumentRecursively(entry.document)) {
                        "Termux 文件提供器未能完整删除该项目。"
                    }
                }
                toast("已删除 ${entry.name}")
                reloadCurrentDirectory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error.message ?: "删除失败。")
                setBusy(false)
            }
        }
    }

    private fun nameInput(initialValue: String) = EditText(this).apply {
        setText(initialValue)
        setSelection(text.length)
        inputType = InputType.TYPE_CLASS_TEXT
        filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_CHARACTERS))
        setPadding(dp(24), dp(8), dp(24), dp(8))
    }

    private fun breadcrumbPath(): String = buildString {
        append("Termux Home")
        breadcrumbs.drop(1).forEach { document ->
            append(" / ")
            append(document.name ?: "?")
        }
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        listView.isEnabled = !busy
        newFolderButton.isEnabled = !busy && breadcrumbs.isNotEmpty()
        newFileButton.isEnabled = !busy && breadcrumbs.isNotEmpty()
        upButton.isEnabled = !busy && breadcrumbs.size > 1
        if (message != null) statusView.text = message
    }

    private fun showContent() {
        grantButton.visibility = View.GONE
        val isEmpty = adapter.isEmpty
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        listView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        statusView.text = "${adapter.count} 个项目"
        setBusy(false)
    }

    private fun showAuthorizationRequired(message: String) {
        breadcrumbs.clear()
        adapter.replace(emptyList())
        pathView.text = "Termux Home"
        statusView.text = message
        grantButton.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        listView.visibility = View.GONE
        setBusy(false)
    }

    private fun showError(message: String) {
        statusView.text = message
        toast(message)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class FileListAdapter : ArrayAdapter<FileEntry>(
        this@NativeTermuxFilesActivity,
        android.R.layout.simple_list_item_2,
        android.R.id.text1,
        mutableListOf(),
    ) {
        fun replace(entries: List<FileEntry>) {
            clear()
            addAll(entries)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val entry = requireNotNull(getItem(position))
            view.findViewById<TextView>(android.R.id.text1).text =
                (if (entry.directory) "▸  " else "") + entry.name
            view.findViewById<TextView>(android.R.id.text2).text = when {
                entry.directory -> "目录"
                entry.size < 0L -> "文件"
                else -> formatFileSize(entry.size)
            }
            return view
        }
    }

    private data class FileEntry(
        val document: DocumentFile,
        val name: String,
        val directory: Boolean,
        val size: Long,
    )

    companion object {
        internal const val MAX_EDIT_BYTES = 512 * 1024
        private const val MAX_EDIT_CHARACTERS = MAX_EDIT_BYTES
        private const val MAX_NAME_CHARACTERS = 255
    }
}

internal class FileTooLargeException(maxBytes: Int) :
    IllegalArgumentException("文件超过 ${maxBytes / 1024} KiB，不能在轻量编辑器中打开。")

internal fun readUtf8TextLimited(input: InputStream, maxBytes: Int): String {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) throw FileTooLargeException(maxBytes)
        output.write(buffer, 0, read)
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

internal fun isKnownFileTooLarge(length: Long, maxBytes: Int): Boolean =
    length > maxBytes

internal fun validateDocumentName(name: String): String? = when {
    name.isBlank() -> "名称不能为空。"
    name == "." || name == ".." -> "不能使用该名称。"
    '/' in name || '\u0000' in name -> "名称不能包含 / 或空字符。"
    else -> null
}

internal fun deleteDocumentRecursively(document: DocumentFile): Boolean {
    if (document.isDirectory) {
        document.listFiles().forEach { child ->
            if (!deleteDocumentRecursively(child)) return false
        }
    }
    return document.delete()
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
}
