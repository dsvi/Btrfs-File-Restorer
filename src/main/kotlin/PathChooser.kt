import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
@androidx.compose.ui.tooling.preview.Preview
fun PathChooser() {
    var fsPath by remember { mutableStateOf("") }
    var outPath by remember { mutableStateOf("") }

    val mdf = remember { Modifier.padding(10.dp).fillMaxWidth() }
    Column {
        TextField(
            modifier = mdf,
            value = fsPath,
            onValueChange = { fsPath = it },
            label = { Text("Filesystem path") },
            placeholder = { Text("/dev/sda2") },
            supportingText = {
                Text(
                    "This can be either a device path or a filesystem image path",
                )
            },
            singleLine = true,
        )
        TextField(
            modifier = mdf,
            value = outPath,
            onValueChange = { outPath = it },
            label = { Text("Output path") },
            singleLine = true,
        )
        ElevatedButton(
            modifier = Modifier.padding(20.dp).align(Alignment.End),
            onClick = {
                currentScreen.value =
                    @Composable fun(){
                        FilesystemTreeView(fsPath, outPath)
                    }
            }
        ) {
            Text("Proceed")
        }
    }

}