package org.superdro.core.ui.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.readout.DisplayUnit

class ReadoutAlignmentTest {

    @Test
    fun eachUnitBelongsToTheKindThatListsIt() {
        RowKind.entries.forEach { kind ->
            ReadoutAlignment.units(kind).forEach { unit -> assertEquals(kind, ReadoutAlignment.kind(unit)) }
        }
        assertEquals(DisplayUnit.entries.toSet(), RowKind.entries.flatMap(ReadoutAlignment::units).toSet())
    }

    @Test
    fun aLinearRowKeepsTheDecimalsOfTheInchPattern() {
        assertEquals(4, ReadoutAlignment.decimals(RowKind.Linear, ReadoutFormat.Format3))
        assertEquals(5, ReadoutAlignment.decimals(RowKind.Linear, ReadoutFormat.Format5))
        assertEquals(2, ReadoutAlignment.decimals(RowKind.Angular, ReadoutFormat.Format3))
        assertEquals(2, ReadoutAlignment.decimals(RowKind.Tachometer, ReadoutFormat.Format3))
    }

    @Test
    fun theReadoutKeepsTheMostDecimalsOfItsRows() {
        assertEquals(4, ReadoutAlignment.decimals(listOf(RowKind.Angular, RowKind.Linear), ReadoutFormat.Format3))
        assertEquals(0, ReadoutAlignment.decimals(emptyList(), ReadoutFormat.Format3))
    }

    @Test
    fun theTrailingSpacePutsTheDecimalMarksInOneColumn() {
        assertEquals(0, ReadoutAlignment.decimals("1200"))
        assertEquals(3, ReadoutAlignment.decimals("12.000"))
        assertEquals(0f, ReadoutAlignment.trailingSpace("1.0000", reservedDecimals = 4, digitWidth = 10f, markWidth = 4f))
        assertEquals(10f, ReadoutAlignment.trailingSpace("1.000", reservedDecimals = 4, digitWidth = 10f, markWidth = 4f))
        // A text with no mark (RPM) also keeps the space of the mark.
        assertEquals(44f, ReadoutAlignment.trailingSpace("1200", reservedDecimals = 4, digitWidth = 10f, markWidth = 4f))
        assertEquals(0f, ReadoutAlignment.trailingSpace("1200", reservedDecimals = 0, digitWidth = 10f, markWidth = 4f))
    }

    @Test
    fun theButtonGroupIsTheLargestOfEachDimension() {
        assertEquals(ButtonGroup(300, 3, 80), ButtonGroup(300, 2, 64).max(ButtonGroup(200, 3, 80)))
    }
}
