package org.superdro.core.ui.functions

import org.superdro.core.ui.functions.importing.importEntries
import org.superdro.core.ui.functions.patterns.patternEntries
import org.superdro.core.ui.functions.sheets.sheetEntries
import org.superdro.core.ui.functions.subdatums.subDatumEntries
import org.superdro.core.ui.functions.thread.threadEntries
import org.superdro.core.ui.functions.tools.toolEntries
import org.superdro.core.ui.functions.workspace.workspaceEntries
import org.superdro.core.ui.navigation.EntryRegistry

/**
 * The entries of the functions area. The central provider
 * ([org.superdro.core.ui.navigation.appEntryRegistry]) calls this function. Each part of the area
 * registers its entries in its own package:
 *
 * - `patterns`: the hole patterns, the step and repeat, and the arc cut.
 * - `sheets`: the measure, compensation, Z reference, and projection sheets.
 * - `thread`: the thread setup.
 * - `tools`: the tool library, the tool edit sheet, and the tool panel.
 * - `importing`: the profile file, the import preview, and the report screen.
 * - `subdatums`: the sub-datum panel, the "+" button, the shape menu, and the sub-datum sheets.
 * - `workspace`: the workspaces, the machine datums, and the workspace transforms.
 *
 * This area also implements the profile file, the import preview, and the report of the settings.
 */
fun EntryRegistry.functionEntries() {
    patternEntries()
    sheetEntries()
    threadEntries()
    toolEntries()
    importEntries()
    subDatumEntries()
    workspaceEntries()

    // The graphics area registers the edge finder, the rotate part, and the measurement mode sheets.
}
