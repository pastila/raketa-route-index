package online.raketa.routeindex

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleCommandLineTest {
    @Test
    fun `run turns xdebug off`() {
        assertEquals(
            listOf("docker", "exec", "-w", "/web/app/raketa", "raketa-php", "php", "-d", "xdebug.mode=off", "/web/app/raketa/bin/console", "bus:listen", "--id=1"),
            ConsoleCommandLine.docker("raketa-php", "/web", "app/raketa/bin/console", "app/raketa", listOf("bus:listen", "--id=1"), debug = false),
        )
    }

    @Test
    fun `debug requests a session`() {
        assertEquals(
            listOf("docker", "exec", "-e", "XDEBUG_SESSION=1", "-e", "XDEBUG_MODE=debug", "-w", "/web/api/hotels", "raketa-php", "php",
                "/web/api/hotels/cli/sabre", "sync:api:service"),
            ConsoleCommandLine.docker("raketa-php", "/web", "api/hotels/cli/sabre", "api/hotels", listOf("sync:api:service"), debug = true),
        )
    }

    @Test
    fun `console at the project root`() {
        assertEquals("/web", ConsoleCommandLine.docker("c", "/web", "console", "", emptyList(), debug = false)[3])
    }
}
