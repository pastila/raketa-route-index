package online.raketa.routeindex

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

class SpxCommandOptionsTest {
    private fun target(entry: String) = CommandRunTarget(
        ConsoleCommand("sync:api:service", emptyList(), "", false, null, null, 1, emptyList(), listOf(entry)),
        entry,
    )

    private fun option(name: String, value: String) = Element("option").setAttribute("name", name).setAttribute("value", value)

    private fun value(state: Element, name: String): String? =
        state.getChildren("option").single { it.getAttributeValue("name") == name }.getAttributeValue("value")

    @Test
    fun `new profile uses console app directory and copies arguments verbatim`() {
        val state = Element("configuration")
            .addContent(option("php", "/usr/bin/php8.5"))
            .addContent(option("phpOptions", "-d memory_limit=1G"))
        val arguments = "sync:api:service 123 --name=\"two words\" --filter='a & b'"
        SpxCommandOptions(state).initialize(target("api/hotels/cli/sabre"), arguments)

        assertEquals("api/hotels/cli/sabre", value(state, "scriptPath"))
        assertEquals("api/hotels", value(state, "workingDirectory"))
        assertEquals(arguments, SpxCommandOptions(state).arguments)
        assertEquals("/usr/bin/php8.5", value(state, "php"))
        assertEquals("-d memory_limit=1G", value(state, "phpOptions"))
    }

    @Test
    fun `editing arguments preserves profile paths environment and before run tasks`() {
        val environment = Element("option").setAttribute("name", "environment")
            .addContent(Element("map").addContent(Element("entry").setAttribute("key", "APP_ENV").setAttribute("value", "dev")))
        val beforeRun = Element("method").setAttribute("v", "2").addContent(Element("option").setAttribute("name", "CustomTask"))
        val state = Element("configuration")
            .addContent(option("scriptPath", "/custom/console"))
            .addContent(option("workingDirectory", "/custom/app"))
            .addContent(option("arguments", "sync:api:service --old"))
            .addContent(environment)
            .addContent(beforeRun)
        SpxCommandOptions(state).arguments = "sync:api:service --new"

        assertEquals("sync:api:service --new", value(state, "arguments"))
        assertEquals("/custom/console", value(state, "scriptPath"))
        assertEquals("/custom/app", value(state, "workingDirectory"))
        assertEquals("dev", environment.getChild("map").getChild("entry").getAttributeValue("value"))
        assertEquals(beforeRun, state.getChild("method"))
    }

    @Test
    fun `console at project root gets an explicit working directory`() {
        val state = Element("configuration")
        SpxCommandOptions(state).initialize(target("console"), "sync:api:service")
        assertEquals(".", value(state, "workingDirectory"))
    }

    /** Optional compatibility check against a built SPX plugin: `-PraketaSpxPlugin=/path/to/raketa-php-spx.jar`. */
    @Test
    fun `SPX plugin reads and writes our options`() {
        val path = System.getProperty("raketa.spxPlugin")
        assumeTrue(path != null && File(path).isFile)
        URLClassLoader(arrayOf(File(path!!).toURI().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("ru.raketa.php.spx.script.SpxPhpScriptOptions")
            val state = Element("configuration").addContent(option("phpOptions", "-d memory_limit=1G"))
            val arguments = "sync:api:service --name=\"two words\""
            SpxCommandOptions(state).initialize(target("api/hotels/cli/sabre"), arguments)

            val native = XmlSerializer.deserialize(state, type)
            assertEquals("api/hotels/cli/sabre", type.getMethod("getScriptPath").invoke(native))
            assertEquals("api/hotels", type.getMethod("getWorkingDirectory").invoke(native))
            assertEquals(arguments, type.getMethod("getArguments").invoke(native))

            val saved = XmlSerializer.serialize(native)
            assertEquals(arguments, SpxCommandOptions(saved).arguments)
            SpxCommandOptions(saved).arguments = "sync:api:service --id=1"
            val edited = XmlSerializer.deserialize(saved, type)
            assertEquals("sync:api:service --id=1", type.getMethod("getArguments").invoke(edited))
            assertEquals("-d memory_limit=1G", type.getMethod("getPhpOptions").invoke(edited))
        }
    }
}
