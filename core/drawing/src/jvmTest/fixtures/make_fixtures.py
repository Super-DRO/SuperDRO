#!/usr/bin/env python3
"""Makes the DXF test fixtures of core:drawing with the Python library ezdxf (a test tool only).

Each drawing is written two times: as ASCII DXF (``<name>.dxf``) and as binary DXF
(``<name>.dxb``). The two files hold the same drawing. The two files must give the same
wireframe result.

Run the script with a Python that has ezdxf 1.4.4, from the root of the repository:

    <venv>/bin/python core/drawing/src/jvmTest/fixtures/make_fixtures.py

The script writes into ``core/drawing/src/jvmTest/resources``. It also writes
``curves_r2000_mm.points.json``: points on each curve of the curve fixture, from the evaluation
of ezdxf, in drawing units. The tests compare the Bezier chains of SuperDRO with these points.
"""

import gzip
import json
import math
import os
import pathlib
import struct
import sys
import zlib

# ezdxf writes some sets (for example the CLASSES section) in the order of the string hash. A
# fixed hash seed makes the files the same at each run.
if os.environ.get("PYTHONHASHSEED") != "0":
    os.execve(sys.executable, [sys.executable] + sys.argv, dict(os.environ, PYTHONHASHSEED="0"))

import ezdxf
from ezdxf import units
from ezdxf.math import Vec3, closed_uniform_bspline

ezdxf.options.write_fixed_meta_data_for_testing = True

ROOT = pathlib.Path(__file__).resolve().parents[1] / "resources"
DXF_DIR = ROOT / "dxf"
OTHER_DIR = ROOT / "other"


def save_twins(doc, name):
    """Writes the ASCII file and the binary twin of the same document."""
    DXF_DIR.mkdir(parents=True, exist_ok=True)
    doc.saveas(DXF_DIR / f"{name}.dxf")
    doc.saveas(DXF_DIR / f"{name}.dxb", fmt="bin")


def entities_r2000_mm():
    """Each entity type of the converter, layers with on, off, and frozen, line types, XDATA."""
    doc = ezdxf.new("R2000", setup=True)
    doc.units = units.MM
    doc.header["$MEASUREMENT"] = 1
    doc.layers.add("OUTLINE", color=7)
    doc.layers.add("HOLES", color=1)
    doc.layers.add("CENTERLINES", color=3, linetype="CENTER")
    # The table of ezdxf has no HIDDEN line type: the layer gets the style 1 (Continuous).
    doc.layers.add("HIDDEN_EDGES", color=4, linetype="HIDDEN")
    doc.layers.add("DASHED_EDGES", color=4, linetype="DASHED2")
    off = doc.layers.add("OFF_LAYER", color=5)
    off.off()
    frozen = doc.layers.add("FROZEN", color=6)
    frozen.freeze()
    doc.layers.add("NOTES", color=2)
    msp = doc.modelspace()

    line = msp.add_line((0, 0), (50, 0), dxfattribs={"layer": "OUTLINE"})
    line.set_xdata("SUPERDRO_TEST", [(1000, "xdata text"), (1040, 1.5)])
    msp.add_line((0, 0), (0, 30), dxfattribs={"layer": "OUTLINE", "linetype": "DASHED"})
    msp.add_line((25, -5), (25, 35), dxfattribs={"layer": "CENTERLINES"})
    msp.add_line((5, 25), (45, 25), dxfattribs={"layer": "HIDDEN_EDGES"})
    msp.add_line((5, 27), (45, 27), dxfattribs={"layer": "DASHED_EDGES"})
    msp.add_circle((10, 10), 3.175, dxfattribs={"layer": "HOLES"})
    msp.add_arc((25, 15), 5, 0, 90, dxfattribs={"layer": "OUTLINE"})
    msp.add_arc((30, 15), 4, 270, 45, dxfattribs={"layer": "OUTLINE"})
    # An arc with the extrusion (0, 0, -1): the OCS X axis is the WCS -X axis.
    msp.add_arc((-40, 15), 4, 10, 100, dxfattribs={"layer": "OUTLINE", "extrusion": (0, 0, -1)})
    msp.add_point((5, 5), dxfattribs={"layer": "OUTLINE"})
    msp.add_lwpolyline(
        [(0, 40, 0, 0, 0), (50, 40, 0, 0, 0.41421356237309503), (60, 50, 0, 0, 0), (0, 50, 0, 0, 0)],
        format="xyseb",
        close=True,
        dxfattribs={"layer": "OUTLINE"},
    )
    # An open polyline: the bulge of the last vertex goes away.
    msp.add_lwpolyline(
        [(70, 0, 0, 0, -0.5), (80, 0, 0, 0, 0), (80, 10, 0, 0, 1.0)],
        format="xyseb",
        dxfattribs={"layer": "OUTLINE"},
    )
    msp.add_polyline2d(
        [(0, 60, 0, 0, 0.25), (20, 60, 0, 0, 0), (20, 70, 0, 0, 0)],
        format="xyseb",
        close=True,
        dxfattribs={"layer": "OUTLINE"},
    )
    msp.add_polyline3d([(30, 60, 1), (40, 65, 2), (50, 60, 3)], dxfattribs={"layer": "OUTLINE"})
    msp.add_ellipse((30, 40), major_axis=(10, 0), ratio=0.5, dxfattribs={"layer": "OUTLINE"})
    msp.add_ellipse((60, 40), major_axis=(5, 5), ratio=0.4, start_param=0, end_param=math.pi / 2, dxfattribs={"layer": "OUTLINE"})
    msp.add_open_spline([(0, 80), (10, 90), (20, 80), (30, 90), (40, 80)], degree=3, dxfattribs={"layer": "OUTLINE"})
    hatch = msp.add_hatch(color=2, dxfattribs={"layer": "OUTLINE"})
    hatch.paths.add_polyline_path([(100, 0, 0), (110, 0, 0.5), (110, 10, 0), (100, 10, 0)], is_closed=True)
    edge = hatch.paths.add_edge_path()
    edge.add_line((120, 0), (130, 0))
    edge.add_arc((125, 0), 5, 0, 180)
    msp.add_text("NOTE", dxfattribs={"layer": "NOTES", "height": 2.5}).set_placement((0, -10))
    msp.add_mtext("Multi line\\Ptext", dxfattribs={"layer": "NOTES"}).set_location((0, -20))
    dim = msp.add_linear_dim(base=(0, -5), p1=(0, 0), p2=(50, 0), dxfattribs={"layer": "NOTES"})
    dim.render()
    msp.add_line((0, 100), (10, 100), dxfattribs={"layer": "OFF_LAYER"})
    msp.add_line((0, 110), (10, 110), dxfattribs={"layer": "FROZEN"})
    # A paper space entity: the converter ignores it.
    doc.paperspace().add_line((0, 0), (100, 100))
    save_twins(doc, "entities_r2000_mm")


