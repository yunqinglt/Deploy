# Deploy analysis and native loading

Deploy 0.4.0 runs LIEF, Capstone, Ghidra and Keystone on the device. The standalone MCP endpoint
(`127.0.0.1:4177`), PosEdMCP's AIDL proxy, and the future frontend share the same
analysis core. The applications have separate UIDs and preferences; no shared UID
or root capability is added to Deploy.

Deploy and the fixture now live in their own repository (`E:/deploy` on this
workstation), extracted from PosEdMCP commit `7212643`. PosEdMCP's bridge and native
loader remain in `E:/posedmcp`. The Deploy repository owns `deploy-api`; PosEdMCP
keeps a source snapshot so each build is independent. See
[contract synchronization](../deploy-api/README.md) and the
[Deploy agent prompt](MCP_PROMPT.md).

## Disassembly

Call `deploy_analyze` on either server:

```json
{"path":"/sdcard/libc.so","op":"disasm","name":"strlen","max":16}
```

Supply exactly one of `name` and `address`. Addresses are hexadecimal strings in
the ELF virtual address space, **before ASLR**, not file offsets or runtime
addresses. Results contain each instruction's ELF address, file offset, bytes,
size, mnemonic and operands. `nextAddress` and `stopReason` describe continuation.
An IFUNC symbol describes its resolver, not the implementation selected at runtime.

- `max`: instruction count, default 64, range 1–512.
- `bytes`: byte window, range 1–4096; defaults to the symbol size (capped at 4096),
  or 256 when no symbol size is available. Symbol and file-backed executable
  segment boundaries cap the window.
- Supported ELF architectures: AArch64, ARM/Thumb, x86 and x86_64, little-endian.
  The APK itself is arm64. For ARM32 `mode=auto` uses the symbol/address low bit;
  use `mode=thumb` for a raw even Thumb address or `mode=arm` for ARM instructions.
- Unmapped, misaligned, undefined or non-function addresses/symbols produce an
  error. No instruction is fabricated when decoding stops.
- `op=decompile` uses native Ghidra 11.3.2, currently little-endian AArch64 only.
  Select exactly one symbol name or ELF address. `max` is the instruction budget,
  default 20000, range 1–100000. Output includes inferred C `pseudocode`, `warnings`
  and `note`; types/prototypes are inferred, not original source. Import prototype
  and relocation recovery are incomplete. Output is capped at 256 KiB.
  It runs in the unexported `:decompiler` service with a default 30-second work deadline (timeoutSeconds accepts 1–120);
  timeout kills that worker. Healthy workers may be reused between requests.
  Errors preserve the MCP frontend. Other engines still run in the service process.

The shared `DeployContract` keeps both MCP input schemas aligned, including
`name`, `address`, `max`, `bytes`, and `mode`. These arguments are also available
through AIDL. Native errors set the MCP tool error flag.

## Assembly, buffers and live memory

Both servers expose `deploy_assemble(arch, source, address="0x0")` and
`deploy_analyze_bytes(arch, bytes, address="0x0", max=64)`. `arch` is one of
`aarch64`, `arm`, `thumb`, `x86`, `x86_64`, all little-endian; x86 uses Intel syntax.
Assembly accepts at most 16384 characters/512 statements and produces 1–4096
bytes, with `executed:false`. Only instructions and simple named labels are
accepted; directives, quoted strings, block comments and escapes are rejected.
Byte input is 1–4096 bytes of contiguous hex pairs. Decode max is 1–512.
ARM64/ARM origins must be 4-byte aligned, Thumb 2-byte aligned; 32-bit targets
require a 32-bit address range. Supply the final origin for PC-relative encoding.

Only PosEdMCP exposes these two additional runtime tools, using its existing
plugin confirmation policy and an exact ordinary scoped app peer:

- `deploy_disasm_memory(package, peer, address, reason, length=256, max=64)` reads
  1–4096 bytes within one readable mapping through `/proc/self/mem` in the target.
  PosEdMCP writes a private, unlinked read-only descriptor and sends it through
  AIDL `analyzeBuffer`. Deploy never opens the other process. Results include
  snapshot PID/UID/time and `addressKind:runtime_virtual_address`.
