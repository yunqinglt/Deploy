#!/usr/bin/env bash
# Downloads the Gradle distribution used by tools/gradle.sh.
set -euo pipefail

VERSION="8.14.3"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${REPO_ROOT}/.tools"

mkdir -p "${DEST}"
cd "${DEST}"

if [[ -d "gradle-${VERSION}" ]]; then
    echo "gradle-${VERSION} already present"
    exit 0
fi

curl -fL --progress-bar -o gradle.zip \
    "https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip"
unzip -q gradle.zip
rm -f gradle.zip
echo "Installed ${DEST}/gradle-${VERSION}"
