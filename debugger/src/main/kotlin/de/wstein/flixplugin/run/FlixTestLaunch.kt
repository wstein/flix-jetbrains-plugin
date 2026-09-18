package de.wstein.flixplugin.run

import com.intellij.execution.configurations.RemoteConnection
import de.wstein.flixplugin.FlixLaunchCommand

/** One-process JDWP launch for `flix test`: the compiler JVM is also the test JVM. */
internal class FlixTestLaunch private constructor(
    val command: List<String>,
    val remoteConnection: RemoteConnection,
) {

    companion object {
        private const val LOCALHOST = "localhost"

        /** Allocates the one port shared by the command and IntelliJ's connection. */
        fun create(command: List<String>, inheritedJavaToolOptions: String?): FlixTestLaunch =
            debug(command, FlixLaunchCommand.findFreePort(), inheritedJavaToolOptions)

        /** Builds a deterministic launch; [port] is injectable so the command is unit-testable. */
        fun debug(
            command: List<String>,
            port: Int,
            inheritedJavaToolOptions: String?,
        ): FlixTestLaunch {
            FlixLaunchCommand.requireNoInheritedJdwpAgent(inheritedJavaToolOptions)

            val jar = command.indexOf("-jar")
            require(jar > 0) { "A Flix test command must launch the compiler with -jar: $command" }
            require(
                command.subList(1, jar).none(::isJdwpAgent),
            ) { "The test JVM options already load a debug agent: $command" }

            val task = command.indexOf("test")
            require(task > jar) { "A Flix test debug command must contain the test task: $command" }
            val separator = command.indexOf("--").let { if (it < 0) command.size else it }

            val result = command.toMutableList()
            for (index in (separator - 1) downTo (task + 1)) {
                if (result[index] == "--Xdebug") result.removeAt(index)
            }
            result.add(task + 1, "--Xdebug")
            result.add(1, FlixLaunchCommand.jdwpAgent(port, true))

            return FlixTestLaunch(
                result,
                RemoteConnection(true, LOCALHOST, port.toString(), false),
            )
        }

        private fun isJdwpAgent(argument: String): Boolean =
            argument.startsWith("-agentlib:jdwp") || argument.startsWith("-Xrunjdwp")
    }
}
