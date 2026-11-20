"use strict";
let result;
async function calculate(input) {
  try {
    const py = await loadPyodide({indexURL: new URL(".", location.href).href});
    await py.loadPackage(["numpy", "scipy"]);
    const source = await fetch("engine.py");
    if (!source.ok) throw new Error("Missing quant engine");
    py.runPython(await source.text());
    py.globals.set("input_json", JSON.stringify(input));
    result = py.runPython("import json\njson.dumps(run_plan(json.loads(input_json)), allow_nan=False)");
  } catch (e) {
    result = JSON.stringify({status:"invalid_input", reason:String(e.message).split("\n").filter(Boolean).pop()});
  }
}