def curves_r2000_mm():
    """ELLIPSE and SPLINE entities, and the reference points of ezdxf."""
    doc = ezdxf.new("R2000", setup=True)
    doc.units = units.MM
    msp = doc.modelspace()
    curves = []
    curves.append(msp.add_ellipse((0, 0), major_axis=(20, 0), ratio=0.5))
    curves.append(msp.add_ellipse((50, 0), major_axis=(10, 10), ratio=0.3, start_param=0.5, end_param=4.0))
    curves.append(msp.add_ellipse((100, 0), major_axis=(1000, 0), ratio=0.25))
    curves.append(msp.add_open_spline([(0, 50), (10, 70), (30, 40), (50, 70), (70, 50)], degree=3))
    curves.append(msp.add_open_spline([(0, 100), (20, 130), (40, 100), (60, 130)], degree=2))
    curves.append(msp.add_open_spline([(0, 150), (10, 160), (20, 140), (30, 170), (40, 140), (50, 160), (60, 150)], degree=5))
    w = math.sqrt(2) / 2
    curves.append(msp.add_rational_spline([(20, 200), (20, 220), (0, 220)], [1, w, 1], degree=2, knots=[0, 0, 0, 1, 1, 1]))
    closed = closed_uniform_bspline([(100, 100), (120, 100), (120, 120), (100, 120)], order=4)
    spline = msp.add_spline()
    spline.apply_construction_tool(closed)
    spline.closed = True
    curves.append(spline)
    references = []
    for curve in curves:
        if curve.dxftype() == "ELLIPSE":
            params = [curve.dxf.start_param + (curve.dxf.end_param - curve.dxf.start_param) * i / 64 for i in range(65)]
            if curve.dxf.end_param <= curve.dxf.start_param:
                params = [curve.dxf.start_param + (curve.dxf.end_param + 2 * math.pi - curve.dxf.start_param) * i / 64 for i in range(65)]
            points = list(curve.vertices(params))
        else:
            # The domain of the curve is knots[degree] to knots[count]. ezdxf also evaluates
            # outside the domain of an unclamped (closed) curve, so the script gives the domain.
            tool = curve.construction_tool()
            knots = list(tool.knots())
            low, high = knots[tool.degree], knots[tool.count]
            points = list(tool.points([low + (high - low) * i / 128 for i in range(129)]))
        references.append({"type": curve.dxftype(), "points": [[p.x, p.y] for p in points]})
    save_twins(doc, "curves_r2000_mm")
    (DXF_DIR / "curves_r2000_mm.points.json").write_text(json.dumps(references, indent=1))


