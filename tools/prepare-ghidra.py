"""Compile the host SLEIGH tool and prepare APK language assets. No device needed.

Run in a C++ compiler environment (on Windows, a VS Developer Command Prompt).
Dependencies are pinned in third_party/README.md; generated assets are ignored.
"""
import argparse
import pathlib
import shutil
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cmake', default='cmake')
parser.add_argument('--generator', default='Ninja')
args = parser.parse_args()
build = root / ('.tools/sleigh-msvc' if __import__('os').name == 'nt' else '.tools/sleigh-build')
compiler_args = ['-DCMAKE_C_COMPILER=cl', '-DCMAKE_CXX_COMPILER=cl'] if __import__('os').name == 'nt' else []
subprocess.run([args.cmake, '-S', str(root / 'tools/sleigh'), '-B', str(build),
                '-G', args.generator, '-DCMAKE_BUILD_TYPE=Release'] + compiler_args, check=True)
subprocess.run([args.cmake, '--build', str(build), '--config', 'Release', '-j', '4'], check=True)
compiler = build / ('deploy-sleigh.exe' if __import__('os').name == 'nt' else 'deploy-sleigh')
if not compiler.exists():
    compiler = build / 'Release' / compiler.name
languages = root / 'third_party/ghidra/Ghidra/Processors/AARCH64/data/languages'
output = root / 'deploy/src/main/assets/ghidra'
output.mkdir(parents=True, exist_ok=True)
subprocess.run([str(compiler), str(languages / 'AARCH64.slaspec'), str(output / 'AARCH64.sla')], check=True)
for name in ('AARCH64.ldefs', 'AARCH64.pspec', 'AARCH64.cspec'):
    shutil.copyfile(languages / name, output / name)
print('Prepared Ghidra AArch64 specifications in', output)
