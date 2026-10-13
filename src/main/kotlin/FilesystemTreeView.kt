import anarchy.ds.compose.TreeView
import anarchy.ds.compose.TreeViewElement
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.LinkedTransferQueue

enum class FileState{
    Ignored,
    HasToBeRestored,
    Restored,
    Restoring,
    Error
}


open class FsTreeElement (
    val name: String,
    var state: MutableState<FileState> = mutableStateOf(FileState.Ignored),
    val parent: FsTreeElement? = null,
): TreeViewElement(){
    // always not null when state is set to Error
    var restorationError: String? = null

    private val children: TreeMap<String, FsTreeElement> = TreeMap<String, FsTreeElement>()

    fun ensureChildExist(name: String): FsTreeElement{
        var c = children.get(name)
        if (c == null) {
            c = FsTreeElement(
                name,
                parent = this
            )
            children.put(name, c)
            notifyChildrenChanged()
        }
        return c
    }

    override val key: Any?
        get() = name

    override fun children(): Iterable<FsTreeElement> {
        return children.values
    }

    override fun hasChildren(): Boolean {
        return children.isNotEmpty()
    }

    @Composable
    override fun view() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val transition: AnimatedContentTransitionScope<FileState>.() -> ContentTransform = remember {
                {
                    val scale = 800
                    val fade = 90
                    (fadeIn(animationSpec = tween(scale, delayMillis = fade)) +
                            scaleIn(initialScale = 0.92f, animationSpec = tween(scale, delayMillis = fade)))
                        .togetherWith(fadeOut(animationSpec = tween(fade)))
                }
            }
            AnimatedContent(
                state.value,
                transitionSpec = transition
            ) {
                val iconSize = 20.dp
                when (it) {
                    FileState.Ignored -> {
                        Tooltip("Restore") {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Restore",
                                modifier = Modifier.clickable(
                                    onClick = {
                                        root().restore(this@FsTreeElement)
                                    },
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true)
                                ).size(iconSize)
                            )
                        }
                    }

                    FileState.HasToBeRestored -> {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "",
                            modifier = Modifier.clickable(
                                onClick = {
                                    root().restoreCancel(this@FsTreeElement)
                                },
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true)
                            ).size(iconSize)
                        )
                    }

                    FileState.Restored -> Icon(
                        Icons.Default.Check,
                        contentDescription = "",
                        modifier = Modifier.size(iconSize),
                        tint = Color.Green
                    )

                    FileState.Restoring -> {
                        val infiniteTransition = rememberInfiniteTransition()
                        val rotation = infiniteTransition.animateFloat(
                            0f,
                            360f,
                            infiniteRepeatable(tween(2000), RepeatMode.Restart)
                        )
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "",
                            modifier = Modifier.graphicsLayer {
                                rotationZ = rotation.value
                            }.size(iconSize)
                        )
                    }

                    FileState.Error -> {
                        Tooltip(restorationError!!.trim()) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "",
                                modifier = Modifier.size(iconSize),
                                tint = Color.Red)
                        }
                    }
                }
            }
            Text(name)
        }
    }
}

fun FsTreeElement.root(): RootFsTreeElement{
    var root = this
    while (root.parent != null)
        root = root.parent
    return root as RootFsTreeElement
}

class RootFsTreeElement: FsTreeElement(""){
    private val restorationQueue = LinkedTransferQueue<FsTreeElement>()
    // don't collect on Main or will be deadlocked
    fun resrorationFlow() = flow<FsTreeElement> {
        while(true) {
            emit(restorationQueue.take())
        }
    }
    fun restore(e: FsTreeElement){
        if (e.state.value != FileState.Ignored)
            return
        status = "Restoring"
        restorationQueue.add(e)
        e.state.value = FileState.HasToBeRestored
        for (c in e.children())
            restore(c)
    }
    fun restoreCancel(e: FsTreeElement){
        if (e.state.value != FileState.HasToBeRestored)
            return
        restorationQueue.remove(e)
        e.state.value = FileState.Ignored
        for (c in e.children())
            restoreCancel(c)
        updateStatus()
    }
    var status by mutableStateOf("Ready")
    fun updateStatus(){
        if (restorationQueue.isEmpty())
            status = "Ready"
        else
            status = "Restoring"
    }
}

@Composable
fun FilesystemTreeView(fsPath: String, outPath: String){
    val root = remember{ RootFsTreeElement() }
    val server = remember(fsPath) { ServerController(fsPath) }

    fun add(path: List<String>){
        var el: FsTreeElement = root
        for (p in path){
            el = el.ensureChildExist(p)
        }
    }

    LaunchedEffect(root) {
        withContext(Dispatchers.IO){
            root.status = "Loading..."
            server.setFsPath(fsPath)
            server.setOutPath(outPath)
            val list = server.getRecoverableFilesList()
            withContext(Dispatchers.Main) {
                for (l in list) {
                    add(l.split("/").drop(1))
                }
                root.status = "Ready"
            }
            launch {
                root.resrorationFlow().collect{
                    withContext(Dispatchers.Main) {
                        it.state.value = FileState.Restoring
                    }
                    var e = it
                    val upwardPath = LinkedList<String>()
                    while (true) {
                        upwardPath.addFirst(e.name)
                        if (e.parent == null)
                            break
                        e = e.parent
                    }
                    val path = upwardPath.joinToString(separator = "/")
                    val error = server.recover(path)
                    withContext(Dispatchers.Main) {
                        root.updateStatus()
                        if (error.isNotEmpty()) {
                            it.state.value = FileState.Error
                            it.restorationError = error
                        }
                        else{
                            it.state.value = FileState.Restored
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.padding(3.dp).fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            val state = rememberLazyListState()
            val stateHorizontal = rememberScrollState(0)
            Box(modifier = Modifier.fillMaxSize().padding(end = 12.dp, bottom = 12.dp).horizontalScroll(stateHorizontal)) {
                TreeView(
                    root,
                    stateLazyList = state,
                )
            }
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(
                    scrollState = state
                )
            )
            HorizontalScrollbar(
                modifier = Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(end = 12.dp),
                adapter = rememberScrollbarAdapter(stateHorizontal)
            )
        }
        Text(root.status)
    }
}