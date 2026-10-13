import java.io.BufferedReader
import java.io.File
import java.io.PrintStream
import java.io.StringWriter
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readSymbolicLink

private
enum class Commands(){
    Quit,
    SetFsPath,
    SetOutPath,
    GetList,
    Recover,
}

// The token the GUI appends when it re-spawns this very executable to act as
// the restore server; main() checks args for it to decide server vs GUI mode.
val SERVE_FLAG = "Serve!"

fun getServerCmdLine(needsRoot: Boolean = true): List<String>{
    var l = File("/proc/self/cmdline").readText().split("\u0000") + SERVE_FLAG
    l = listOf(Path("/proc/self/exe").readSymbolicLink().toString()) + l.drop(1)
    // This is to enable debugging. filter out Idea parameters so server wont wait for debugger to connect.
    l = l.filter {
        it.run{
            !isEmpty() && !startsWith("-javaagent") && !startsWith("-agentlib")
        }
    }
    // Only block devices need root; a filesystem image is an ordinary file,
    // so spawn the server directly as the current user.
    if (!needsRoot)
        return l
    fun env(name: String): String{
        return System.getenv(name) ?: ""
    }
    return """
        pkexec env DISPLAY=${env("DISPLAY")} XAUTHORITY=${env("XAUTHORITY")}
        WAYLAND_DISPLAY=${env("WAYLAND_DISPLAY")} XDG_RUNTIME_DIR=${env("XDG_RUNTIME_DIR")}"""
        .trimIndent().split(" ", "\n") + l
}

private
val restorationDone = "RestorationdonE"

class ServerController(
    fsPath: String,
    private val proc: Process =
        ProcessBuilder(getServerCmdLine(fsPath.startsWith("/dev")))
        .start(),
    private val out: PrintStream = PrintStream(proc.outputStream, true),
    private val ins: BufferedReader = proc.inputStream.bufferedReader(),
) {
    fun setFsPath(p: String){
        out.println(Commands.SetFsPath)
        out.println(p)
    }
    fun setOutPath(p: String){
        out.println(Commands.SetOutPath)
        out.println(p)
    }
    fun getRecoverableFilesList(): List<String>{
        out.println(Commands.GetList)
        val ret = mutableListOf<String>()
        while (true) {
            val l = ins.readLine()
            if (l == null || l.isEmpty())
                break
            ret.add(l)
        }
        return ret
    }
    //returns error, if any
    fun recover(pathInFs: String): String{
        out.println(Commands.Recover)
        out.println(pathInFs)
        val errBuf = StringWriter()
        while (true){
            val line = ins.readLine()
            if (line == restorationDone)
                break
            errBuf.write(line)
            errBuf.write("\n")
        }
        return errBuf.toString()
    }
    fun quit(){
        out.println(Commands.Quit)
    }
}

class Server(
    private val ins: BufferedReader = System.`in`.bufferedReader(),
    // autoFlush: the GUI blocks on readLine() waiting for the GetList reply;
    // without it the output sits in the encoder buffer and both sides wait on each other.
    private val out: PrintStream = PrintStream(System.out, true)
) {
    private lateinit
    var fsPath : String
    private lateinit
    var outPath : String
    val log by lazy{
        File(outPath + "/btrfs-file-restorer-errors.txt")
    }
    fun serve() {
        while (true) {
            val line = ins.readLine() ?: break
            when (line) {
                Commands.Quit.name -> break
                Commands.SetFsPath.name ->
                    fsPath = ins.readLine()
                Commands.SetOutPath.name -> {
                    outPath = ins.readLine()
                    Path(outPath).createDirectories()
                }
                Commands.GetList.name -> {
                    val proc = ProcessBuilder("btrfs restore -vv --dry-run".split(" ") + fsPath + ".")
                        .redirectError(ProcessBuilder.Redirect.appendTo(log))
                        .start()
                    val ins = proc.inputStream.bufferedReader()
                    while (true) {
                        val l = ins.readLine()
                        if (l == null) {
                            out.println()
                            break
                        }
                        val prefix = "Restoring ./"
                        if (l.startsWith(prefix)){
                            val path = l.removePrefix(prefix)
                            out.println(path)
                        }
                    }
                }
                Commands.Recover.name -> {
                    val pathInFS = ins.readLine()
                    var regexpPath = pathInFS.split("/").asSequence().drop(1).map {
                        val s = StringBuilder()
                        for (c in it){
                            if (!c.isWhitespace() && !c.isLetterOrDigit())
                                s.append("\\")
                            s.append(c)
                        }
                        s.toString()
                    }.toList().reversed().fold(""){ acc, element ->
                        "(|/$element$acc)"
                    }
                    regexpPath = "^$regexpPath$"
                    val proc = ProcessBuilder("btrfs restore --metadata --xattr --symlinks --path-regex".split(" ") + regexpPath + fsPath + outPath)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                        .start()
                    val errBuf = StringWriter()
                    while (true){
                        val line = proc.errorReader().readLine()
                        if (line == null)
                            break
                        errBuf.write(line)
                    }
                    proc.waitFor()
                    val errMsg = errBuf.toString()
                    if (errMsg.isNotEmpty()){
                        log.appendText("While restoring $pathInFS\n")
                        log.appendText(errMsg)
                        log.appendText("\n")
                        out.println(errMsg)
                    }
                    out.println(restorationDone)
                }
            }
        }
    }
}

