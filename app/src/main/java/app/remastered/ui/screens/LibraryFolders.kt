package app.remastered.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remastered.core.LibraryEntry
import app.remastered.core.LibraryFolder
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.Route
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.CollectionRow
import app.remastered.ui.components.EmptyMessage
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.Loading
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.SubText
import app.remastered.ui.components.TitleText
import app.remastered.ui.components.dotted
import app.remastered.ui.player.MenuItem
import app.remastered.ui.player.Sheet
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type

/** The Library's menus and dialogs; one is open at a time. */
internal sealed interface LibrarySheet {
    /** The + button: a playlist (Spotify's) or a folder (ours). */
    data object Create : LibrarySheet
    data object CreatePlaylist : LibrarySheet
    data class Item(val item: LibItem) : LibrarySheet
    data class Folder(val id: String) : LibrarySheet
    /** Pick a folder for [uri]. */
    data class PickFolder(val uri: String) : LibrarySheet
    /** Name a new folder, then put [uri] in it if there is one. */
    data class NewFolder(val uri: String?) : LibrarySheet
    data class Rename(val id: String) : LibrarySheet
    data class Delete(val id: String) : LibrarySheet
}

/** Shows [sheet]; [show] opens the next one (or closes with null). */
@Composable
internal fun LibrarySheets(app: AppState, sheet: LibrarySheet, items: List<LibItem>, show: (LibrarySheet?) -> Unit) {
    val store = app.c.library
    val layout by store.layout.collectAsState()
    val close = { show(null) }
    when (sheet) {
        LibrarySheet.Create -> Sheet(close) {
            Text("Create", style = Type.sectionSmall, color = Palette.text, modifier = Modifier.padding(bottom = 12.dp))
            MenuItem(Icons.AddPlaylist, "Playlist", accent = true) { show(LibrarySheet.CreatePlaylist) }
            MenuItem(Icons.Folder, "Folder", accent = true) { show(LibrarySheet.NewFolder(null)) }
        }
        LibrarySheet.CreatePlaylist -> CreatePlaylistDialog(app, close)
        is LibrarySheet.Item -> Sheet(close) {
            val item = sheet.item
            val pinned = layout.isPinned(item.uri)
            val folder = layout.folderOf(item.uri)
            Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(item.imageUrl, Modifier.size(56.dp), if (item.kind == LibKind.Artist) CircleShape else RoundedCornerShape(10.dp))
                Column(Modifier.padding(start = 14.dp)) {
                    TitleText(item.title, Type.body.copy(fontSize = 16.5.sp))
                    SubText(item.subtitle, Modifier.padding(top = 3.dp))
                }
            }
            HorizontalDivider(color = Palette.line)
            MenuItem(Icons.Pin, if (pinned) "Unpin" else "Pin", accent = true) {
                close()
                store.update { if (pinned) it.unpin(item.uri) else it.pin(item.uri) }
            }
            MenuItem(Icons.Folder, if (folder == null) "Add to folder" else "Move to another folder") { show(LibrarySheet.PickFolder(item.uri)) }
            if (folder != null) MenuItem(Icons.Remove, "Remove from ${folder.name}") {
                close()
                store.update { it.moveTo(item.uri, null) }
                app.message("Removed from ${folder.name}")
            }
            MenuItem(Icons.Share, "Share") { close(); app.share(item.uri, item.title) }
        }
        is LibrarySheet.Folder -> {
            val folder = layout.folder(sheet.id) ?: return close()
            val pinned = layout.isPinned(folder.uri)
            Sheet(close) {
                Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FolderArt(folderImages(items, folder), Modifier.size(56.dp), 10)
                    Column(Modifier.padding(start = 14.dp)) {
                        TitleText(folder.name, Type.body.copy(fontSize = 16.5.sp))
                        SubText("Folder", Modifier.padding(top = 3.dp))
                    }
                }
                HorizontalDivider(color = Palette.line)
                MenuItem(Icons.Pin, if (pinned) "Unpin" else "Pin", accent = true) {
                    close()
                    store.update { if (pinned) it.unpin(folder.uri) else it.pin(folder.uri) }
                }
                MenuItem(Icons.Edit, "Rename") { show(LibrarySheet.Rename(folder.id)) }
                MenuItem(Icons.Remove, "Delete folder") { show(LibrarySheet.Delete(folder.id)) }
            }
        }
        is LibrarySheet.PickFolder -> Sheet(close) {
            val current = layout.folderOf(sheet.uri)
            Text("Add to folder", style = Type.sectionSmall, color = Palette.text, modifier = Modifier.padding(bottom = 12.dp))
            MenuItem(Icons.Plus, "New folder", accent = true) { show(LibrarySheet.NewFolder(sheet.uri)) }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(layout.folders, key = { it.id }) { f ->
                    Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).clickable {
                        close()
                        store.update { it.moveTo(sheet.uri, f.id) }
                        app.message("Added to ${f.name}")
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Folder, null, Modifier.size(24.dp), tint = Palette.sub)
                        TitleText(f.name, modifier = Modifier.weight(1f).padding(start = 18.dp))
                        if (f.id == current?.id) Icon(Icons.Check, "In this folder", Modifier.size(22.dp), tint = Palette.accent)
                    }
                }
            }
        }
        is LibrarySheet.NewFolder -> NameDialog("Give your folder a name", "", "Create", close) { name ->
            store.createFolder(name, listOfNotNull(sheet.uri))
            app.message(if (sheet.uri != null) "Added to $name" else "Folder created")
        }
        is LibrarySheet.Rename -> {
            val folder = layout.folder(sheet.id) ?: return close()
            NameDialog("Rename folder", folder.name, "Save", close) { name -> store.update { it.renameFolder(folder.id, name) } }
        }
        is LibrarySheet.Delete -> {
            val folder = layout.folder(sheet.id) ?: return close()
            AlertDialog(
                onDismissRequest = close,
                containerColor = Palette.surface,
                title = { Text("Delete ${folder.name}?", style = Type.sectionSmall, color = Palette.text) },
                text = { Text("What's in it stays in Your Library.", style = Type.caption, color = Palette.sub) },
                confirmButton = {
                    TextButton(onClick = {
                        close()
                        store.update { it.deleteFolder(folder.id) }
                        if (app.nav.current == Route.LibraryFolder(folder.id)) app.back()
                    }) { Text("Delete", color = Palette.heart) }
                },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDone: () -> Unit, onName: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Palette.surface,
        title = { Text(title, style = Type.sectionSmall, color = Palette.text) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("My folder") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDone(); onName(name.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** A playlist, album, artist or podcast in Your Library or a folder; a long press opens its menu. */
@Composable
internal fun LibraryItemRow(app: AppState, item: LibItem, pinned: Boolean, grid: Boolean, position: Int, onLongClick: () -> Unit) {
    val shape = if (item.kind == LibKind.Artist) CircleShape else RoundedCornerShape(if (grid) 14.dp else 10.dp)
    if (grid) GridCard(position, item.title, item.subtitle, { Artwork(item.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f), shape) }, onLongClick) {
        app.openUri(item.uri)
    }
    else CollectionRow(item.title, item.subtitle, { Artwork(item.imageUrl, Modifier.size(56.dp), shape) }, { app.openUri(item.uri) },
        playing = app.isPlaying(item.uri), leadingSubIcon = if (pinned) Icons.Pin else null, onLongClick = onLongClick)
}

@Composable
internal fun FolderRow(app: AppState, entry: LibraryEntry.Folder<LibItem>, grid: Boolean, position: Int, onLongClick: () -> Unit) {
    val images = entry.members.map { it.imageUrl }
    val subtitle = dotted("Folder", folderCount(entry.members))
    val open = { app.open(Route.LibraryFolder(entry.folder.id)) }
    if (grid) GridCard(position, entry.folder.name, subtitle, { FolderArt(images, Modifier.fillMaxWidth().aspectRatio(1f), 14) }, onLongClick, open)
    else CollectionRow(entry.folder.name, subtitle, { FolderArt(images, Modifier.size(56.dp), 10) }, open,
        leadingSubIcon = if (entry.pinned) Icons.Pin else Icons.Folder, onLongClick = onLongClick)
}

/** "6 playlists" when that's all a folder holds, else "6 items"; null when it's empty. */
internal fun folderCount(members: List<LibItem>): String? = when {
    members.isEmpty() -> null
    members.all { it.kind == LibKind.Playlist } -> if (members.size == 1) "1 playlist" else "${members.size} playlists"
    else -> if (members.size == 1) "1 item" else "${members.size} items"
}

/** The covers of what's in [folder] that's still in [items]. */
private fun folderImages(items: List<LibItem>, folder: LibraryFolder): List<String?> {
    val byUri = items.associateBy { it.uri }
    return folder.items.mapNotNull { byUri[it] }.map { it.imageUrl }
}

/**
 * A folder's cover, as in the mockups: its first four covers in a 2×2 grid. With fewer than four,
 * the first cover; empty, a folder icon.
 */
@Composable
internal fun FolderArt(images: List<String?>, modifier: Modifier, cornerDp: Int) {
    Box(modifier.clip(RoundedCornerShape(cornerDp.dp)).background(Palette.raised), contentAlignment = Alignment.Center) {
        when {
            images.isEmpty() -> Icon(Icons.Folder, null, Modifier.size(26.dp), tint = Palette.accent)
            images.size < 4 -> Artwork(images.first(), Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (col in 0..1) Artwork(images[row * 2 + col], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
                }
            }
        }
    }
}

/** One of the app's own folders: what's in it (pinned first), with the same menus as Your Library. */
@Composable
fun LibraryFolderScreen(app: AppState, id: String) {
    val data = loadLibrary(app)
    val layout by app.c.library.layout.collectAsState()
    var sheet by remember { mutableStateOf<LibrarySheet?>(null) }
    val folder = layout.folder(id)
    val contents = remember(data.value, layout) { layout.contents(id, data.value.orEmpty(), LibItem::uri) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Icons.Back, "Back", { app.back() }, tint = Palette.text)
                    Spacer(Modifier.weight(1f))
                    if (folder != null) IconButton(Icons.More, "Folder options", { sheet = LibrarySheet.Folder(id) }, tint = Palette.text, iconSize = 20.dp)
                }
                if (folder != null) Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FolderArt(contents.map { it.item.imageUrl }, Modifier.size(72.dp), 12)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(folder.name, style = Type.title, color = Palette.text, maxLines = 2)
                        SubText(dotted("Folder", folderCount(contents.map { it.item })), Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        when {
            folder == null -> item { EmptyMessage("Folder not found", "It was deleted.") }
            else -> when (val s = data.state) {
                Load.Loading -> item { Loading() }
                is Load.Failed -> item { ErrorMessage(s, data.retry) }
                is Load.Done -> {
                    items(contents, key = { it.item.uri }) { entry ->
                        LibraryItemRow(app, entry.item, entry.pinned, grid = false, position = 0) { sheet = LibrarySheet.Item(entry.item) }
                    }
                    if (contents.isEmpty()) item {
                        EmptyMessage("This folder is empty", "Long-press a playlist in Your Library and choose Add to folder.")
                    }
                }
            }
        }
    }
    sheet?.let { LibrarySheets(app, it, data.value.orEmpty()) { next -> sheet = next } }
}
