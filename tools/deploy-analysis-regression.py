#!/usr/bin/env python3
"""Read-only device regression for symbol paging and long functions.

Requires a running debug Deploy MCP service. Builds a synthetic ELF with an
NDK clang, then copies it to /sdcard/deploy-tests. Does not load/execute it.
Credentials stay in memory. No PosEdMCP or root required.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument("--serial", required=True)
p.add_argument("--adb", default="adb")
p.add_argument("--clang", required=True)
p.add_argument("--port", type=int, default=14177)
a = p.parse_args()


def adb(*args):
    return subprocess.check_output([a.adb, "-s", a.serial, *args])


with tempfile.TemporaryDirectory(prefix="deploy-analysis-") as temp:
    source = Path(temp) / "regression.S"
    library = Path(temp) / "libanalysis_regression.so"
    code = ".text\n.p2align 2\n.global long_function\n.type long_function,%function\nlong_function:\n"
    code += "add x0,x0,#1\n" * 5000 + "ret\n.size long_function,.-long_function\n"
    for index in range(620):
        name = f"local_{index:04}"
        code += (f".local {name}\n.type {name},%function\n{name}:\nmov x0,#{index}\n"
                 f"ret\n.size {name},.-{name}\n")
    source.write_text(code, encoding="utf-8")
    subprocess.run([a.clang, "--target=aarch64-linux-android31", "-shared", "-nostdlib",
                    "-Wl,--build-id=none", str(source), "-o", str(library)], check=True)
    adb("shell", "mkdir", "-p", "/sdcard/deploy-tests")
    remote = "/sdcard/deploy-tests/libanalysis_regression.so"
    adb("push", str(library), remote)

adb("forward", f"tcp:{a.port}", "tcp:4177")
prefs = ET.fromstring(adb("shell", "run-as", "dev.deploy", "cat", "shared_prefs/deploy.xml"))
token = prefs.find("string[@name='token']").text


def call(**args):
    request = urllib.request.Request(f"http://127.0.0.1:{a.port}/mcp",
        data=json.dumps(dict(jsonrpc="2.0", id=1, method="tools/call",
            params=dict(name="deploy_analyze", arguments=dict(path=remote, **args)))).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    response = json.load(urllib.request.urlopen(request, timeout=140))
    if "error" in response:
        return {"error": response["error"]}
    return json.loads(response["result"]["content"][0]["text"])


def check(label, condition):
    assert condition, label
    print("PASS", label, flush=True)


all_symbols = []
offset = 0
while True:
    result = call(op="symbols", table="all", max=73, offset=offset)
    check(f"page at {offset}", "error" not in result and len(result["symbols"]) <= 73)
    all_symbols.extend(result["symbols"])
    if result["nextOffset"] is None:
        break
    check("page advances", result["nextOffset"] > offset)
    offset = result["nextOffset"]
check("pages cover both tables", len(all_symbols) == result["count"] == result["dynamicSymbols"] + result["staticSymbols"])
check("no page duplicates", len({(s["table"], s["index"]) for s in all_symbols}) == len(all_symbols))
check("620 local function names", {s["name"] for s in all_symbols if s["name"].startswith("local_")} == {f"local_{i:04}" for i in range(620)})
filtered = call(op="symbols", table="static", query="local_0619", max=1)
check("filtered static symbol beyond first 512", filtered["count"] == 1 and filtered["symbols"][0]["name"] == "local_0619")
dynamic = call(op="symbols", table="dynamic", max=100)
check("dynamic table selection", all(s["table"] == "dynamic" for s in dynamic["symbols"]))
last = call(op="symbols", offset=2147483647)
check("past last page", last["returned"] == 0 and last["nextOffset"] is None)
for args in [dict(op="symbols", offset=-1), dict(op="symbols", table="invalid"),
             dict(op="symbols", max=0), dict(op="symbols", offset=1.5),
             dict(op="decompile", name="long_function", timeoutSeconds=121)]:
    check("invalid argument rejected", "error" in call(**args))
limited = call(op="decompile", name="long_function", max=4096)
check("structured instruction limit", limited.get("code") == "DECOMPILE_INSTRUCTION_LIMIT" and limited.get("complete") is False)
start = time.monotonic()
long_code = call(op="decompile", name="long_function")
check("5001-instruction C output", "return" in long_code.get("pseudocode", "") and ("0x1388" in long_code["pseudocode"] or "5000" in long_code["pseudocode"]))
print("Long function seconds:", round(time.monotonic() - start, 2), flush=True)
local = call(op="decompile", address=filtered["symbols"][0]["value"])
check("static function by address", ("0x26b" in local.get("pseudocode", "") or "619" in local.get("pseudocode", "")))
resolved = call(op="resolve", name="long_function")
next_address = resolved["value"]
remaining = resolved["size"]
consumed = 0
while remaining:
    asm = call(op="disasm", address=next_address, bytes=min(4096, remaining), max=512)
    check("ASM page advances", asm.get("decodedBytes", 0) > 0)
    consumed += asm["decodedBytes"]
    remaining -= asm["decodedBytes"]
    next_address = asm["nextAddress"]
check("ASM pages cover whole function", consumed == 20004 and remaining == 0)
print("All analysis regressions passed.")
