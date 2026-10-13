"""Makes the DXF fixtures of the validation tests: each drawing as ASCII and as binary DXF.

The tool is ezdxf 1.4.4 (MIT license). It is a test tool only. The app does not contain it.
Run it from a separate directory and give the fixture directory as the argument:

    python -I make_dxf.py <fixture directory>

The two files of one drawing must give the same wireframe document.
"""
import sys
import ezdxf
from ezdxf.enums import TextEntityAlignment

out = sys.argv[1]


def save(doc, name):
    doc.saveas(f"{out}/{name}_ascii.dxf")
    doc.saveas(f"{out}/{name}_binary.dxf", fmt="bin")


def basic(version, name, units=4, layer_name="Outline"):
    doc = ezdxf.new(version, setup=True)
    if units is not None:
        doc.header["$INSUNITS"] = units
    else:
        doc.header["$INSUNITS"] = 0
    doc.layers.add(layer_name, linetype="CONTINUOUS")
    doc.layers.add("Centers", linetype="CENTER")
    doc.layers.add("Dashed", linetype="DASHED")
    # HIDDEN is not in the LTYPE table of ezdxf: the layer reads as Continuous.
    doc.layers.add("Hidden", linetype="HIDDEN")
    off = doc.layers.add("Off")
    off.off()
    msp = doc.modelspace()
    msp.add_line((0, 0), (100, 0), dxfattribs={"layer": layer_name})
    msp.add_circle((50, 25), 10, dxfattribs={"layer": "Centers"})
    msp.add_arc((20, 20), 5, 0, 90, dxfattribs={"layer": "Dashed"})
    msp.add_line((0, 10), (10, 10), dxfattribs={"layer": "Hidden"})
    msp.add_lwpolyline([(0, 0, 0), (40, 0, 0.5), (40, 30, 0), (0, 30, 0)], format="xyb", close=True,
                       dxfattribs={"layer": layer_name})
    msp.add_point((5, 5), dxfattribs={"layer": "Off"})
    msp.add_text("NOT GEOMETRY", dxfattribs={"layer": layer_name, "height": 2.5})
    msp.add_ellipse((70, 70), major_axis=(10, 0), ratio=0.5, dxfattribs={"layer": layer_name})
    msp.add_spline([(0, 50), (10, 60), (20, 50), (30, 60)], dxfattribs={"layer": layer_name})
    block = doc.blocks.new("PEG")
    block.add_line((0, 0), (2, 0), dxfattribs={"layer": "0"})
    msp.add_blockref("PEG", (200, 0), dxfattribs={"layer": layer_name})
    save(doc, name)


def r12(name):
    doc = ezdxf.new("R12")
    doc.header["$INSUNITS"] = 4
    msp = doc.modelspace()
    msp.add_line((1, 2), (3, 4))
    msp.add_circle((10, 10), 2.5)
    msp.add_arc((0, 0), 4, 90, 180)
    msp.add_polyline2d([(0, 0), (10, 0), (10, 5)], close=True)
    save(doc, name)


def inch(name):
    doc = ezdxf.new("R2000")
    doc.header["$INSUNITS"] = 1
    msp = doc.modelspace()
    msp.add_line((0, 0), (1, 2))
    save(doc, name)


def nested(name, levels):
    doc = ezdxf.new("R2000")
    doc.header["$INSUNITS"] = 4
    previous = None
    for level in range(levels):
        block = doc.blocks.new(f"B{level}")
        if previous is None:
            block.add_line((0, 0), (1, 0))
        else:
            block.add_blockref(previous, (0, 0))
        previous = f"B{level}"
    doc.modelspace().add_blockref(previous, (0, 0))
    save(doc, name)


def many_lines(name, count):
    doc = ezdxf.new("R2000")
    doc.header["$INSUNITS"] = 4
    msp = doc.modelspace()
    for index in range(count):
        msp.add_line((index, 0), (index, 1))
    save(doc, name)


def long_polyline(name, count):
    doc = ezdxf.new("R2000")
    doc.header["$INSUNITS"] = 4
    msp = doc.modelspace()
    msp.add_lwpolyline([(index * 0.1, (index % 2) * 0.1) for index in range(count)])
    save(doc, name)


def big_spline(name, count):
    doc = ezdxf.new("R2000")
    doc.header["$INSUNITS"] = 4
    msp = doc.modelspace()
    msp.add_line((0, 0), (1, 1))
    msp.add_spline_control_frame([(index, (index % 3) * 1.0) for index in range(count)])
    save(doc, name)


basic("R2000", "basic_r2000")
basic("R2000", "cp1252_r2000", layer_name="Ébauche")
basic("R2018", "utf8_r2018", layer_name="Ébauche")
basic("R2000", "nounits_r2000", units=None)
r12("old_r12")
inch("inch_r2000")
nested("nested9", 9)
nested("nested8", 8)
many_lines("lines12", 12)
long_polyline("poly2001", 2001)
big_spline("spline1001", 1001)
long_polyline("poly2000", 2000)
big_spline("spline1000", 1000)
