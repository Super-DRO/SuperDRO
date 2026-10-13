package org.superdro.core.store.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.jdbcProperties
import org.superdro.core.store.valueOrNull

/** The offsets stay after an app restart: a second driver on the same file reads them. */
class RestartTest {

    private val directory: File = Files.createTempDirectory("superdro-restart").toFile()
    private val url = "jdbc:sqlite:${File(directory, "superdro.db").absolutePath}"

    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    private fun fileDriver() = JdbcSqliteDriver(url, jdbcProperties(), SuperDroDatabase.Schema)

    @Test
    fun theOffsetsStayAfterARestart() {
        val firstScope = TestScope()
        val first = StoreFixture(firstScope, fileDriver())
        lateinit var workspaceId: String
        firstScope.runTest {
            val profile = first.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
            workspaceId = profile.activeWorkspaceId
            first.machines.setAxisState(workspaceId, AxisId.X, AxisState(absoluteOffset = -25400.0, incrementalOffset = 1270.0, relative = true, metric = true))
            first.machines.setAxisState(workspaceId, AxisId.Z, AxisState(toolOffset = 3000.0, toolOffsetActive = true))
        }
        first.close()

        val secondScope = TestScope()
        val second = StoreFixture(secondScope, fileDriver())
        secondScope.runTest {
            val states = second.machines.loadWorkspace(workspaceId)!!.axisStates
            assertEquals(AxisState(absoluteOffset = -25400.0, incrementalOffset = 1270.0, relative = true, metric = true), states.getValue(AxisId.X))
            assertEquals(AxisState(toolOffset = 3000.0, toolOffsetActive = true), states.getValue(AxisId.Z))
            assertEquals(listOf("Mill"), second.database.machineProfileQueries.selectAll().executeAsList().map { it.name })
        }
        second.close()
    }
}
