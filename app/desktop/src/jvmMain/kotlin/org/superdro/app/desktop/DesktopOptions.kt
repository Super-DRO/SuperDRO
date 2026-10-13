package org.superdro.app.desktop

import java.io.File

/**
 * The command line arguments of the desktop app.
 *
 * @property demo `--demo`: the demo entry. The graph binds the fake adapter, and the two
 *   fake machines connect. With no `--demo`, the app has no fake adapter and no demo links.
 * @property dataDirectory `--data-dir=<folder>` or `--data-dir <folder>`: the application data
 *   directory. Null gives the directory of the operating system.
 * @property exitAfterMs `--exit-after-ms=<n>`: the window closes after this time. Only the
 *   automatic test of the window uses it.
 * @property help `--help`: the app writes [USAGE] and stops.
 * @property unknown The arguments that the app does not know. The app writes them to the log.
 */
data class DesktopOptions(
    val demo: Boolean = false,
    val dataDirectory: File? = null,
    val exitAfterMs: Long? = null,
    val help: Boolean = false,
    val unknown: List<String> = emptyList(),
) {
    companion object {
        /** The text of `--help`. */
        const val USAGE: String =
            "Usage: superdro [--demo] [--data-dir=<folder>] [--exit-after-ms=<n>] [--help]\n" +
                "  --demo                 Connect the two fake demo machines.\n" +
                "  --data-dir=<folder>    Keep the database and the settings in <folder>.\n" +
                "  --exit-after-ms=<n>    Close the window after <n> ms (for a test).\n" +
                "  --help                 Show this text."

        private const val DEMO = "--demo"
        private const val DATA_DIR = "--data-dir"
        private const val EXIT_AFTER = "--exit-after-ms"
        private const val HELP = "--help"

        /** Reads [args]. An argument that the app does not know goes to [DesktopOptions.unknown]. */
        fun parse(args: Array<String>): DesktopOptions {
            var options = DesktopOptions()
            val unknown = mutableListOf<String>()
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                val name = arg.substringBefore('=')
                val inline = if ('=' in arg) arg.substringAfter('=') else null
                when (name) {
                    DEMO -> options = options.copy(demo = true)
                    HELP, "-h" -> options = options.copy(help = true)
                    DATA_DIR, EXIT_AFTER -> {
                        // The next argument is the value, unless it is a different option.
                        val value = inline ?: args.getOrNull(index + 1)?.takeUnless { next -> next.startsWith("--") }?.also { index += 1 }
                        options = when {
                            value.isNullOrBlank() -> options.also { unknown += arg }
                            name == DATA_DIR -> options.copy(dataDirectory = File(value))
                            else -> value.toLongOrNull()?.takeIf { ms -> ms > 0 }?.let { ms -> options.copy(exitAfterMs = ms) }
                                ?: options.also { unknown += arg }
                        }
                    }
                    else -> unknown += arg
                }
                index += 1
            }
            return options.copy(unknown = unknown)
        }
    }
}
