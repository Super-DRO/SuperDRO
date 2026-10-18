# Thread cutting spike

This document gives the research and the design for the internal and external thread cutting tools
in SuperDRO.

## 1. Decisions

These six decisions set the scope of the work:

1. **Primary scope.** Expand the lathe single-point thread setup and the live thread cut panel, and
   add a tap, drill, and clearance calculator for both mill profiles and lathe profiles.
2. **Pass schedule and infeed.** Show the pass schedule table in `ThreadSetupScreen`, and show the
   active pass target in the live `ThreadPanel`.
3. **Thread forms.** Add Acme (29°), ISO metric trapezoidal (`Tr`, 30°), British Standard Whitworth
   and BSPP parallel pipe (55°), and NPT and BSPT tapered pipe (1:16 taper) to the 60° ISO metric
   and Unified forms.
4. **Hand and starts.** Support left-hand threads (cut from −Z toward +Z) and multi-start threads
   (`Lead = starts × pitch`, with Z offset and angle indexing for each start).
5. **Machine synchronization and safety.** Show the threading dial rule from the leadscrew pitch,
   check the live Z pitch against the target lead when the tachometer is active, and show the safe
   spindle speed and the reaction time in the relief groove.
6. **Inspection and gauging.** Accept a custom wire diameter and a measured value over three wires
   to give the remaining radial depth. Apply the lead-angle correction to the three-wire formula on
   multi-start, Acme, and trapezoidal threads. Show the internal Go and No-Go pin and pitch
   diameter limits.

## 2. Current state in SuperDRO

SuperDRO has these thread functions now:

| Area | Current function | Files |
|---|---|---|
| Model | Stores a workspace thread in µm (`ThreadForm`, `ThreadClass`, `ThreadCutter`, diameters, start Z, stop Z, reliefs W₁ and W₂, and the tip radius of a partial cutter). | `core/model/src/commonMain/kotlin/org/superdro/core/model/ThreadSpec.kt` |
| Tables and tolerances | ISO 261 metric coarse (M3 to M68) and fine (M3 × 0.35 to M68 × 1.5), ASME B1.1 UNC, UNF, and UNEF, custom metric, and custom imperial. ISO 965-1 grade 6 (`6g`, `6H`) and ASME B1.1 (`2A`, `3A`, `2B`, `3B`). | `core/functions/src/commonMain/kotlin/org/superdro/core/functions/thread/ThreadTables.kt`, `core/functions/src/commonMain/kotlin/org/superdro/core/functions/thread/ClassTolerances.kt` |
| Diameter math | Calculates the pre-op diameter ("Turn to (D)" or "Bore to (D₁)") with its class range, the cut-to diameter for the cutter root radius `r`, and the three-wire values for the best wire diameter (`W = 0.5773503 × P`). | `core/functions/src/commonMain/kotlin/org/superdro/core/functions/thread/ThreadCalculator.kt`, `core/functions/src/commonMain/kotlin/org/superdro/core/functions/thread/ThreadSetup.kt` |
| Points and outline | Makes 14 to 20 sub-datums (`Z₁`, `Z₂`, `W₁`, `W₂`, `D`, `D₁`, and the extent and relief corners) and draws the thread outline in the graphical view. | `core/functions/src/commonMain/kotlin/org/superdro/core/functions/thread/ThreadPoints.kt`, `core/ui/src/commonMain/kotlin/org/superdro/core/ui/graphics/view/GraphicalSceneBuilder.kt` |
| Live cut panel | Shows the retract state (`Clear`, `Engaged`, `PastLimit`), the depth phase (`Safe`, `Cutting`, `PastFinal`), the deepest recorded pass, the radial infeed, and the chip-area load ratio. | `core/functions/src/commonMain/kotlin/org/superdro/core/functions/thread/ThreadCut.kt`, `core/ui/src/commonMain/kotlin/org/superdro/core/ui/graphics/thread/ThreadCutController.kt`, `core/ui/src/commonMain/kotlin/org/superdro/core/ui/graphics/thread/ThreadPanel.kt` |

The current code has these gaps:

- `ThreadSetupScreen` shows only on a lathe profile. A mill profile has no tap drill, roll tap, or
  clearance hole calculator.
- `ThreadSetupScreen` does not show the relief groove diameter, the entry chamfer diameter, the
  partial insert preset (`A60`, `G60`, `AG60`, `N60`), the smallest bore of an internal threading
  bar, or the lead angle of the helix.
- `ThreadCut` has no pass schedule and no compound slide infeed math.
- `ThreadCalculator.threeWire` uses only the best wire size on a 60° external thread with no lead
  angle correction, and gives no inspection values for an internal thread.
