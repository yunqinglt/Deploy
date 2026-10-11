# Native dependencies

Deploy builds these upstream source trees locally. They are ignored by the parent
Git repository; dependency versions and the SDK compatibility patch are tracked.

| Directory | Upstream | Pinned commit |
| --- | --- | --- |
| `LIEF/` | https://github.com/lief-project/LIEF | `5416ff2e2fcda29924c2f868a7249b4318359c41` |
| `capstone/` | https://github.com/capstone-engine/capstone | `81497779add4f56196b16f0e0e2afe23c582c06f` |
| `ghidra/` | https://github.com/NationalSecurityAgency/ghidra (`Ghidra_11.3.2_build`) | `3a503048975ac134d9d4efecaa09f143bbc6b3b9` |
| `keystone/` | https://github.com/keystone-engine/keystone (`0.9.2`) | `dc7932ef2b2c4a793836caec6ecab485005139d6` |
| `zlib/` | https://github.com/madler/zlib (`v1.3.1`, host SLEIGH only) | `51b7f2abdade71cd9bb0e7a373ef2610ec6f9daf` |

From the repository root, for a fresh checkout:

```text
git clone https://github.com/lief-project/LIEF.git third_party/LIEF
git -C third_party/LIEF checkout --detach 5416ff2e2fcda29924c2f868a7249b4318359c41
git -C third_party/LIEF apply ../lief-cmake-3.22.patch
git clone https://github.com/capstone-engine/capstone.git third_party/capstone
git -C third_party/capstone checkout --detach 81497779add4f56196b16f0e0e2afe23c582c06f
git clone --depth 1 --branch Ghidra_11.3.2_build --filter=blob:none --sparse https://github.com/NationalSecurityAgency/ghidra.git third_party/ghidra
git -C third_party/ghidra sparse-checkout set Ghidra/Features/Decompiler/src/decompile/cpp Ghidra/Processors/AARCH64/data/languages
git clone --depth 1 --branch 0.9.2 https://github.com/keystone-engine/keystone.git third_party/keystone
git clone --depth 1 --branch v1.3.1 https://github.com/madler/zlib.git third_party/zlib
```

Check each `git -C third_party/<directory> rev-parse HEAD` against the table before
building. Generated sources and language assets are not vendored. Keystone's
bundled LLVM supplies AArch64/ARM/X86 assemblers; it is not a host LLVM dependency.

## Ghidra language assets

Run `python tools/prepare-ghidra.py` in a host C++ compiler environment with CMake
and Ninja on PATH. It builds the upstream SLEIGH compiler, compiles AARCH64.slaspec,
and copies AARCH64.ldefs/pspec/cspec into `deploy/src/main/assets/ghidra/`.
Gradle checks that the compiled language exists; Android builds only the native
decompiler and links platform zlib. Re-run asset preparation after changing the
pinned Ghidra version; update `GhidraSpecs`'s asset directory version too.

On this Windows workstation, from PowerShell in `E:/deploy`:

```powershell
$env:PATH = 'E:/SDK/cmake/3.22.1/bin;' + $env:PATH
cmd /d /c 'call "E:\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" >nul && python tools\prepare-ghidra.py --cmake E:/SDK/cmake/3.22.1/bin/cmake.exe'
```

Adjust the Visual Studio path to the installed C++ workload. The Windows script
selects MSVC and `.tools/sleigh-msvc`; other hosts use their CMake default compiler.
No Java Ghidra application or on-device JDK is needed. The selected source lists
in `cmake/GhidraSources.cmake` follow this Ghidra revision's native Makefile.

The LIEF revision includes its `third-party/` dependency archives. Keep them in
place. Its declared CMake minimum is 3.24; the tracked patch preserves the existing
ELF-only Android build's use of SDK CMake 3.22.1. Apply the patch once to a clean
pinned checkout. This is a local build compatibility change, not an upstream fix.

Capstone enables only AArch64, ARM/Thumb and x86/x64 decoders. APK output is arm64.
LIEF and the selected Ghidra code use Apache-2.0. Capstone uses BSD-style licenses
and includes LLVM notices. Keystone uses GPLv2 with `EXCEPTIONS-CLIENT` for listed
FOSS client licenses; its bundled LLVM uses the University of Illinois/NCSA
license. Keystone also offers commercial licensing. The exception does not grant
an unrestricted proprietary distribution license. zlib uses its zlib license.
This dependency choice does not select a license for Deploy itself. License texts
are retained under `deploy/src/main/assets/licenses/`; the original sources remain
the authority. Source trees, their Git histories and build products stay ignored.

## Android offline API artifact

`listenablefuture-1.0.jar` is the small Guava API artifact required by AndroidX
ProfileInstaller. The AndroidX graph includes its POM, but this workspace is
built offline, so the valid JAR is kept in `third_party/` and referenced directly
by `deploy/build.gradle.kts`. It was retrieved from Maven Central and has
SHA-256 `E4AD7607E5C0477C6F890EF26A49CB8D1BB4DFFB650BAB4502AFEE64644E3069`.

If needed on this workstation, prefix a download command with Git's temporary
`-c http.proxy=http://127.0.0.1:10808` option. Do not persist proxy credentials in
tracked files.
