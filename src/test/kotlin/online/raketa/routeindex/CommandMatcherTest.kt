package online.raketa.routeindex

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CommandMatcherTest {
    private fun command(
        name: String,
        handler: String,
        entries: List<String> = listOf("core/bin/console", "app/raketa/api/bin/console"),
        description: String = "",
        aliases: List<String> = emptyList(),
    ) = ConsoleCommand(name, aliases, description, false, handler, "x.php", 1, listOf("core"), entries)

    private val commands = listOf(
        command("bus:translate-stations", "TranslateBusStationsCommand::execute", description = "Переводит автобусные станции на несколько языков"),
        command("bus:service-process", "ProcessBusServiceCommand::execute"),
        command("sync:api:service", "Sabre\\SyncApiServiceCommand::execute", entries = listOf("api/hotels/cli/sabre")),
        command("sync:api:service", "Ostrovok\\SyncApiServiceCommand::execute", entries = listOf("api/hotels/cli/ostrovok")),
        command("message-broker-queue-listen", "QueueListenCommand::execute", entries = listOf("api/bus/cli/run"), aliases = listOf("mb:listen")),
    )

    private fun search(q: String): List<CommandMatch> =
        commands.mapNotNull { CommandMatcher.match(it, q) }.sortedByDescending { it.score }

    private fun best(q: String): String? = search(q).firstOrNull()?.command?.handler

    @Test
    fun `exact name`() {
        assertEquals("TranslateBusStationsCommand::execute", best("bus:translate-stations"))
        assertTrue(search("bus:translate-stations").first().exact)
    }

    @Test
    fun `pasted command line`() {
        assertEquals("TranslateBusStationsCommand::execute", best("php core/bin/console bus:translate-stations --dry-run"))
        assertEquals("QueueListenCommand::execute", best("php api/bus/cli/run message-broker-queue-listen"))
        assertEquals("QueueListenCommand::execute", best("mb:listen"))
    }

    @Test
    fun `script path picks between same-named commands`() {
        assertEquals("Ostrovok\\SyncApiServiceCommand::execute", best("php /web/api/hotels/cli/ostrovok sync:api:service --id=1"))
        assertEquals("Sabre\\SyncApiServiceCommand::execute", best("api/hotels/cli/sabre sync:api:service"))
        assertEquals("Sabre\\SyncApiServiceCommand::execute", best("cli/sabre sync:api:service"))
    }

    @Test
    fun `partial name, class and description`() {
        assertEquals(2, search("bus:").size)
        assertEquals("TranslateBusStationsCommand::execute", best("translate-st"))
        assertEquals("TranslateBusStationsCommand::execute", best("автобусные станции"))
        assertTrue(search("bus:translate-stations").none { it.command.name == "bus:service-process" })
        assertNull(best("unknown:command"))
    }

    @Test
    fun `behat stub step`() {
        val line = """    Дано команда "aeroexpress:booking --serviceId=9611269" вернёт код 0 и вывод "WAIT""""
        val hit = FeatureStepCommand.find(line, line.indexOf("booking"))
        assertEquals("aeroexpress:booking", hit?.name)
        assertNull(FeatureStepCommand.find(line, line.indexOf("serviceId")))
        assertNull(FeatureStepCommand.find(line, line.indexOf("вернёт")))
        assertEquals("cities:sync-osm", FeatureStepCommand.find("""И команда "cities:sync-osm" вернёт код 0 и вывод""", 12)?.name)
    }

    /** Smoke test against a real dump: `-Draketa.routes=/path/to/raketa-routes.json` */
    @Test
    fun `real dump resolves known commands`() {
        val path = System.getProperty("raketa.routes") ?: System.getenv("RAKETA_ROUTES")
        assumeTrue(path != null && File(path).isFile)
        val root = File(path!!).reader().use { JsonParser.parseReader(it).asJsonObject }
        val all = root.getAsJsonArray("commands").map { RouteIndex.parseCommand(it.asJsonObject) }
        fun assertTop(q: String, handlerSuffix: String) {
            val found = all.mapNotNull { CommandMatcher.match(it, q) }.sortedByDescending { it.score }.take(5)
            val dump = found.joinToString("\n") { "${it.score} ${it.command.name} ${it.command.handler} ${it.command.entries}" }
            assertTrue("$q →\n$dump", found.firstOrNull()?.command?.handler?.endsWith(handlerSuffix) == true)
        }
        assertTop("bus:translate-stations", "TranslateBusStationsCommand::execute")
        assertTop("php core/bin/console order:process", "ProcessOrderCommand::execute")
        assertTop("php api/hotels/cli/ostrovok sync:api:service", "Ostrovok\\v3\\Tool\\SyncApiServiceCommand::execute")
        assertTop("php api/bus/cli/run message-broker-queue-listen", "::execute")
        assertTrue("every command but maker-bundle has a class", all.none { it.handler == null && !it.name.startsWith("make:") })
    }
}
