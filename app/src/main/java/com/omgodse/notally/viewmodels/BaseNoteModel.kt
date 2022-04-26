package com.omgodse.notally.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import android.print.PostPDFGenerator
import android.text.Html
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.text.toHtml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDirections
import androidx.room.withTransaction
import com.omgodse.notally.R
import com.omgodse.notally.fragments.ArchivedDirections
import com.omgodse.notally.fragments.DeletedDirections
import com.omgodse.notally.helpers.Event
import com.omgodse.notally.miscellaneous.NavEvent
import com.omgodse.notally.miscellaneous.applySpans
import com.omgodse.notally.miscellaneous.getBody
import com.omgodse.notally.miscellaneous.getLocale
import com.omgodse.notally.room.*
import com.omgodse.notally.room.livedata.Content
import com.omgodse.notally.room.livedata.SearchResult
import com.omgodse.notally.xml.Backup
import com.omgodse.notally.xml.XMLTags
import com.omgodse.notally.xml.XMLUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class BaseNoteModel(private val app: Application) : AndroidViewModel(app) {

    private val database = NotallyDatabase.getDatabase(app)
    private val labelDao = database.labelDao
    private val commonDao = database.commonDao
    private val baseNoteDao = database.baseNoteDao

    private val labelCache = HashMap<String, Content>()
    val formatter = getDateFormatter(app.getLocale())

    private val _navigation = MutableLiveData<Event<NavEvent>>()
    val navigation: LiveData<Event<NavEvent>> get() = _navigation

    var currentFile: File? = null

    val labels = labelDao.getAll()
    val baseNotes = Content(baseNoteDao.getAllFromFolder(Folder.NOTES), ::transform)
    val deletedNotes = Content(baseNoteDao.getAllFromFolder(Folder.DELETED), ::transform)
    val archivedNotes = Content(baseNoteDao.getAllFromFolder(Folder.ARCHIVED), ::transform)

    var keyword = String()
        set(value) {
            if (field != value) {
                field = value
                searchResults.fetch(value)
            }
        }

    val searchResults = SearchResult(viewModelScope, baseNoteDao, ::transform)

    private val pinned = Header(app.getString(R.string.pinned))
    private val others = Header(app.getString(R.string.others))

    init {
        viewModelScope.launch {
            val previousNotes = getPreviousNotes()
            val previousLabels = getPreviousLabels()
            val delete: (file: File) -> Unit = { file: File -> file.delete() }
            if (previousNotes.isNotEmpty() || previousLabels.isNotEmpty()) {
                database.withTransaction {
                    labelDao.insert(previousLabels)
                    baseNoteDao.insert(previousNotes)
                    getNotePath().listFiles()?.forEach(delete)
                    getDeletedPath().listFiles()?.forEach(delete)
                    getArchivedPath().listFiles()?.forEach(delete)
                    getLabelsPreferences().edit(true) { clear() }
                }
            }
        }
    }

    private fun navigateTo(navDirections: NavDirections) {
        _navigation.postValue(Event(NavEvent.ToDirection(navDirections)))
    }

    private fun navigateBack() {
        _navigation.postValue(Event(NavEvent.Back))
    }

    private fun navigateToNotes(direction: NavDirections) {
        navigateTo(direction)
    }

    fun getNotesByLabel(label: String): Content {
        if (labelCache[label] == null) {
            labelCache[label] = Content(baseNoteDao.getBaseNotesByLabel(label), ::transform)
        }
        return requireNotNull(labelCache[label])
    }


    private fun transform(list: List<BaseNote>): List<Item> {
        if (list.isEmpty()) {
            return list
        } else {
            val firstNote = list[0]
            return if (firstNote.pinned) {
                val newList = ArrayList<Item>(list.size + 2)
                newList.add(pinned)

                val indexFirstUnpinnedNote = list.indexOfFirst { baseNote -> !baseNote.pinned }
                list.forEachIndexed { index, baseNote ->
                    if (index == indexFirstUnpinnedNote) {
                        newList.add(others)
                    }
                    newList.add(baseNote)
                }
                newList
            } else list
        }
    }


    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val labels = labelDao.getListOfAll().toHashSet()
                val baseNotes = baseNoteDao.getListOfAllFromFolder(Folder.NOTES)
                val deletedNotes = baseNoteDao.getListOfAllFromFolder(Folder.DELETED)
                val archivedNotes = baseNoteDao.getListOfAllFromFolder(Folder.ARCHIVED)

                val backup = Backup(baseNotes, deletedNotes, archivedNotes, labels)

                (app.contentResolver.openOutputStream(uri) as? FileOutputStream)?.use { stream ->
                    stream.channel.truncate(0)
                    XMLUtils.writeBackupToStream(backup, stream)
                }
            }
            Toast.makeText(app, R.string.saved_to_device, Toast.LENGTH_LONG).show()
        }
    }

    fun importBackup(uri: Uri) {
        executeAsync {
            app.contentResolver.openInputStream(uri)?.use { stream ->
                val backup = XMLUtils.readBackupFromStream(stream)

                val list = ArrayList(backup.baseNotes)
                list.addAll(backup.deletedNotes)
                list.addAll(backup.archivedNotes)

                val labels = backup.labels.map { label -> Label(label) }

                baseNoteDao.insert(list)
                labelDao.insert(labels)
            }
        }
    }

    fun writeCurrentFileToUri(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                (app.contentResolver.openOutputStream(uri) as? FileOutputStream)?.use { stream ->
                    stream.channel.truncate(0)
                    stream.write(requireNotNull(currentFile).readBytes())
                }
            }
            Toast.makeText(app, R.string.saved_to_device, Toast.LENGTH_LONG).show()
        }
    }


    suspend fun getXMLFile(baseNote: BaseNote) = withContext(Dispatchers.IO) {
        val fileName = getFileName(baseNote)
        val file = File(getExportedPath(), "$fileName.xml")
        val outputStream = FileOutputStream(file)
        XMLUtils.writeBaseNoteToStream(baseNote, outputStream)
        outputStream.close()
        file
    }

    suspend fun getJSONFile(baseNote: BaseNote) = withContext(Dispatchers.IO) {
        val fileName = getFileName(baseNote)
        val file = File(getExportedPath(), "$fileName.json")
        val json = getJSON(baseNote)
        file.writeText(json)
        file
    }

    suspend fun getTXTFile(baseNote: BaseNote, showDateCreated: Boolean) = withContext(Dispatchers.IO) {
        val fileName = getFileName(baseNote)
        val file = File(getExportedPath(), "$fileName.txt")
        val content = getTXT(baseNote, showDateCreated)
        file.writeText(content)
        file
    }

    suspend fun getHTMLFile(baseNote: BaseNote, showDateCreated: Boolean) = withContext(Dispatchers.IO) {
        val fileName = getFileName(baseNote)
        val file = File(getExportedPath(), "$fileName.html")
        val content = getHTML(baseNote, showDateCreated)
        file.writeText(content)
        file
    }

    fun getPDFFile(baseNote: BaseNote, showDateCreated: Boolean, onResult: PostPDFGenerator.OnResult) {
        val fileName = getFileName(baseNote)
        val pdfFile = File(getExportedPath(), "$fileName.pdf")

        val html = getHTML(baseNote, showDateCreated)

        PostPDFGenerator.create(pdfFile, html, app, onResult)
    }


    fun pinBaseNote(id: Long) = executeAsync { baseNoteDao.updatePinned(id, true) }

    fun unpinBaseNote(id: Long) = executeAsync { baseNoteDao.updatePinned(id, false) }


    fun restoreBaseNote(id: Long, folder: Folder) = executeAsync {
        baseNoteDao.move(id, Folder.NOTES)
        when (folder) {
            Folder.DELETED -> {
                navigateToNotes(DeletedDirections.actionDeletedToNotes())
            }
            Folder.ARCHIVED -> {
                navigateToNotes(ArchivedDirections.actionArchivedToNotes())
            }
        }
    }

    fun moveBaseNoteToDeleted(id: Long) = executeAsync { baseNoteDao.move(id, Folder.DELETED) }

    fun moveBaseNoteToArchive(id: Long) = executeAsync { baseNoteDao.move(id, Folder.ARCHIVED) }

    fun deleteAllBaseNotes() = executeAsync { baseNoteDao.deleteAllFromFolder(Folder.DELETED) }

    fun deleteBaseNoteForever(baseNote: BaseNote) = executeAsync { baseNoteDao.delete(baseNote) }

    fun updateBaseNoteLabels(labels: HashSet<String>, id: Long) =
        executeAsync { baseNoteDao.updateLabels(id, labels) }


    suspend fun getAllLabelsAsList() = withContext(Dispatchers.IO) { labelDao.getListOfAll() }

    fun deleteLabel(value: String) = executeAsync { commonDao.deleteLabel(value) }

    fun insertLabel(label: Label, onComplete: (success: Boolean) -> Unit) =
        executeAsyncWithCallback({ labelDao.insert(label) }, onComplete)

    fun updateLabel(oldValue: String, newValue: String, onComplete: (success: Boolean) -> Unit) =
        executeAsyncWithCallback({ commonDao.updateLabel(oldValue, newValue) }, onComplete)


    private fun getExportedPath(): File {
        val filePath = File(app.cacheDir, "exported")
        if (!filePath.exists()) {
            filePath.mkdir()
        }
        filePath.listFiles()?.forEach { file -> file.delete() }
        return filePath
    }

    private fun getFileName(baseNote: BaseNote): String {
        val title = baseNote.title
        val body = when (baseNote.type) {
            Type.NOTE -> baseNote.body
            Type.LIST -> baseNote.items.getBody()
            Type.PHONE_NUMBER -> baseNote.body
        }
        val fileName = if (title.isEmpty()) {
            val words = body.split(" ").take(2)
            buildString {
                words.forEach {
                    append(it)
                    append(" ")
                }
            }
        } else title
        return fileName.take(64).replace("/", "")
    }


    private fun getJSON(baseNote: BaseNote): String {
        val labels = JSONArray(baseNote.labels)

        val jsonObject = JSONObject()
            .put("type", baseNote.type.name)
            .put(XMLTags.Title, baseNote.title)
            .put(XMLTags.Pinned, baseNote.pinned)
            .put(XMLTags.DateCreated, baseNote.timestamp)
            .put("labels", labels)

        when (baseNote.type) {
            Type.NOTE -> {
                val spans = JSONArray(baseNote.spans.map { representation -> representation.toJSONObject() })
                jsonObject.put(XMLTags.Body, baseNote.body)
                jsonObject.put("spans", spans)
            }
            Type.LIST -> {
                val items = JSONArray(baseNote.items.map { item -> item.toJSONObject() })
                jsonObject.put("items", items)
            }
        }

        return jsonObject.toString(2)
    }

    private fun getTXT(baseNote: BaseNote, showDateCreated: Boolean) = buildString {
        val date = formatter.format(baseNote.timestamp)

        val body = when (baseNote.type) {
            Type.NOTE -> baseNote.body
            Type.LIST -> baseNote.items.getBody()
            Type.PHONE_NUMBER -> baseNote.body
        }

        if (baseNote.title.isNotEmpty()) {
            append("${baseNote.title}\n\n")
        }
        if (showDateCreated) {
            append("$date\n\n")
        }
        append(body)
    }

    private fun getHTML(baseNote: BaseNote, showDateCreated: Boolean) = buildString {
        val date = formatter.format(baseNote.timestamp)
        val title = Html.escapeHtml(baseNote.title)

        append("<!DOCTYPE html>")
        append("<html><head>")
        append("<meta charset=\"UTF-8\"><title>$title</title>")
        append("</head><body>")
        append("<h2>$title</h2>")

        if (showDateCreated) {
            append("<p>$date</p>")
        }

        when (baseNote.type) {
            Type.NOTE -> {
                val body = baseNote.body.applySpans(baseNote.spans).toHtml()
                append(body)
            }
            Type.LIST -> {
                append("<ol>")
                baseNote.items.forEach { (body) ->
                    append("<li>${Html.escapeHtml(body)}</li>")
                }
                append("</ol>")
            }
        }
        append("</body></html>")
    }


    private fun getPreviousNotes(): List<BaseNote> {
        val previousNotes = ArrayList<BaseNote>()
        getNotePath().listFiles()?.mapTo(previousNotes, { file -> XMLUtils.readBaseNoteFromFile(file, Folder.NOTES) })
        getDeletedPath().listFiles()?.mapTo(previousNotes, { file -> XMLUtils.readBaseNoteFromFile(file, Folder.DELETED) })
        getArchivedPath().listFiles()?.mapTo(previousNotes, { file -> XMLUtils.readBaseNoteFromFile(file, Folder.ARCHIVED) })
        return previousNotes
    }

    private fun getPreviousLabels(): List<Label> {
        val labels = getLabelsPreferences().getStringSet("labelItems", emptySet()) ?: emptySet()
        return labels.map { value -> Label(value) }
    }


    private fun getNotePath() = getFolder("notes")

    private fun getDeletedPath() = getFolder("deleted")

    private fun getArchivedPath() = getFolder("archived")

    private fun getFolder(name: String): File {
        val folder = File(app.filesDir, name)
        if (!folder.exists()) {
            folder.mkdir()
        }
        return folder
    }

    private fun getLabelsPreferences() = app.getSharedPreferences("labelsPreferences", Context.MODE_PRIVATE)


    private fun executeAsync(function: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { function() }
        }
    }

    companion object {

        fun getDateFormatter(locale: Locale): SimpleDateFormat {
            val pattern = when (locale.language) {
                Locale.CHINESE.language,
                Locale.JAPANESE.language -> "yyyy年 MMM d日 (EEE)"
                else -> "EEE d MMM yyyy"
            }
            return SimpleDateFormat(pattern, locale)
        }
    }
}