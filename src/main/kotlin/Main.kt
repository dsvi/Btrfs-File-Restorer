import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jthemedetecor.OsThemeDetector
import com.materialkolor.dynamicColorScheme
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt
import kotlin.system.exitProcess


val AppName = "btrfs file restorer"
//val colorScheme = dynamicColorScheme(Color(0xF99703), isAmoled = false, isDark = true)
val colorScheme = dynamicColorScheme(Color(0x4C662B), isAmoled = false, isDark = OsThemeDetector.getDetector().isDark)

val currentScreen: MutableState<@Composable () -> Unit> = mutableStateOf<@Composable ()->Unit>(
    @Composable fun(){
        PathChooser()
    }
)

@Composable
@androidx.compose.ui.tooling.preview.Preview
fun App() {
    MaterialTheme(
        colorScheme = colorScheme
    ) {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            currentScreen.value()
        }
    }
}

fun main(args: Array<String>) {
    // HiDPI. Compose Desktop takes its density from AWT — see
    // Density(GraphicsConfiguration.defaultTransform.scaleX) — and the JDK only ever
    // applies an *integer* sun.java2d.uiScale on X11: Xft.dpi 168 (= 1.75) is silently
    // dropped and the toolkit stays at 1.0. Rather than pre-multiplying our own dp,
    // which leaves Material3's internal metrics unscaled, we top up LocalDensity by
    // whatever the platform failed to apply. That re-densifies the whole tree, and the
    // ratio guard means we never double-scale where the platform already got it right
    // (Windows/macOS, or integer X11 scaling).
    //   --ui-scale=1.5 sets an absolute target density instead — useful on a Wayland
    //   session that publishes no Xft.dpi at all, or to shrink the UI below 1×.
    val requestedScale = args.find { it.startsWith("--ui-scale=") }
        ?.substringAfter('=')
        ?.toFloatOrNull()

    // The GUI re-spawns this executable with SERVE_FLAG on the command line to
    // run it as the restore server (see getServerCmdLine); without that flag we
    // show the UI, whatever other arguments are present.
    if (SERVE_FLAG in args) {
        Server().serve()
        exitProcess(0)
    }

    val xftScale = xftDpiScale()
    // Used only to size the window frame; the content re-reads its own window's
    // density below, which is the one that actually matters.
    val frameFactor = uiScaleFactor(awtUiScale(), requestedScale, xftScale)

    println(getServerCmdLine().joinToString(" ") { it })
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = AppName,
            // Compose Desktop turns WindowState dp into *pixels* one-to-one
            // (Windows.setSizeImpl just rounds the dp value), so the frame has to be
            // pre-multiplied by the same factor the content is densified by — otherwise
            // a 1.75× UI would be crammed into the same 600px it used to get at 1×.
            state = rememberWindowState(
                width = (600 * frameFactor).roundToInt().dp,
                height = (600 * frameFactor).roundToInt().dp,
            )
            //state = rememberWindowState(size = DpSize.Unspecified)
        ) {
            window.background = java.awt.Color(colorScheme.background.value.toInt())

            val platform = LocalDensity.current
            val factor = uiScaleFactor(platform.density, requestedScale, xftScale)

            CompositionLocalProvider(
                LocalDensity provides
                    Density(platform.density * factor, platform.fontScale),
            ) {
                App()
            }
        }
    }
}

/**
 * How much density to add on top of what the platform already applies.
 *
 * An explicit `--ui-scale=` is an absolute target and may be < 1; auto-detection only
 * ever tops up what the platform under-applied, so it never double-scales.
 */
internal fun uiScaleFactor(
    platformDensity: Float,
    requestedScale: Float?,
    xftScale: Float?,
): Float {
    val applied = platformDensity.takeIf { it > 0f } ?: 1f
    return when {
        requestedScale != null -> requestedScale / applied
        xftScale != null -> (xftScale / applied).takeIf { it > 1.001f } ?: 1f
        else -> 1f
    }.coerceIn(0.1f, 10f)
}

/** UI scale implied by the X resource DB (Xft.dpi / 96), or null when there is none. */
private fun xftDpiScale(): Float? = try {
    val process = ProcessBuilder("xrdb", "-query")
        .redirectErrorStream(true)
        .start()
    val output = try {
        process.inputStream.bufferedReader().use { it.readText() }
    } finally {
        process.waitFor()
        process.destroy()
    }
    output.lineSequence()
        .firstOrNull { it.trimStart().startsWith("Xft.dpi") }
        ?.substringAfter(':')
        ?.trim()
        ?.toFloatOrNull()
        ?.takeIf { it > 0f }
        ?.let { it / 96f }
} catch (_: Throwable) {
    null // no xrdb / no X resource DB (pure Wayland session, container, ...)
}

/** The scale AWT already applies and Compose therefore inherits: defaultTransform.scaleX. */
private fun awtUiScale(): Float = try {
    GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
        .defaultTransform
        .scaleX
        .toFloat()
} catch (_: Throwable) {
    1f // headless, or an AWT we did not expect
}