- `ThreadCutWarning.XNotReferenced` is in `ThreadCut`, but `ThreadCutController` and `ThreadPanel`
  do not show it.

## 3. Thread forms and profile geometry

Let `P` be the axial pitch in µm, `starts` (1 or more) be the number of starts, and
`Lead = starts × P` be the axial advance for one spindle turn.

### 3.1 60° ISO metric and Unified (`IsoMetricCoarse`, `IsoMetricFine`, `Unc`, `Unf`, `Unef`, `CustomMetric`, `CustomImperial`)

- Included angle `α = 60°`.
- Fundamental triangle height `H = 0.8660254 × P`.
- Basic pitch diameter `d2 = D − 0.75 × H = D − 0.6495191 × P`.
- Basic minor diameter `D1 = D − 1.25 × H = D − 1.0825318 × P`.

### 3.2 55° British Standard Whitworth and BSPP (`Bsw`, `Bsf`, `Bspp`)

- Included angle `α = 55°`.
- Fundamental triangle height `H = P / (2 × tan 27.5°) = 0.9604911 × P`.
- Profile depth `h3 = (2 / 3) × H = 0.6403274 × P`.
- Crest and root radius `r = 0.1373291 × P`.
- Basic pitch diameter `d2 = D − h3`.
- Basic minor diameter `D1 = D − 2 × h3 = D − 1.2806548 × P`.
- Tables:
  - `Bspp` (ISO 228-1, designation `G`): G 1/16 (28 TPI) to G 2 (11 TPI), external classes A and B,
    internal class G.
  - `Bsw` and `Bsf` (BS 84): 1/8 in to 2 in, medium class.

### 3.3 30° ISO metric trapezoidal (`IsoTrapezoidal`, ISO 2901 / DIN 103)

- Included angle `α = 30°`.
- Fundamental triangle height `H1 = P / (2 × tan 15°) = 1.8660254 × P`.
- Radial crest and root clearance `ac`:
  - `P = 1500 µm`: `ac = 150 µm`.
  - `2000 µm ≤ P ≤ 5000 µm`: `ac = 250 µm`.
  - `6000 µm ≤ P ≤ 12000 µm`: `ac = 500 µm`.
- Basic pitch diameter `d2 = D − 0.5 × P`.
- External minor diameter `d3 = D − (P + 2 × ac)`.
- Internal major diameter `D4 = D + 2 × ac`.
- Internal minor bore `D1 = D − P`.
- Cutter tip flat width at the root `f = 0.3660254 × P − 0.5358984 × ac`.
- Table: ISO 2902 series from `Tr 8 × 1.5` to `Tr 60 × 9`, classes `7e` (external) and `7H`
  (internal).

### 3.4 29° Acme (`Acme`, ASME B1.5)

- Included angle `α = 29°`.
- Fundamental triangle height `H = P / (2 × tan 14.5°) = 1.9333567 × P`.
- Basic depth `h = 0.5 × P`.
- Radial root and crest clearance `c`: `254 µm` (0.010 in) for 10 TPI or less, `127 µm` (0.005 in)
  for more than 10 TPI.
- Basic pitch diameter `d2 = D − 0.5 × P`.
- External minor diameter `d3 = D − P − 2 × c`.
- Internal major diameter `D4 = D + 2 × c`.
- Internal minor bore `D1 = D − P`.
- Crest flat width `Fc = 0.3706996 × P`.
- Cutter tip flat width at the root `Fr = 0.3706996 × P − 0.5172414 × c`.
- Table: ASME B1.5 general purpose sizes from `1/4-16` to `2-4`, classes `2G` and `3G`.

### 3.5 Tapered pipe threads (`Npt` 60° ASME B1.20.1, `Bspt` 55° ISO 7-1)

- Diameter taper `1:16` (`0.0625` µm per µm of Z), half-angle `β = arctan(1 / 32) = 1.78991°`
  (`6443.7` arc seconds).
- Truncation height: `0.800 × P` for `Npt`, `0.6403274 × P` for `Bspt`.
- Table values for each size from `1/16` to `2`:
  - Pipe outside diameter `D`.
  - Pitch diameter `E0` at the end of the pipe and `E1` at the hand-tight plane `L1`.
  - Effective thread length `L2`.
  - Straight tap drill diameter and tapered reamer start and end diameters.

## 4. Pre-machining preparation and tooling checks

### 4.1 Tap drill, form tap, and hole preparation (`TapAndClearanceCalculator`)

This calculator works on both mill profiles and lathe profiles:

