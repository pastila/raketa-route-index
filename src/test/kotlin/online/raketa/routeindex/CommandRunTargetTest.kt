package online.raketa.routeindex

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CommandRunTargetTest {
    private fun command(name: String, handler: String?, file: String?, entries: List<String>) =
        ConsoleCommand(name, emptyList(), "", false, handler, file, 1, listOf("raketa"), entries)

    private val sabre = "api/hotels/src/Sabre/SyncApiServiceCommand.php"
    private val commands = listOf(
        command("profiles:handle-queue", "Layer\\ProfileImporterQueueHandlerCommand::execute", "app/raketa/raketa/Layer/Presentation/Command/ProfileImporterQueueHandlerCommand.php",
            listOf("app/raketa/bin/console", "api/profile-sync/cli/amadeus-import-profiles")),
        command("sync:api:service", "Sabre\\SyncApiServiceCommand::execute", sabre, listOf("api/hotels/cli/sabre")),
        command("bus:listen", "Bus\\ListenCommand::__invoke", "api/bus/src/ListenCommand.php", listOf("api/bus/cli/run")),
        command("make:entity", null, null, listOf("api/transfer/bin/console")),
    )

    @Test
    fun `one target per console`() {
        val targets = CommandRunTarget.forMethod(commands, "app/raketa/raketa/Layer/Presentation/Command/ProfileImporterQueueHandlerCommand.php", "execute")
        assertEquals(listOf("app/raketa/bin/console", "api/profile-sync/cli/amadeus-import-profiles"), targets.map { it.entry })
        assertEquals("profiles:handle-queue (app/raketa/bin/console)", targets[0].configurationName)
        assertEquals("app/raketa", targets[0].workingDirectory)
        assertEquals("api/profile-sync", targets[1].workingDirectory)
    }

    @Test
    fun `single console keeps the bare name`() {
        val target = CommandRunTarget.forMethod(commands, sabre, "execute").single()
        assertEquals("sync:api:service", target.configurationName)
        assertEquals("api/hotels", target.workingDirectory)
    }

    @Test
    fun `only the handler method of that file`() {
        assertEquals(1, CommandRunTarget.forMethod(commands, "api/bus/src/ListenCommand.php", "__invoke").size)
        assertTrue(CommandRunTarget.forMethod(commands, "api/bus/src/ListenCommand.php", "execute").isEmpty())
        assertTrue(CommandRunTarget.forMethod(commands, sabre, "configure").isEmpty())
        assertTrue(CommandRunTarget.forMethod(commands, "app/raketa/src/Other.php", "execute").isEmpty())
    }

    /** Smoke test against a real dump: `-Draketa.routes=/path/to/raketa-routes.json` */
    @Test
    fun `real dump gives every command with a class a target`() {
        val path = System.getProperty("raketa.routes") ?: System.getenv("RAKETA_ROUTES")
        assumeTrue(path != null && File(path).isFile)
        val root = File(path!!).reader().use { JsonParser.parseReader(it).asJsonObject }
        val all = root.getAsJsonArray("commands").map { RouteIndex.parseCommand(it.asJsonObject) }
        val targets = all.filter { it.file != null && it.handler != null }
            .flatMap { CommandRunTarget.forMethod(all, it.file!!, it.handler!!.substringAfter("::")) }
        val queue = targets.filter { it.command.name == "profiles:handle-queue" }
        assertEquals(setOf("app/raketa/bin/console"), queue.map { it.entry }.toSet())
        val sabreTargets = targets.filter { it.command.name == "sync:api:service" && it.entry == "api/hotels/cli/sabre" }
        assertTrue("sabre sync:api:service has its own target", sabreTargets.isNotEmpty())
        assertTrue("every console resolves to an app dir", targets.all { it.workingDirectory.isNotEmpty() })
    }
}