def fit_spline_r2000_mm():
    """A SPLINE with only fit points."""
    doc = ezdxf.new("R2000")
    doc.units = units.MM
    msp = doc.modelspace()
    spline = msp.add_spline([(0, 0), (10, 15), (25, 5), (40, 20)])
    assert spline.control_point_count() == 0
    save_twins(doc, "fit_spline_r2000_mm")


def blocks_r2000_inch():
    """Blocks with nesting (3 levels), scale, rotation, mirror, arrays, layer 0, and BYBLOCK."""
    doc = ezdxf.new("R2000", setup=True)
    doc.units = units.IN
    doc.header["$MEASUREMENT"] = 0
    doc.layers.add("PARTS", color=1, linetype="CENTER")
    doc.layers.add("BLOCKLINES", color=2)
    bolt = doc.blocks.new("BOLT", base_point=(1, 1))
    bolt.add_circle((1, 1), 0.125, dxfattribs={"layer": "0"})
    bolt.add_line((0.75, 1), (1.25, 1), dxfattribs={"layer": "BLOCKLINES", "linetype": "BYBLOCK"})
    plate = doc.blocks.new("PLATE")
    plate.add_lwpolyline([(0, 0), (4, 0), (4, 2), (0, 2)], close=True, dxfattribs={"layer": "0"})
    for x, y in ((0.5, 0.5), (3.5, 0.5), (3.5, 1.5), (0.5, 1.5)):
        plate.add_blockref("BOLT", (x, y))
    assembly = doc.blocks.new("ASSEMBLY")
    assembly.add_blockref("PLATE", (0, 0), dxfattribs={"xscale": 2, "yscale": 2, "rotation": 30})
    assembly.add_line((0, 0), (1, 0))
    msp = doc.modelspace()
    msp.add_blockref("ASSEMBLY", (10, 10), dxfattribs={"layer": "PARTS"})
    msp.add_blockref("BOLT", (20, 0), dxfattribs={"xscale": 2, "yscale": 1})
    msp.add_blockref("BOLT", (25, 0), dxfattribs={"xscale": -1, "yscale": 1})
    grid = msp.add_blockref("BOLT", (0, 30), dxfattribs={"linetype": "DASHED2"})
    grid.grid(size=(2, 3), spacing=(0.5, 1.0))
    save_twins(doc, "blocks_r2000_inch")


def nesting(levels, name):
    """A chain of [levels] nested blocks."""
    doc = ezdxf.new("R2000")
    doc.units = units.MM
    for level in range(levels, 0, -1):
        block = doc.blocks.new(f"L{level}")
        block.add_line((0, 0), (level, 0))
        if level < levels:
            block.add_blockref(f"L{level + 1}", (0, 1))
    doc.modelspace().add_blockref("L1", (0, 0))
    save_twins(doc, name)


def r12_mm():
    """DXF R12 (AC1009). ezdxf writes the binary twin with 1-byte group codes."""
    doc = ezdxf.new("R12")
    doc.layers.add("OUTLINE", color=7)
    block = doc.blocks.new("MARK")
    block.add_circle((0, 0), 1)
    block.add_line((-1, 0), (1, 0))
    msp = doc.modelspace()
    msp.add_line((0, 0), (50, 0), dxfattribs={"layer": "OUTLINE"})
    msp.add_circle((10, 10), 3, dxfattribs={"layer": "OUTLINE"})
    msp.add_arc((25, 15), 5, 0, 90, dxfattribs={"layer": "OUTLINE"})
    msp.add_point((5, 5), dxfattribs={"layer": "OUTLINE"})
    msp.add_polyline2d([(0, 40, 0, 0, 0.5), (20, 40, 0, 0, 0), (20, 60, 0, 0, 0)], format="xyseb", close=True, dxfattribs={"layer": "OUTLINE"})
    msp.add_blockref("MARK", (30, 30), dxfattribs={"rotation": 45})
    msp.add_text("R12", dxfattribs={"height": 2})
    save_twins(doc, "r12_mm")


