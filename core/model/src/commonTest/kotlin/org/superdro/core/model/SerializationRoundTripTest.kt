package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Each stored entity goes to JSON and back with no change.
 */
class SerializationRoundTripTest {

    private val time = Instant.parse("2026-10-11T14:03:22.517Z")

    private inline fun <reified T> assertRoundTrip(value: T) {
        val serializer: KSerializer<T> = serializer()
        val text = SuperDroJson.encodeToString(serializer, value)
        assertEquals(value, SuperDroJson.decodeFromString(serializer, text), text)
    }

    @Test
    fun eachAdapterAddressGoesBothWays() {
        val addresses: List<AdapterAddress> = listOf(
            AdapterAddress.BluetoothSpp(mac = "24:6F:28:A1:B2:C3"),
            AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = "0001", baudRate = 9600, portIndex = 1),
            AdapterAddress.Tcp(host = "dro.local", port = 9000),
            AdapterAddress.Ble(id = "C0:FF:EE:00:00:01"),
            AdapterAddress.Fake(scenario = "mill-3-axis"),
        )
        addresses.forEach { address -> assertRoundTrip<AdapterAddress>(address) }
    }

    @Test
    fun theTcpAddressHasTheTypeField() {
        val text = SuperDroJson.encodeToString(AdapterAddress.serializer(), AdapterAddress.Tcp("10.0.10.5"))
        assertEquals("""{"type":"tcp","host":"10.0.10.5","port":8080}""", text)
    }

    @Test
    fun aFullMachineProfileGoesBothWays() {
        val subDatum = SubDatum(
            id = "s1", name = "Point 1", note = "bore", tags = listOf("holes", "Measured"),
            x = 25400.0, y = -12700.0, z = null, a = 324000.0, groupId = 7L,
            groupKind = SubDatumGroupKind.Thread, groupRef = "thread-1:z_start", createdOn = time, updatedOn = time,
        )
        val workspace = Workspace(
            id = "w1",
            name = "Part A",
            axisStates = mapOf(
                AxisId.X to AxisState(absoluteOffset = -40000.0, incrementalOffset = 12.5, relative = true, metric = true, diameterMode = true, referenced = true),
                AxisId.Angular to AxisState(divisionUnits = true, divisionsPerRev = 24),
                AxisId.Tachometer to AxisState(surfaceSpeedMode = true),
            ),
            activeSubDatumId = "s1",
            activeToolId = "t1",
            toolXUm = 6000.0,
            toolZUm = -1500.0,
            toothCount = 4,
            proximity = ProximityTarget(AxisId.Z, targetUm = -5000.0, absolute = true),
            graphical = GraphicalSwitches(snapToPoint = true, automaticZero = true),
            plane = Plane.XZ,
            referenceImage = ReferenceImage("images/a.jpg", listOf(2.0, 0.0, 5.0, 0.0, 2.0, 6.0, 0.0, 0.0, 1.0), alpha = 127),
            lastOrdinal = 3,
            subDatums = listOf(subDatum),
            guides = listOf(Guide(id = "g1", axis = AxisId.Y, valueUm = 30000.0, createdOn = time)),
            partRotation = PartRotation(active = true, angleArcSec = 108000.0, pivotXUm = 10000.0),
            axisAlignment = AxisAlignment(active = true, angleArcSec = -7.5, source = AxisAlignmentSource.MeasuredLine),
            armedThreadId = "thread-1",
            createdOn = time,
            updatedOn = time,
        )
        val profile = MachineProfile(
            id = "p1",
            name = "Bridgeport",
            machineType = MachineType.HorizontalMill,
            metricDefault = true,
            adapter = AdapterAddress.UsbSerial(vendorId = 0x1A86, productId = 0x7523, serial = null),
            adapterProfileId = "a1",
            machineMode = MachineMode.Measurement,
            patternAngleConvention = PatternAngleConvention.Legacy,
            auxiliary = AuxiliaryConfig(AuxiliaryType.CompoundSlide, vectoring = true, angleReferenceDegrees = 90),
            probeRoles = ProbeRoles("pr1", "pr2", "pr3"),
            machineDatums = listOf(MachineDatum("d1", "Vise", "", 152400.0, 76200.0, -12700.0, null, time, time)),
            dataOutput = DataOutputConfig(enabled = true, security = DataOutputSecurity.PlainText, port = 6000),
            cameraId = "1d6b:0102",
            twoWayCommunication = false,
            connectAtStart = true,
            adapterConfigurationCommands = true,
            useSharedToolLibrary = true,
            muteSounds = true,
            display = DisplaySettings(
                readoutFormat = ReadoutFormat.Format5,
                readoutFont = ReadoutFont.Cousine,
                showSliders = false,
                readoutPaneFraction = 0.58f,
                graphicalPaneWeight = 0.42f,
            ),
            functionButtons = FunctionButtonSettings(arcCut = false),
            x = LinearAxisConfig.forAxis(AxisId.X).copy(secondary = InputConfig('w', sign = -1, weight = 0.5)),
            angular = AngularAxisConfig(enabled = true),
            caliper = CaliperConfig(enabled = true),
            tachometer = TachometerConfig(enabled = true, mode = TachometerMode.Preset),
            probe = ProbeConfig(enabled = true, channel = 'q'),
            use3dSubDatums = false,
            sidePanel = SidePanelMode.Tools,
            stylusDiameterUm = 4000.0,
            stylusMetric = true,
            zSetterHeightUm = 50000.0,
            zSetterMetric = false,
            leadscrewPitchUm = 3175.0,
            workspaces = listOf(workspace),
            activeWorkspaceId = "w1",
            tools = listOf(Tool(id = "t1", kind = ToolKind.Mill, name = "6 mm end mill", diameterUm = 6000.0, toothCount = 4, createdOn = time, updatedOn = time)),
            createdOn = time,
            updatedOn = time,
        )
        assertRoundTrip(profile)
    }

    @Test
    fun aLatheToolWithGeometryGoesBothWays() {
        val shape = DrawingShape(type = ShapeType.Arc, x1 = 1_000_000L, y1 = -2_000L, radius = 400_000L, startAngle = 0.5, sweepAngle = -1.0)
        assertRoundTrip(
            Tool(
                id = "t2", kind = ToolKind.Lathe, name = "Boring bar", internal = true, xOffsetUm = 120.0, zOffsetUm = -35.5,
                geometry = ToolGeometry(listOf(shape), DrawingSource("bar.dxf", DrawingFormat.BinaryDxf)),
                createdOn = time, updatedOn = time,
            )
        )
    }

    @Test
    fun theAppSettingsAndTheTabStateGoBothWays() {
        assertRoundTrip(
            AppSettings(
                themeMode = ThemeMode.Dark,
                graphicalViewInPortrait = GraphicalViewPlacement.FullScreen,
                readoutPaneFraction = 0.55f,
                graphicalPaneWeight = 0.45f,
                paneSizeScope = PaneSizeScope.EachMachine,
                largeButtons = true,
                muteAllSounds = true,
                muteScope = MuteScope.EachMachine,
                fullScreen = false,
                wakeLockWhileLinked = false,
                showHints = false,
                showSetupReminder = false,
                sendGetSigOnce = true,
                hiddenLinkTypes = setOf(TransportKind.Ble),
                shownHints = setOf("hold_to_zero"),
                keyMappings = listOf(KeyMapping(keyCode = 66L, deviceDescriptor = "pedal", action = KeyAction.ZeroAxis, axis = AxisId.X)),
            )
        )
        assertRoundTrip(TabState(remembered = listOf(RememberedMachine("p1"), RememberedMachine("p2", paused = true)), selectedProfileId = "p2"))
    }

    @Test
    fun anAdapterProfileWithMapsGoesBothWays() {
        val encoder = EncoderProfile.defaultFor('x').copy(
            label = "X scale",
            reversed = true,
            filter = true,
            backlashMode = BacklashMode.Constant,
            backlashCounts = 3L,
            errorMap = listOf(MapPoint(0L, 0L), MapPoint(10000L, 5L), MapPoint(20000L, -2L)),
            calibration = Calibration(CalibrationStatus.Calibrated, time, MachineType.VerticalMill, AxisId.X, "quadrature"),
        )
        assertRoundTrip(
            AdapterProfile(
                id = "a1", alias = "Mill adapter", model = "tda-esp32", identity = "24:6f:28:a1:b2:c5",
                identitySource = IdentitySource.AdapterReported, legacy = false,
                lastLink = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"),
                declaredInputs = setOf(DeclaredInput.X, DeclaredInput.Probe),
                probeContactType = ProbeContactType.NormallyOpen, probeDebounceMs = 50L,
                encoders = mapOf('x' to encoder, 't' to EncoderProfile.defaultFor('t')),
                lastSeenOn = time, createdOn = time,
            )
        )
    }

    @Test
    fun aProbeProfileGoesBothWays() {
        assertRoundTrip(
            ProbeProfile(
                id = "pr1", profileId = null, type = ProbeType.EdgeFinder2D, name = "4 mm", port = 'p',
                stylusDiameterUm = 4000.0, effectiveDiameterUm = 3990.0, xOffsetUm = 1.5, sampleCount = 3,
                spreadLimitUm = null, calibrationResidualUm = 0.8, calibratedOn = time, lastUsedOn = time,
            )
        )
    }

    @Test
    fun aDrawingAThreadALogEntryAndTheCameraRecordsGoBothWays() {
        assertRoundTrip(
            Drawing(
                id = "dr1", workspaceId = "w1",
                layers = listOf(DrawingLayer(0, "0"), DrawingLayer(1, "Holes", hidden = true)),
                shapes = listOf(
                    DrawingShape(ShapeType.Line, x1 = 0L, y1 = 0L, x2 = 25_400_000L, y2 = 0L),
                    DrawingShape(ShapeType.Polyline, layerIndex = 1, closed = true, points = listOf(0L, 0L, 1000L, 0L, 1000L, 1000L), bulges = mapOf(1 to 0.414)),
                ),
                source = DrawingSource("part.dxf", DrawingFormat.AsciiDxf),
                createdOn = time,
            )
        )
        assertRoundTrip(
            ThreadSpec(
                id = "th1", workspaceId = "w1", tag = "M10", form = ThreadForm.IsoMetricCoarse, size = "M10 × 1.5",
                threadClass = ThreadClass.Class6g, cutter = ThreadCutter.Partial, pitchUm = 1500.0,
                preOpDiameterUm = 9968.0, cutToDiameterUm = 7695.0, startZUm = 0.0, stopZUm = -15500.0,
                leftReliefUm = 4500.0, cutterTipRadiusUm = 55.0, leftHand = true, starts = 2,
                infeedMode = ThreadInfeedMode.Flank, roughingPasses = 12, wireDiameterUm = 866.0,
                insertPreset = PartialInsertPreset.A60, createdOn = time, updatedOn = time,
            )
        )
        ThreadForm.entries.forEach { form -> assertRoundTrip(form) }
        ThreadClass.entries.forEach { threadClass -> assertRoundTrip(threadClass) }
        ThreadInfeedMode.entries.forEach { mode -> assertRoundTrip(mode) }
        PartialInsertPreset.entries.forEach { preset -> assertRoundTrip(preset) }
        assertRoundTrip(
            MeasurementLogEntry(
                id = "m1", workspaceId = "w1", sequence = 1, verb = "circle_from_3_points", title = "Circle from 3 points",
                results = listOf(LoggedResult("center", x = 20000.0, y = 15000.0), LoggedResult("diameter", value = 50000.0)),
                points = listOf(CollectedPoint(0.0, 0.0, -1250.0), CollectedPoint(40000.0, 0.0, -1250.0, PointSource.Probe, approachX = 1)),
                notes = listOf("UNCOMPENSATED"), note = "Bore A", workspaceName = "Default", createdOn = time,
            )
        )
        assertRoundTrip(CameraModeResult("1d6b:0102", "mjpeg-1280x720-30", CameraModeStatus.Bad, 1280, 720, "MJPEG", 29.9, time))
        assertRoundTrip(CameraSelection("p1", "1d6b:0102", "mjpeg-1280x720-30", time))
    }

    @Test
    fun aDataOutputConfigurationGoesBothWays() {
        assertRoundTrip(
            DataOutputConfig(
                enabled = true, bindInterface = BindInterface.All, port = 6000, transport = DataOutputTransport.Mqtt,
                mqttBrokerHost = "broker.local", mqttClientId = "superdro-mill", mqttMachineName = "mill-1",
                mqttTopicLayout = MqttTopicLayout.TopicPerField, format = DataOutputFormat.Json,
                delimiter = DataOutputDelimiter.Tab, labels = true, timestampFormat = TimestampFormat.EpochMs,
                distanceUnit = DistanceUnit.Inch, angleUnit = AngleUnit.Radians, precision = 6,
                cadence = DataOutputCadence.FixedInterval, intervalMs = 50L, maxRateHz = 1,
                activation = DataOutputActivation.AlwaysOn,
            )
        )
    }
}
