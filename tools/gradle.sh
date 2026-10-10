#!/usr/bin/env bash
# Wrapper that keeps Gradle's cache on an ASCII path.
#
# The Windows user profile here contains non-ASCII characters
# (C:\Users\<CJK>), which several Android/Java toolchain helpers mishandle, so
# GRADLE_USER_HOME is redirected into the repo. Proxy settings live in
# .gradle-home/gradle.properties, not in version control.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_BIN="${REPO_ROOT}/.tools/gradle-8.14.3/bin/gradle"

if [[ ! -x "${GRADLE_BIN}" ]]; then
    echo "Gradle not found at ${GRADLE_BIN}" >&2
    echo "Download it with: tools/bootstrap-gradle.sh" >&2
    exit 1
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in "C:/Program Files/Java/jdk-22" "C:/Program Files/Java/jdk-21"; do
        if [[ -x "${candidate}/bin/javac" || -x "${candidate}/bin/javac.exe" ]]; then
            export JAVA_HOME="${candidate}"
            break
        fi
    done
fi

export GRADLE_USER_HOME="${REPO_ROOT}/.gradle-home"

cd "${REPO_ROOT}"
exec "${GRADLE_BIN}" "$@"