- `deploy_patch_memory(package, peer, address, source, expected, reason)` assembles
  exactly one aligned 4-byte ARM64 instruction at the final runtime address.
  Expected bytes must be 8 hex digits. The target requires a private readable
  executable mapping, temporarily adds write permission, atomically compares and
  replaces the instruction, flushes the instruction cache, and restores protection.
  Inspect both `patched` and `permissionsRestored`, including on error.

These addresses include ASLR; they are not ELF virtual addresses or file offsets.
Threads are not stopped and mappings are not pinned against concurrent unloading.
The caller must arrange a suitable target lifetime and code usage window. There
is no multi-instruction patch, trampoline, root fallback or automatic rollback.
To restore, assemble the original instruction and use the replacement bytes as
`expected`. A timeout/disconnect/protection-restore error can follow a write;
inspect state before retrying. Returned `originalBytes`/`replacementBytes` aid recovery.

AIDL v2 appends `assemble` and `analyzeBuffer` without renumbering existing
methods. Status advertises `apiVersion:2`. The receiver closes the buffer FD and
rejects pipes/oversized input. See the shared contract synchronization guide.

## Runtime loading through PosEdMCP

Only PosEdMCP advertises `deploy_load_native`. Select the exact online peer from
`module_status`; the target must be an ordinary application in the framework's
scope, started since the updated module was installed.

```json
{
  "package":"dev.deploy.fixture",
  "peer":"app:dev.deploy.fixture:12345",
  "path":"/sdcard/deploy-tests/libdeploy_fixture.so",
  "entry":"deploy_fixture_probe",
  "reason":"Load the test library and return the PID captured by its constructor"
}
```

Replace the peer key with the live value. The flow is:

1. Deploy copies a readable regular file into a bounded snapshot, validates its
   ELF with LIEF, and calculates SHA-256. It returns metadata and a read-only file
   descriptor over AIDL; the temporary pathname is unlinked. Maximum size: 8 MiB.
2. PosEdMCP reads and verifies that snapshot before confirmation. The normal
   `ConfirmationGate.Kind.PLUGIN` policy displays the package, exact process,
   source path, length, hash, entry and reason. No execution occurs in Deploy.
3. After approval the exact peer must still be online. PosEdMCP transports the
   approved bytes over its existing bridge; the target checks ELF and SHA-256
   again. There is no automatic retargeting to another process or root fallback.
4. In the scoped application, `libposednative.so` creates a sealed memfd and calls
   `android_dlopen_ext(RTLD_NOW | RTLD_LOCAL)`. ELF constructors run as that app's
   UID. Android linker namespace/dependency and SELinux restrictions still apply;
   a loader failure is returned, not bypassed.

This milestone accepts little-endian arm64 ET_DYN shared objects; PIE executables
with an interpreter are rejected. System framework, PosEdMCP and Deploy are not
targets of this new tool. `entry` is optional; when present it must be an exported
**`int64_t function(void)`**. It is called on each approved request. `JNI_OnLoad`
is not called automatically. A missing entry explicitly reports that the library
has already been loaded. A timeout/crash can occur after constructors ran: inspect
target state before requesting another load.

Libraries are deduplicated by SHA-256 within a target process. They remain loaded
until process exit; there is no unsafe automatic `dlclose` and no persisted hook
registration. Changed code requires a new hash or a process restart. Required
libraries must be available in the target's existing linker namespace.

