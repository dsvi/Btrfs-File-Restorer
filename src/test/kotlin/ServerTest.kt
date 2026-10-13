import java.io.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {
    @Test
    fun testServer(){
        val ins0 = PipedInputStream()
        val out0 = PipedOutputStream(ins0)
        val ins1 = PipedInputStream()
        val out1 = PipedOutputStream(ins1)
        val sc = ServerController(
            fsPath = "test.img",
            proc = ProcessBuilder(listOf<String>("echo")).start(),
            out = PrintStream(out1),
            ins = BufferedReader(InputStreamReader(ins0)) )
        val s = Server(BufferedReader(InputStreamReader(ins1)), PrintStream(out0))
        val t = Thread {
            s.serve()
        }
        t.start()
        sc.setFsPath("test.img")
        sc.setOutPath("test-output")
        val expected = listOf(
            "/Tomb raider па рюске!",
            "/Tomb raider па рюске!/А́а́, Е́е́, И́и́, О́о́, У́у́, Ы́ы́, Э́э́, Ю́ю́, Я́я́.txt",
            "/docs",
            "/docs/notes",
            "/docs/notes/meeting-2024-01-15.txt",
            "/docs/papers",
            "/docs/papers/lazy-superblocks.txt",
            "/docs/readme.md",
            "/src",
            "/src/utils",
            "/src/utils/helpers.kt",
            "/src/Main.kt",
            "/data",
            "/data/logs",
            "/data/logs/server.log",
            "/data/backup-2023-12-01.bin",
            "/music",
            "/music/playlist.m3u",
            "/empty-dir",
        )
        assertEquals(expected, sc.getRecoverableFilesList())
        // The restore used to "succeed" (exit 0, empty stderr) while writing nothing
        // at all, so assert the bytes actually landed on disk.
        val deepPath = expected[1]
        assertEquals("", sc.recover(deepPath))
        assertEquals(true, File("test-output" + deepPath).isFile)
        sc.recover("/Tomb raider па рюске!/А́а́, Е́е́, И́и́, О́о́, У́у́, Ы́ы́, Э́э́, Ю́ю́, Я́я́.txt")
        sc.quit()
        t.join()
    }
}