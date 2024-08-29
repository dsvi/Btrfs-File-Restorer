//import androidx.compose.ui.unit.dp
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.materialkolor.dynamicColorScheme
import kotlin.system.exitProcess


// region
// -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=
// TODO: remove this shit when compose supports wayland
var scale = 1f
inline val Int.dp: Dp get() = Dp(value = this.toFloat()*scale)
private
fun scaledUpFonts() : Typography{
    val scale = fun (font: TextStyle) : TextStyle{
        return font.copy(fontSize = font.fontSize * scale, lineHeight = font.lineHeight * scale)
    }
    val defaultTyp = Typography()
    return Typography(
        bodyLarge = scale(defaultTyp.bodyLarge),
        bodyMedium    = scale(defaultTyp.bodyMedium),
        bodySmall     = scale(defaultTyp.bodySmall),
        displayLarge  = scale(defaultTyp.displayLarge),
        displayMedium = scale(defaultTyp.displayMedium),
        displaySmall  = scale(defaultTyp.displaySmall),
        headlineLarge = scale(defaultTyp.headlineLarge),
        headlineMedium= scale(defaultTyp.headlineMedium),
        headlineSmall = scale(defaultTyp.headlineSmall),
        labelLarge    = scale(defaultTyp.labelLarge),
        labelMedium   = scale(defaultTyp.labelMedium),
        labelSmall    = scale(defaultTyp.labelSmall),
        titleLarge    = scale(defaultTyp.titleLarge),
        titleMedium   = scale(defaultTyp.titleMedium),
        titleSmall    = scale(defaultTyp.titleSmall),
    )
}
val fonts by lazy { scaledUpFonts() }
// -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=
// endregion



val AppName = "btrfs file restorer"
//val colorScheme = dynamicColorScheme(Color(0xF99703), isAmoled = false, isDark = false)
val colorScheme = dynamicColorScheme(Color(0x63A002), isAmoled = false, isDark = false)

val currentScreen: MutableState<@Composable () -> Unit> = mutableStateOf<@Composable ()->Unit>(
    @Composable fun(){
        PathChooser()
    }
)

@Composable
@Preview
fun App() {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = fonts
    ) {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            currentScreen.value()
        }
    }
}

fun main(args: Array<String>) {
    if (!args.isEmpty()) {
        Server().serve()
        exitProcess(0)
    }
    run {
        try {
            val p = Runtime.getRuntime().exec("gsettings get org.gnome.desktop.interface scaling-factor")
            val l = p.inputStream.bufferedReader().readLine()?.split(" ")
            if (l != null && l.size == 2)
                scale = l[1].toFloat()
        }
        catch(e: Throwable){
            val env = System.getenv("GDK_SCALE")
            if (env != null && env.isNotEmpty())
                scale = env.toFloat()
        }
    }
    println(getServerCmdLine().joinToString(" ") { it })
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = AppName,
            state = rememberWindowState(width = 600.dp, height = 600.dp)
            //state = rememberWindowState(size = DpSize.Unspecified)
        ) {
            window.background = java.awt.Color(colorScheme.background.value.toInt())
            App()
        }
    }
}