1. **Cut tap drill diameter.** For a thread engagement percentage `pct` (50% to 85%, default 75%):
   - 60° threads: `D_drill = D − (pct / 100) × 1.2990381 × P`.
   - 55° threads: `D_drill = D − (pct / 100) × 1.2806548 × P`.
2. **Roll-form tap drill diameter.** For a 60° form tap:
   - `D_form_drill = D − (pct / 100) × 0.68 × P`.
3. **Standard drill table lookup (`StandardDrillTables`).** Gives the closest smaller and closest
   larger standard drill from the metric (0.1 mm), fractional (1/64 in), number (#80 to #1), and
   letter (A to Z) drill tables. Shows the drill diameter in mm and inch, the resulting percentage
   of thread, and whether the hole is inside the class minor diameter range `[D1min, D1max]`.
4. **Clearance hole and countersink.** Gives the close, normal, and loose clearance hole diameters
   (ISO 273 and ASME B18.2.8), the 90° entry countersink diameter (`D + 300 µm`), and the minimum
   blind-hole drill depth for taper (`9 × P`), plug (`4.5 × P`), and bottoming (`2.5 × P`) taps.

### 4.2 Relief grooves, chamfers, and cutter checks

1. **Relief groove diameter (`dg` / `Dg`).**
   - External undercut diameter: `dg = cutToDiameterUm − 2 × min(200 µm, 0.15 × P)`.
   - Internal undercut diameter: `Dg = cutToDiameterUm + 2 × min(200 µm, 0.15 × P)`.
   - Normal relief width `W1 = 3 × P`; short relief width `1.75 × P`.
2. **Partial insert presets.** When the user selects `ThreadCutter.Partial`, offer these presets
   for `cutterTipRadiusUm`:
   - `A60`: `r = 55 µm` (pitch 0.5 to 1.5 mm, 48 to 16 TPI).
   - `G60`: `r = 200 µm` (pitch 1.75 to 3.0 mm, 14 to 8 TPI).
   - `AG60`: `r = 60 µm` (pitch 0.5 to 3.0 mm, 48 to 8 TPI).
   - `N60`: `r = 400 µm` (pitch 3.5 to 5.0 mm, 7 to 5 TPI).
   - `Custom`: manual tip radius.
3. **Internal boring bar check.** Gives the largest standard internal holder size (`06IR`, `08IR`,
   `11IR`, `16IR`, `22IR`, or solid carbide micro-bar) that fits inside the pre-bore diameter `D1`,
   and warns when `lengthUm > 3.5 × D1`.
4. **Helix lead angle (`λ`).**
   - `λ = arctan(starts × P / (π × d2))`.
   - Shows `λ` in degrees. Warns when `λ > 2.5°` that the tool needs a tilted anvil or extra leading
     flank relief.

## 5. Left-hand and multi-start threads

1. **Left-hand (`leftHand: Boolean`).**
   - A right-hand thread cuts from `startZ` toward `−Z`, with the retract limit at `Zmin − W1`.
   - A left-hand thread cuts from `Zmin` toward `+Z`, with the retract limit at `Zmax + W2`.
   - `ThreadPoints.activeCorner` selects the finish corner at `Zmin` for a right-hand thread and at
     `Zmax` for a left-hand thread.
2. **Multi-start (`starts: Int`).**
   - The gearbox pitch is the lead: `Lead = starts × P` (or `TPI / starts`).
   - For each start `m` from `1` to `starts`:
     - Axial Z offset: `ΔZ_m = (m − 1) × P`.
     - Spindle angle offset: `Δθ_m = 360° × (m − 1) / starts`.
   - `ThreadPanel` shows the active start (`Start m of starts`) and resets the pass record when the
     operator switches to the next start.

## 6. Pass schedule and compound infeed (`ThreadPassSchedule`)

### 6.1 Infeed modes (`ThreadInfeedMode`)

- `Radial` (`0°`): cross-slide plunge only (`Δc = Δr`, `Δz = 0`).
- `ModifiedFlank`: compound slide set to `β_infeed = (α / 2) − 0.5°` (`29.5°` for 60°, `27.0°` for
  55°, `14.5°` for 30° Tr, `14.0°` for 29° Acme).
  - Compound travel: `Δc = Δr / cos(β_infeed)`.
  - Z travel from compound advance: `Δz = Δr × tan(β_infeed)`.
- `Flank`: compound slide set to `α / 2`.

### 6.2 Constant chip-area schedule

Let `h_total = |cutToDiameterUm − preOpDiameterUm| / 2` be the total radial depth, and let
`h_finish` be the radial depth of the finish pass (default `35 µm`).

The roughing depth is `h_rough = max(0, h_total − h_finish)`. With the chip area formula
`A(h) = tan(α / 2) × h² + w × h`, pass `k` of `N_rough` roughing passes has the cumulative radial
depth `h_k` that solves `A(h_k) = (k / N_rough) × A(h_rough)`:

```text
h_k = (-w + sqrt(w² + 4 × tan(α / 2) × (k / N_rough) × A(h_rough))) / (2 × tan(α / 2))
```

Each step `Δr_k = h_k − h_{k−1}` has a floor of `25 µm` so that the cutter does not rub. The
schedule ends with `1` finish pass at `h_total` and `1` or `2` spring passes at `h_total`.

Both `ThreadSetupScreen` and `ThreadPanel` use this schedule:

- `ThreadSetupScreen` shows the table of passes (`Pass`, `Type`, `Δr`, `Target Ø`, `Compound`).
- `ThreadPanel` compares `record.deepestUm` with the schedule and shows the current pass, the next
  target diameter, and the compound advance.

## 7. Machine synchronization and safety (`ThreadSyncRules`)

1. **Threading dial rule.** From the leadscrew pitch (`leadscrewPitchUm`) and the thread lead:
   - Different unit systems (metric thread on an inch leadscrew, or inch thread on a metric
     leadscrew): keep the half-nut closed for all passes.
   - Same unit system: gives the exact dial engagement rule (`Any point`, `Any line`,
     `Any numbered line`, `Opposite numbered lines`, or `Same line only`).
2. **Safe spindle speed and reaction time.**
   - With a stopping zone `W_stop = max(W1, 1.5 × P)` and a target reaction window `t_react`
     (1.0 s external, 1.5 s internal):
     `RPM_max = floor(60 × W_stop / (Lead × t_react))`.
   - When the tachometer is active, `ThreadPanel` calculates the carriage speed
     `v_z = (RPM / 60) × Lead` and the time in the relief groove `t_window = W_stop / v_z`.
3. **Live pitch check.** When the tachometer and the Z scale are active, `ThreadPanel` compares the
   measured Z feed per revolution with `Lead` and warns when the error is more than 3%.
4. **X reference warning (D135).** `ThreadCutController` passes `x.referenced` to `ThreadCut.values`,
   and `ThreadPanel` shows the warning when X has no reference.

## 8. Inspection and verification (`ThreadInspection`)

1. **External three-wire measurement with custom wire size and lead correction.**
   - Best wire size `W_best = P / (2 × cos(α / 2))`.
   - For a wire of diameter `W`, half-angle `θ = α / 2`, pitch diameter `E`, and lead angle
     `λ = arctan(starts × P / (π × E))`:
     `M(E, W) = E − P / (2 × tan θ) + W × (1 + 1 / sin θ + 0.5 × tan²(λ) × cos θ × cot θ)`.
   - When the user enters a measured micrometer reading `M_measured`, the calculator gives the
     actual pitch diameter `E_actual` and the remaining radial depth
     `Δr_rem = max(0, (M_measured − M_target) / 2)`.
2. **Internal thread limits.**
   - Minor diameter pin gauge limits: `Go = D1min`, `No-Go = D1max`.
   - Pitch diameter plug gauge limits: `Go = D2min`, `No-Go = D2max`.

## 9. Module plan

| Module | Changes |
|---|---|
| `core:model` | Add `Bsw`, `Bsf`, `Bspp`, `IsoTrapezoidal`, `Acme`, `Npt`, and `Bspt` to `ThreadForm`. Add `ThreadInfeedMode` and the optional fields `leftHand`, `starts`, `infeedMode`, `roughingPasses`, and `wireDiameterUm` to `ThreadSpec`. |
| `core:functions` | Extend `ThreadTables` and `ThreadCalculator`. Add `TapAndClearanceCalculator`, `StandardDrillTables`, `ThreadPassSchedule`, `ThreadSyncRules`, and `ThreadInspection`. Update `ThreadPoints` and `ThreadCut` for left-hand and multi-start threads. |
| `core:store` | Add a SQLDelight migration for the new `thread_spec` columns with default values. |
| `core:resources` | Add the new strings to `strings_functions_thread.xml` and `strings_graphics.xml` in all six languages. |
| `core:ui` | Update `ThreadSetupPresenter`, `ThreadSetupScreen`, `ThreadCutController`, `ThreadPanel`, `GraphicalSceneBuilder`, and `FunctionStripRules` (to open the thread and tap tool on mill profiles too). |
