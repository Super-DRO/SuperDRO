package org.superdro.core.ui.navigation

import org.superdro.core.ui.adapter.adapterEntries
import org.superdro.core.ui.camera.cameraEntries
import org.superdro.core.ui.dataoutput.dataOutputEntries
import org.superdro.core.ui.functions.functionEntries
import org.superdro.core.ui.graphics.graphicsEntries
import org.superdro.core.ui.mcp.mcpEntries
import org.superdro.core.ui.readout.readoutEntries
import org.superdro.core.ui.settings.settingsEntries
import org.superdro.core.ui.tabs.tabsEntries

/**
 * The central entry provider of SuperDRO. It calls the entry function of
 * each area, then the entries of the frame (the tabs and the readout). A later registration of a
 * route type replaces an earlier one, so the frame entries come last.
 *
 * The data output, the MCP server, and the camera have their own entry files. Their routes are in
 * the files of the settings and the graphics areas, so their functions come after the four area
 * functions. The MCP chip comes after the data output chip in the status strip.
 */
fun appEntryRegistry(): EntryRegistry = EntryRegistry().apply {
    settingsEntries()
    adapterEntries()
    functionEntries()
    graphicsEntries()
    dataOutputEntries()
    mcpEntries()
    cameraEntries()
    tabsEntries()
    readoutEntries()
}
