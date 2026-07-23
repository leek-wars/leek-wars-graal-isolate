#!/bin/bash
# Wrapper build graal-isolate sur /media/hdd (noexec) : deporte WORK sur le HDD et
# symlinke les composants qui doivent EXECUTER des binaires (jdks, bootstrap, ninja,
# musl, LLVM, libffi) vers un FS executable (/home). Cf README "Si le FS de build est
# noexec". Idempotent : re-runnable apres un echec, y compris apres un wipe de WORK
# qui conserve le cache executable.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
export WORK=/media/hdd/lw-graal-isolate-work
EXEC_BASE=/home/pierre/.cache/lw-graal-isolate-exec

# Pins UNIQUES : lus depuis build.sh (source de verite), pas de copie qui driftera au
# prochain bump GraalVM.
eval "$(grep -E '^(BOOTSTRAP_URL|GRAALJS_TAG|GRAAL_COMMIT)=' "$REPO/build.sh")"

mkdir -p "$WORK" "$EXEC_BASE/jdks" "$EXEC_BASE/bootstrap" "$EXEC_BASE/libffi"

# jdks : symlink (cible vide -> le ls de build.sh echoue -> fetch-jdk ecrit a travers le lien)
ln -sfn "$EXEC_BASE/jdks" "$WORK/jdks"

# bootstrap : build.sh saute le download si -d passe -> on pre-telecharge ici. La version
# graal est sur la ligne "GraalVM CE x.y.z" (la 1re ligne affiche la version JDK, 25.0.x).
BOOTSTRAP_GRAAL_VERSION="$(echo "$BOOTSTRAP_URL" | grep -oE 'graal-[0-9.]+' | head -1 | cut -d- -f2)"
if ! "$EXEC_BASE/bootstrap/bin/native-image" --version 2>/dev/null | grep -q "GraalVM CE ${BOOTSTRAP_GRAAL_VERSION//./\\.}"; then
    echo "=== Pre-download bootstrap GraalVM stable (graal $BOOTSTRAP_GRAAL_VERSION) ==="
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

# mx telecharge des OUTILS EXECUTABLES (ninja, toolchain musl...) dans mx-cache (noexec
# ici) : relocaliser vers le FS executable + symlink. Si la destination existe deja
# (re-run apres wipe de WORK), on jette la copie fraiche et on symlinke l'existante
# (contenu identique : le nom contient le sha256).
mkdir -p "$EXEC_BASE/mx-tools"
for d in "$WORK"/mx-cache/NINJA_* "$WORK"/mx-cache/MUSL_GCC_TOOLCHAIN_*; do
    [ -e "$d" ] && [ ! -L "$d" ] || continue
    dest="$EXEC_BASE/mx-tools/$(basename "$d")"
    if [ -e "$dest" ]; then rm -rf "$d"; else mv "$d" "$dest"; fi
    ln -s "$dest" "$d"
done

# LLVM_TOOLCHAIN (clang++ du build nativebridge launcher, PAS couvert par
# LW_SKIP_TOOLCHAIN_TEST) : extrait dans graal/sdk/mxbuild, doit lui aussi vivre sur un
# FS executable. rm -rf de la destination avant mv, sinon un re-run imbriquerait le
# toolchain frais DANS l'ancien et le symlink pointerait sur du perime.
LLVM_DIR="$WORK/graal/sdk/mxbuild/linux-amd64/LLVM_TOOLCHAIN"
if [ -d "$LLVM_DIR" ] && [ ! -L "$LLVM_DIR" ]; then
    rm -rf "$EXEC_BASE/LLVM_TOOLCHAIN"
    mv "$LLVM_DIR" "$EXEC_BASE/LLVM_TOOLCHAIN" && ln -s "$EXEC_BASE/LLVM_TOOLCHAIN" "$LLVM_DIR"
fi

export LW_SKIP_TOOLCHAIN_TEST=1
cd "$REPO"
exec bash build.sh
