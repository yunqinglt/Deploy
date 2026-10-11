# Symbol analysis and project tree regression — 2026-10-11

Validated on the connected OnePlus PLR110 (Android 16). The updated Deploy debug
APK was built and installed. PosEdMCP's shared tool schema and MCP guidance were
synchronized and its Java compilation passed; PosEdMCP was not reinstalled.

## Original failure

The device screenshot `deploy-before.png` shows `JNI_OnLoad` at ELF address
`0x3413e4` in `libchunqiunative.so` failing with
`Flow exceeded maximum allowable instructions`. Its symbol size is 50,684 bytes,
larger than the previous 4,096-instruction decompiler budget can cover on ARM64.
LIEF and NDK `llvm-readelf` agree that this ELF contains 307 dynamic symbol entries
and no static symbol table. Missing stripped internal names cannot be recovered
by listing another symbol table.

The backend previously listed only dynamic symbols; the UI also stopped at 512
entries. Imported symbols with no local function body could be selected as code.

## Changes

- Enumerate both symbol tables with stable `(table,index)` identities, name
  filtering, table selection and bounded pages. The UI reads 100 entries at a
  time and labels imports and data separately from local function bodies.
- Render each library's symbols and each symbol's C/ASM code as child views.
  Repeated clicks collapse either level. Filtering, navigation, collapse and
  switching code mode cancel obsolete code requests and discard stale results.
- Use Material's outlined input container for new project names, with dialog
  margins outside the input so the hint and typed text share the correct inset.
- Raise the default decompiler budget to 20,000, allow explicit retries up to
  100,000, and report instruction exhaustion as `DECOMPILE_INSTRUCTION_LIMIT`.
  Keep the 30-second default deadline and allow explicit retries up to 120 seconds.
  Compile Ghidra with optimization even in the debug APK.
- Fix ELF loader lookahead at the end of a mapped segment: SLEIGH may request
  16 bytes for a final 4-byte instruction. Pad the unmapped tail only when the
  read started inside a mapped segment; reject wholly unmapped reads.
- Page ASM within a known symbol's byte range, placing page controls above the
  code so users do not need to scroll through 512 instructions to continue.

## Verification

`tools/deploy-analysis-regression.py` builds a synthetic AArch64 ELF with a
5,001-instruction function and 620 local functions, copies it to shared storage,
and queries Deploy's MCP. It does not load or execute the library.

All checks passed:

- 73-entry pages cover both tables exactly, preserving distinct table entries.
- Name filtering reaches `local_0619`, beyond the old 512-entry display limit.
- Invalid table, offset, list budget and deadline values return errors.
- An explicit 4,096-instruction budget returns a structured limit error.
- The default budget produces `return param_1 + 5000` for the long function
  (about 6.73 seconds on this run).
- Address-based decompilation of the last static function returns 619, verifying
  the segment-end lookahead fix.
- ASM pages cover all 20,004 bytes of the synthetic long function.
- The original `JNI_OnLoad` is fully decoded as 50,684 bytes over 25 ASM pages.

UI checks confirmed child placement, library and symbol collapse/reopen,
successful C output after cancelling an earlier request, ASM continuation
(`0x4228` to `0x4a28` on the test function), and aligned empty/filled project
inputs. Process exit records during the final UI checks show intentional
decompiler cancellation and package replacement, with no new main-process crash.

Local screenshots (ignored by Git): `deploy-before.png`,
`deploy-dialog-before.png`, `deploy-dialog-after.png`, `deploy-dialog-filled.png`,
`deploy-tree-code-after.png`, `deploy-tree-collapsed.png`,
`deploy-asm-page-after.png`.

Reproduction, with a running debug Deploy MCP service:

```powershell
python tools/deploy-analysis-regression.py --serial <device-serial> --adb <adb-path> --clang <NDK-clang-path>
```

## Remaining limitation

The user's actual `JNI_OnLoad` still exceeds the 120-second Ghidra deadline,
despite the larger instruction budget and optimized engine. No complete C output
was obtained for that function. Its ASM is available in full; this is a usable
fallback, not evidence that the C decompilation problem is fully resolved.
Further work needs profiling of this function's control-flow/SSA analysis.
The synthetic success establishes that long functions are no longer rejected
solely by the old 4,096-instruction default; it does not establish that all large
or complex functions will finish within the time limit.