def r2018_utf8_m():
    """DXF R2018 (AC1032): UTF-8 strings and the unit meter."""
    doc = ezdxf.new("R2018")
    doc.units = units.M
    doc.layers.add("Bohrungen_äöü", color=1)
    doc.layers.add("Ø_holes", color=2)
    doc.layers.add("Слой", color=3)
    msp = doc.modelspace()
    msp.add_line((0, 0), (0.05, 0), dxfattribs={"layer": "Bohrungen_äöü"})
    msp.add_circle((0.01, 0.01), 0.003175, dxfattribs={"layer": "Ø_holes"})
    msp.add_lwpolyline([(0, 0.02), (0.05, 0.02), (0.05, 0.03)], dxfattribs={"layer": "Слой"})
    save_twins(doc, "r2018_utf8_m")


def codepage_1251_r2000():
    """DXF R2000 with $DWGCODEPAGE ANSI_1251: the layer name is in windows-1251 bytes."""
    doc = ezdxf.new("R2000")
    doc.encoding = "cp1251"
    doc.units = units.CM
    doc.layers.add("Отверстия", color=1)
    msp = doc.modelspace()
    msp.add_circle((1, 1), 0.5, dxfattribs={"layer": "Отверстия"})
    msp.add_line((0, 0), (5, 0), dxfattribs={"layer": "Отверстия"})
    save_twins(doc, "codepage_1251_r2000")
    data = (DXF_DIR / "codepage_1251_r2000.dxf").read_bytes()
    assert "Отверстия".encode("cp1251") in data


def codepage_932_r2000():
    """DXF R2000 with $DWGCODEPAGE ANSI_932: the layer name is in Shift-JIS bytes (two bytes for each character)."""
    doc = ezdxf.new("R2000")
    doc.encoding = "cp932"
    doc.units = units.MM
    doc.layers.add("図面_穴", color=1)
    msp = doc.modelspace()
    msp.add_circle((10, 10), 3, dxfattribs={"layer": "図面_穴"})
    save_twins(doc, "codepage_932_r2000")
    data = (DXF_DIR / "codepage_932_r2000.dxf").read_bytes()
    assert "図面_穴".encode("cp932") in data


def spline_1001_points():
    """A SPLINE with 1001 control points (dropped, CURVE_TOO_COMPLEX) and one LINE."""
    doc = ezdxf.new("R2000")
    doc.units = units.MM
    msp = doc.modelspace()
    msp.add_open_spline([(i, (i % 2) * 2) for i in range(1001)], degree=3)
    msp.add_line((0, 0), (10, 0))
    save_twins(doc, "spline_1001_points")


def png_bytes():
    raw = b"\x00\xff\x00\x00"
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def other_files():
    """Files that are not drawings, for the type test, and the wireframe examples."""
    OTHER_DIR.mkdir(parents=True, exist_ok=True)
    # The start of a DWG file: the version text and binary data.
    (OTHER_DIR / "sample.dwg").write_bytes(b"AC1032" + bytes(range(256)) * 4)
    (OTHER_DIR / "sample.svg").write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10">\n'
        '  <line x1="0" y1="0" x2="10" y2="10" stroke="black"/>\n</svg>\n'
    )
    (OTHER_DIR / "sample.pdf").write_bytes(
        b"%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\n"
        b"endobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"
    )
    (OTHER_DIR / "sample.png").write_bytes(png_bytes())
    (OTHER_DIR / "sample.nc").write_text("%\nO1000\nG21 G90\nG0 X0 Y0\nG1 X10 Y10 F100\nM30\n%\n")
    example = {
        "schema": "touchdro.convert.result", "v": "1", "status": "ready", "format": "dxf",
        "converterBuild": "local/dxf-converter", "errors": [], "warnings": [],
        "wireframe": {
            "schema": "touchdro.wireframe", "v": "3.0", "createdAt": "2026-10-11T20:00:00Z",
            "axes": ["X", "Y"], "ext": {"min": [0, 0], "max": [50000000, 30000000]},
            "layers": [{"n": "0"}, {"n": "HOLES", "st": 1}],
            "entities": [
                {"t": "P", "l": 0, "pts": [[0, 0], [50000000, 0], [50000000, 30000000], [0, 30000000]], "cl": 1},
                {"t": "C", "l": 1, "c": [10000000, 10000000], "r": 3175000},
            ],
        },
    }
    text = json.dumps(example, separators=(",", ":"))
    (OTHER_DIR / "example_result.json").write_text(text)
    (OTHER_DIR / "example_result.json.gz").write_bytes(gzip.compress(text.encode("utf-8"), mtime=0))


def main():
    entities_r2000_mm()
    curves_r2000_mm()
    fit_spline_r2000_mm()
    blocks_r2000_inch()
    nesting(8, "nesting_8_levels")
    nesting(9, "nesting_9_levels")
    r12_mm()
    r2018_utf8_m()
    codepage_1251_r2000()
    codepage_932_r2000()
    spline_1001_points()
    other_files()


if __name__ == "__main__":
    main()
