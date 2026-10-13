package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.model.Workspace
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.valueOrNull

/** The machine type change: the list of the data that does not fit, the conversion, and the archive. */
class MachineTypeChangeTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    private suspend fun mill(): MachineProfile {
        val workspace = Workspace.forMachine(MachineType.VerticalMill).copy(
            subDatums = listOf(
                SubDatum(id = "xy", name = "XY", x = 1000.0, y = 2000.0),
                SubDatum(id = "y-only", name = "Y only", y = 3000.0),
                SubDatum(id = "xz", name = "XZ", x = 4000.0, z = -500.0),
            ),
            guides = listOf(Guide(id = "gy", axis = AxisId.Y, valueUm = 10.0), Guide(id = "gx", axis = AxisId.X, valueUm = 20.0)),
        )
        val profile = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(
            machineMode = MachineMode.Measurement,
            workspaces = listOf(workspace),
            activeWorkspaceId = workspace.id,
            tools = listOf(Tool(id = "end-mill", kind = ToolKind.Mill, name = "End mill")),
            machineDatums = listOf(MachineDatum(id = "md", name = "Vise", x = 1.0, y = 2.0, z = 3.0)),
        )
        return store.machines.createProfile(profile).valueOrNull()!!
    }

    @Test
    fun aChangeBetweenTheMillTypesKeepsTheMillTools() = scope.runTest {
        val profile = mill()
        val plan = store.machines.planMachineTypeChange(profile.id, MachineType.HorizontalMill)!!
        assertEquals(emptyList(), plan.mismatches)
        assertEquals(listOf(ConversionKind.SharedMillTools), plan.conversions)
        store.machines.changeMachineType(profile.id, MachineType.HorizontalMill)
        assertEquals(MachineType.HorizontalMill, store.machines.loadProfile(profile.id)!!.machineType)
        assertEquals(listOf("End mill"), store.tools.loadLibrary(profile.id).map { it.name })
    }

    @Test
    fun theConfirmationListsTheDataThatDoesNotFitALathe() = scope.runTest {
        val profile = mill()
        val plan = store.machines.planMachineTypeChange(profile.id, MachineType.Lathe)!!
        assertEquals(
            listOf(
                Mismatch(MismatchKind.ProfileTools, 1),
                Mismatch(MismatchKind.SubDatumYCoordinates, 2),
                Mismatch(MismatchKind.YGuides, 1),
                Mismatch(MismatchKind.MachineDatumYCoordinates, 1),
            ),
            plan.mismatches,
        )
        assertEquals(
            listOf(ConversionKind.WorkspacePlane, ConversionKind.DiameterMode, ConversionKind.PatternAngleConvention, ConversionKind.MachineMode),
            plan.conversions,
        )
    }

    @Test
    fun theChangeConvertsAndArchivesAndAChangeBackRestores() = scope.runTest {
        val profile = mill()
        store.machines.changeMachineType(profile.id, MachineType.Lathe)

        val lathe = store.machines.loadFullProfile(profile.id)!!
        assertEquals(MachineType.Lathe, lathe.machineType)
        assertEquals(MachineMode.Cutting, lathe.machineMode)
        assertEquals(PatternAngleConvention.Legacy, lathe.patternAngleConvention)
        val workspace = lathe.workspaces.single()
        assertEquals(Plane.ZX, workspace.plane)
        assertTrue(workspace.axisStates.getValue(AxisId.X).diameterMode)
        assertEquals(listOf("XY" to null, "XZ" to null), workspace.subDatums.map { it.name to it.y })
        assertEquals(listOf("gx"), workspace.guides.map { it.id })
        assertNull(lathe.machineDatums.single().y)
        assertEquals(emptyList(), lathe.tools)
        // The archive keeps the hidden data in the database.
        assertEquals(listOf("Tool", "SubDatumY", "SubDatum", "Guide", "MachineDatumY"), store.machines.archivedItems(profile.id).first().map { it.kind })

        // A lathe thread does not fit a mill. A change back restores the mill data.
        store.machines.saveThread(workspace.id, ThreadSpec(id = "thread", size = "M10 × 1.5"))
        val back = store.machines.planMachineTypeChange(profile.id, MachineType.VerticalMill)!!
        assertEquals(listOf(Mismatch(MismatchKind.LatheThreads, 1)), back.mismatches)
        assertTrue(ConversionKind.RestoreFromArchive in back.conversions)
        store.machines.changeMachineType(profile.id, MachineType.VerticalMill)

        val millAgain = store.machines.loadFullProfile(profile.id)!!
        assertEquals(PatternAngleConvention.Standard, millAgain.patternAngleConvention)
        assertEquals(Plane.XY, millAgain.workspaces.single().plane)
        assertEquals(false, millAgain.workspaces.single().axisStates.getValue(AxisId.X).diameterMode)
        assertEquals(setOf("xy" to 2000.0, "xz" to null, "y-only" to 3000.0), millAgain.workspaces.single().subDatums.map { it.id to it.y }.toSet())
        assertEquals(setOf("gx", "gy"), millAgain.workspaces.single().guides.map { it.id }.toSet())
        assertEquals(2.0, millAgain.machineDatums.single().y)
        assertEquals(listOf("End mill"), millAgain.tools.map { it.name })
        assertEquals(emptyList(), store.machines.threads(workspace.id).first())
        assertEquals(listOf("ThreadSpec"), store.machines.archivedItems(profile.id).first().map { it.kind })
    }

    @Test
    fun theDeleteDisposalKeepsNoArchive() = scope.runTest {
        val profile = mill()
        store.machines.changeMachineType(profile.id, MachineType.Lathe, MismatchDisposal.Delete)
        assertEquals(emptyList(), store.machines.archivedItems(profile.id).first())
        assertEquals(0L, store.count("tool"))
        assertEquals(2L, store.count("sub_datum"))
    }
}
