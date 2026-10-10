# Native dependencies

Deploy builds these upstream source trees locally. They are ignored by the parent
Git repository; dependency versions and the SDK compatibility patch are tracked.

| Directory | Upstream | Pinned commit |
| --- | --- | --- |
| `LIEF/` | https://github.com/lief-project/LIEF | `5416ff2e2fcda29924c2f868a7249b4318359c41` |
| `capstone/` | https://github.com/capstone-engine/capstone | `81497779add4f56196b16f0e0e2afe23c582c06f` |

From the repository root, for a fresh checkout:

```text
git clone https://github.com/lief-project/LIEF.git third_party/LIEF
git -C third_party/LIEF checkout --detach 5416ff2e2fcda29924c2f868a7249b4318359c41
git -C third_party/LIEF apply ../lief-cmake-3.22.patch
git clone https://github.com/capstone-engine/capstone.git third_party/capstone
git -C third_party/capstone checkout --detach 81497779add4f56196b16f0e0e2afe23c582c06f
```

The LIEF revision includes its `third-party/` dependency archives. Keep them in
place. Its declared CMake minimum is 3.24; the tracked patch preserves the existing
ELF-only Android build's use of SDK CMake 3.22.1. Apply the patch once to a clean
pinned checkout. This is a local build compatibility change, not an upstream fix.

Capstone enables only AArch64, ARM/Thumb and x86/x64 decoders. APK output is arm64.
The LIEF and Capstone licenses are in their source trees; review them when
redistributing builds. No third-party sources, Git histories or build products
are vendored into Deploy's initial commit.

If needed on this workstation, prefix a download command with Git's temporary
`-c http.proxy=http://127.0.0.1:10808` option. Do not persist proxy credentials in
tracked files.
