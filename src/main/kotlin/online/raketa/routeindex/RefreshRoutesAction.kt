package online.raketa.routeindex

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Rebuilds `.idea/raketa-routes.json`: pipes the bundled dumper into `php` inside the php container
 * (`docker exec -i raketa-php php -d xdebug.mode=off`), so nothing is written into the raketa working tree except the
 * gitignored `.idea` file.
 */
class RefreshRoutesAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        refresh(e.project ?: return)
    }

    companion object {
        private const val TIMEOUT_MS = 180_000

        val container: String
            get() = System.getProperty("raketa.routes.container")
                ?: System.getenv("RAKETA_PHP_CONTAINER")
                ?: "raketa-php"

        fun refresh(project: Project) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Сбор маршрутов Raketa", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val result = runDumper(project)
                    notify(project, result.first, if (result.second) NotificationType.INFORMATION else NotificationType.ERROR)
                }
            })
        }

        /** @return message and success flag */
        private fun runDumper(project: Project): Pair<String, Boolean> {
            val target = RouteIndex.getInstance(project).indexFile ?: return "Нет базового каталога проекта" to false
            val script = RefreshRoutesAction::class.java.getResourceAsStream("/dumper/dump-routes.php")
                ?.use { it.readBytes() }
                ?: return "В плагине нет dumper/dump-routes.php" to false

            // xdebug.mode=off: with a listening PhpStorm every php process in the container hangs
            val cmd = GeneralCommandLine("docker", "exec", "-i", container, "php", "-d", "xdebug.mode=off")
                .withCharset(Charsets.UTF_8)
            val handler = CapturingProcessHandler(cmd)
            handler.processInput.use { it.write(script) }
            val out = handler.runProcess(TIMEOUT_MS)
            if (out.isTimeout) return "Сборщик маршрутов не уложился в ${TIMEOUT_MS / 1000} с" to false
            if (out.exitCode != 0 || !out.stdout.trimStart().startsWith("{")) {
                val err = (out.stderr + "\n" + out.stdout).trim().take(2000)
                return "docker exec $container php завершился с кодом ${out.exitCode}:\n$err" to false
            }

            Files.createDirectories(target.parent)
            val tmp = target.resolveSibling("${target.fileName}.tmp")
            Files.writeString(tmp, out.stdout)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            val snapshot = RouteIndex.getInstance(project).snapshot()
                ?: return "Файл $target записан, но не читается" to false
            val warnings = out.stderr.lines().filter { it.isNotBlank() }
            val msg = buildString {
                append("Маршрутов: ${snapshot.routes.size}")
                val byApp = snapshot.routes.groupingBy { it.route.app }.eachCount()
                append(" (").append(byApp.entries.joinToString(", ") { "${it.key}: ${it.value}" }).append(")")
                append("\nКонсольных команд: ${snapshot.commands.size}")
                if (warnings.isNotEmpty()) append("\n").append(warnings.take(10).joinToString("\n"))
            }
            return msg to true
        }

        private fun notify(project: Project, message: String, type: NotificationType) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Raketa Routes")
                .createNotification("Индекс маршрутов", message, type)
                .notify(project)
        }
    }
}
