#!/bin/bash
# Wrapper build graal-isolate sur /media/hdd (noexec) : deporte WORK sur le HDD et
# symlinke les composants qui doivent EXECUTER des binaires (jdks, bootstrap, libffi)
# vers un FS executable (/home). Cf README graal-isolate "Si le FS de build est noexec".
set -euo pipefail

REPO=/home/pierre/dev/leek-wars/graal-isolate
export WORK=/media/hdd/lw-graal-isolate-work
EXEC_BASE=/home/pierre/.cache/lw-graal-isolate-exec
# 25.1.3-dev purge des nightlies (404) : on prend le 25.2.4-dev le plus proche, son
# native-image doit juste comprendre les options SVM de la ligne 25.1.
# Nightly 25.1.3-dev purge (404) et un bootstrap 25.2.x echoue l'assertion TruffleAPIFeature :
# release STABLE de la meme ligne = "GraalVM Community 25 Innovation 1" (graal 25.1.3, jdk 25.0.3).
BOOTSTRAP_URL="https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.1.3/graalvm-community-jdk-25i1-25.0.3_linux-x64_bin.tar.gz"
GRAALJS_TAG=graal-25.1.3
GRAAL_COMMIT=2143cd9f4e3c06b5518d1cbd23c09a918bc9cb58

mkdir -p "$WORK" "$EXEC_BASE/jdks" "$EXEC_BASE/bootstrap" "$EXEC_BASE/libffi"

# jdks : symlink (cible vide -> le ls de build.sh echoue -> fetch-jdk ecrit a travers le lien)
ln -sfn "$EXEC_BASE/jdks" "$WORK/jdks"

# bootstrap : build.sh saute le download si -d passe -> on pre-telecharge ici
if ! "$EXEC_BASE/bootstrap/bin/native-image" --version 2>/dev/null | grep -q "^native-image 25\.1\.3"; then
    echo "=== Pre-download bootstrap GraalVM Community 25 Innovation 1 (graal 25.1.3) ==="
    rm -rf "$EXEC_BASE/bootstrap"; mkdir -p "$EXEC_BASE/bootstrap"
    curl -fsSL -o "$WORK/bootstrap.tar.gz" "$BOOTSTRAP_URL"
    tar -xzf "$WORK/bootstrap.tar.gz" -C "$EXEC_BASE/bootstrap" --strip-components=1
    rm "$WORK/bootstrap.tar.gz"
fi
ln -sfn "$EXEC_BASE/bootstrap" "$WORK/bootstrap"

# libffi : le clone graal doit exister AVANT de poser le symlink dans son mxbuild
if [ ! -d "$WORK/graal" ]; then
    echo "=== Pre-clone graal @ $GRAAL_COMMIT ==="
    git clone --depth 1 --branch "$GRAALJS_TAG" https://github.com/oracle/graal.git "$WORK/graal"
    git -C "$WORK/graal" fetch --depth 1 origin "$GRAAL_COMMIT"
fi
mkdir -p "$WORK/graal/truffle/mxbuild/linux-amd64"
ln -sfn "$EXEC_BASE/libffi" "$WORK/graal/truffle/mxbuild/linux-amd64/libffi"

# mx telecharge des OUTILS EXECUTABLES (ninja...) dans mx-cache (noexec ici) :
# relocaliser vers le FS executable + symlink. A refaire si un nouvel outil apparait.
mkdir -p "$EXEC_BASE/mx-tools"
for d in "$WORK"/mx-cache/NINJA_* "$WORK"/mx-cache/MUSL_GCC_TOOLCHAIN_*; do
    [ -e "$d" ] && [ ! -L "$d" ] || continue
    mv "$d" "$EXEC_BASE/mx-tools/" && ln -s "$EXEC_BASE/mx-tools/$(basename "$d")" "$d"
done

# LLVM_TOOLCHAIN (clang++ du build nativebridge launcher) : extrait dans graal/sdk/mxbuild,
# doit lui aussi vivre sur un FS executable.
LLVM_DIR="$WORK/graal/sdk/mxbuild/linux-amd64/LLVM_TOOLCHAIN"
if [ -d "$LLVM_DIR" ] && [ ! -L "$LLVM_DIR" ]; then
    mv "$LLVM_DIR" "$EXEC_BASE/LLVM_TOOLCHAIN" && ln -s "$EXEC_BASE/LLVM_TOOLCHAIN" "$LLVM_DIR"
fi

export LW_SKIP_TOOLCHAIN_TEST=1
cd "$REPO"
exec bash build.sh
