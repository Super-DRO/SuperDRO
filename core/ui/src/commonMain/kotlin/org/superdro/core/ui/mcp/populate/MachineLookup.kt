package org.superdro.core.ui.mcp.populate

import kotlinx.coroutines.flow.first
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.Tool
import org.superdro.core.model.Workspace
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ToolRepository

/**
 * Finds a profile, a workspace, or a tool by its ID or by its name. The ID match comes first.
 * A name match ignores the case and the spaces at the ends. A name that two records share is an
 * error, so the agent must use the ID.
 */
class MachineLookup(private val machines: MachineRepository, private val tools: ToolRepository) {

    /** The settings of the profile [machine] (an ID or a name). A profile that is not there throws [ToolError]. */
    suspend fun profile(machine: String): MachineProfile {
        val key = machine.trim()
        machines.loadProfile(key)?.let { return it }
        val all = machines.profiles().first()
        return byName(all, key, MachineProfile::name, "machine", machine)
    }

    /** The full profile of [machine]: the workspaces, the tools, and the machine datums. */
    suspend fun fullProfile(machine: String): MachineProfile =
        machines.loadFullProfile(profile(machine).id) ?: toolError("The machine \"$machine\" is not there.")

    /** The full workspace [workspace] (an ID or a name) of the profile [profileId]. */
    suspend fun workspace(profileId: String, workspace: String): Workspace {
        val key = workspace.trim()
        val all = machines.workspaces(profileId).first()
        val match = all.firstOrNull { candidate -> candidate.id == key } ?: byName(all, key, Workspace::name, "workspace", workspace)
        return machines.loadWorkspace(match.id) ?: toolError("The workspace \"$workspace\" is not there.")
    }

    /** The tool [tool] (an ID or a name) of the library that the profile [profileId] uses. */
    suspend fun tool(profileId: String, tool: String): Tool {
        val key = tool.trim()
        val library = tools.loadLibrary(profileId)
        return library.firstOrNull { candidate -> candidate.id == key } ?: byName(library, key, Tool::name, "tool", tool)
    }

    private fun <T> byName(all: List<T>, key: String, name: (T) -> String, kind: String, text: String): T {
        val matches = all.filter { candidate -> name(candidate).trim().equals(key, ignoreCase = true) }
        return when (matches.size) {
            0 -> toolError("No $kind has the ID or the name \"$text\".")
            1 -> matches.single()
            else -> toolError("More than one $kind has the name \"$text\". Use the ID.")
        }
    }
}