This provides runtime native code loading. It does not yet implement persistent
native hooks, inline/PLT trampolines, or an offline ELF rewriting/deployment tool.
LIEF's [ELF Hooking tutorial](https://lief.re/doc/latest/tutorials/04_elf_hooking.html)
adds code segments and changes symbols in an output file; that is a separate
workflow. The [Runtime module](https://lief.re/doc/latest/runtime/intro.html)
operates in the process hosting the library and does not itself grant Deploy
access to a scoped application's process.

## Device verification

In the Deploy repository, build `:deploy:assembleDebug :native-fixture:assembleDebug`
as described in [README.md](../README.md). In the PosEdMCP repository, build
`:app:assembleDebug` separately. No Gradle build requires the other repository.
The fixture is a separate optional test APK, not included in either product APK.
Install all three, put `libdeploy_fixture.so` from the fixture APK's
`lib/arm64-v8a/` directory at `/sdcard/deploy-tests/`, and retain `/sdcard/libc.so`
as a real-world sample. Start both MCP services. Run the following integration
checks from the Deploy repository; replace the serial with the connected device.

```text
python tools/deploy-smoke.py --serial 3972b041 --output E:/deploy/smoke-analysis.json
python tools/deploy-smoke.py --serial 3972b041 --output E:/deploy/smoke-runtime.json --load-fixture
python tools/deploy-smoke.py --serial 3972b041 --output E:/deploy/smoke-isolation.json --isolation
```

For `--load-fixture`, enable **Deploy Native Fixture** in PosEdMCP's LSPosed/Vector
scope, restart it and approve the ordinary application prompts when shown. The
Activity has no loader/bridge of its own. The fixture's ELF constructor captures
its process PID; the entry returns that captured value. The test compares it with
the selected peer and checks a non-root UID. It also checks handle reuse and an
entry returning 42, explicit missing-entry errors and offline target rejection.

Tokens are kept in memory, never written to the JSON reports. Forwarding uses
host ports 14177 and 18765; no other adb forwarding rules are removed.
`--isolation` temporarily disables each companion package and restores its state;
`--load-fixture` executes code inside the selected fixture process. These optional
checks change device state and are not part of an ordinary local build.

Verified on Xiaomi 24129PN74C (Android 15, arm64): direct MCP and AIDL disassembly
including libc's `strlen`, error cases, and real scoped-process native loading.
The first load returned PID 4416 from the constructor, matching the bridge peer,
with UID 10289 and `loader=android_dlopen_ext_memfd`.

The final descriptor ownership fix was also tested on the device, along with
handle reuse, missing entry reporting and both isolated server configurations.
Both apps were restored to their original enabled state and their services were
restarted. On this ROM starting an unexported service immediately after restoring
a stopped package can report "not found"; the test launches its Activity first.

AArch64 is the architecture tested end to end. ARM/Thumb/x86/x64 decoders compile,
but the supplementary device test timed out and its retry was rejected by the
execution approval system ("blocked by policy", including after user approval).
Those four targets remain unverified on the device. An occasional background
HTTP timeout was observed; it was not diagnosed as a parser or loader failure.


For 0.4.0 engines and reversible memory operations:

```text
python tools/deploy-engines-smoke.py --output smoke-engines.json
python tools/deploy-engines-smoke.py --runtime-only --output smoke-runtime.json
```

The second command requires the scoped fixture and the user's phone confirmations.
Keep the fixture foreground to avoid ROM background freezing during a bridge call.
Do not change confirmation preferences to run the suite. The script restores a
successful patch in `finally`; if a write's result is lost, inspect/restart the
fixture before another test. `--runtime` combines engine and runtime suites.
`tools/deploy-smoke.py --isolation` also covers standalone decompile/assemble/buffer
analysis and restores both applications' enabled states in `finally`.

PosEdMCP's Special settings now offers separate ColorOS and HyperOS “帮助Deploy”
scripts. They match only dev.deploy and its colon subprocesses on known cleaner
paths; manual force-stop remains available. Defaults remain off. New independent
hook ownership requires the updated system_server module (normally after reboot).
Synthetic Lua/registry tests are in PosEdMCP `tests/run-hooks.ps1`; they do not prove
all ROM method signatures or versions. See MILESTONE-0.4.md for verified scope.


Symbol enumeration now covers both dynamic and static tables (default `table=all`).
Use `table`, `query`, `offset`, and `max`, following `nextOffset` until null;
responses also have a transport size cap. `(table,index)` identifies duplicates
without discarding aliases or local symbols. No static table means stripped
local names are unavailable. Undefined imports have no code in this ELF.
Instruction exhaustion has code `DECOMPILE_INSTRUCTION_LIMIT`; the UI offers
larger-budget retries and paged ASM. Ghidra uses `-O2` in debug builds as well.
`tools/deploy-analysis-regression.py` generates an ELF containing a 5001-instruction
function and 620 local functions, then verifies paging, validation, C and ASM
through a running Deploy MCP server. Requires `--serial`, `--adb`, `--clang`.
