#!/usr/bin/env python3
"""Checks DXF export files of SuperDRO with ezdxf (a test tool only).

    <venv>/bin/python core/drawing/src/jvmTest/fixtures/check_export.py FILE.dxf [FILE.dxf ...]

For each file the script runs ``ezdxf.readfile`` and the auditor (the same checks as
``ezdxf audit``). It prints one JSON line for each file with the DXF version, the code page, the
units, the number of entities of each type, the layer names, and the audit errors and fixes. The
exit code is 1 when a file does not load or the auditor finds an error or a fix.
"""

import collections
import json
import sys

import ezdxf


def check(path):
    doc = ezdxf.readfile(path)
    auditor = doc.audit()
    counts = collections.Counter(entity.dxftype() for entity in doc.modelspace())
    result = {
        "file": path,
        "version": doc.dxfversion,
        "codepage": doc.header.get("$DWGCODEPAGE"),
        "insunits": doc.header.get("$INSUNITS"),
        "measurement": doc.header.get("$MEASUREMENT"),
        "entities": dict(sorted(counts.items())),
        "layers": [layer.dxf.name for layer in doc.layers],
        "linetypes": [linetype.dxf.name for linetype in doc.linetypes],
        "errors": [str(error.message) for error in auditor.errors],
        "fixes": [str(fix.message) for fix in auditor.fixes],
    }
    print(json.dumps(result, ensure_ascii=False))
    return not result["errors"] and not result["fixes"]


def main():
    ok = True
    for path in sys.argv[1:]:
        try:
            ok = check(path) and ok
        except Exception as error:  # noqa: BLE001 - print each failure and continue
            print(json.dumps({"file": path, "failure": repr(error)}))
            ok = False
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
