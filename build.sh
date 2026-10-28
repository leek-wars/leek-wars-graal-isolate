#!/bin/bash
# Reproduction bout-en-bout de l'image isolate GraalJS custom Leek Wars
# (StatementCounter embarqué = compteur d'ops déterministe + RAM bornée par contexte).
#
# Produit : dist/js-isolate-resources-linux-amd64.jar (~127 Mo, image COMBINEE js+python,
# remplace les artefacts js-isolate ET python-isolate officiels)
# (remplace org.graalvm.polyglot:js-isolate-linux-amd64-community:$GRAAL_VERSION)
#
# Prérequis : linux-amd64, ~20 Go de disque sur un FS **exécutable** (pas de noexec),
# gcc/make/zlib headers (build-essential zlib1g-dev), python3, git, curl,
# ~24 Go de RAM libre (20 Go de tas pour native-image).
#
# Durée : ~15-30 min selon la machine (l'image native ~5 min sur 24 cœurs).
set -euo pipefail
cd "$(dirname "$0")"

### Pins de version — à mettre à jour ENSEMBLE lors d'un bump GraalVM (voir README)
GRAAL_VERSION=25.1.3
# Commit de oracle/graal épinglé par la suite graalPYTHON (mx.graalpython/suite.py ->
# regex -> version) : le plus récent des deux pins graaljs/graalpython (même ligne 25.1,
# graaljs tolère un graal plus récent, l'inverse est risqué).
GRAAL_COMMIT=2143cd9f4e3c06b5518d1cbd23c09a918bc9cb58
GRAALJS_TAG=graal-25.1.3
GRAALPYTHON_TAG=graal-25.1.3
MX_VERSION=7.83.0          # exigé par la suite graalpython (>= common.json)
JDK_ID=labsjdk-ce-latest   # résolu via graal/common.json
# Release STABLE de la MÊME ligne (repo graalvm/graalvm-ce-builds) : sert de
# BOOTSTRAP_GRAALVM (son native-image doit être de la ligne 25.1 : un builder 25.2+
# échoue l'assertion de version TruffleAPIFeature) et évite de construire le stage1
# GraalVM localement. NE PAS reprendre un nightly de graalvm-ce-dev-builds : ils sont
# purgés au fil de l'eau (le pin 25.1.3-dev-20260621 est devenu 404).
BOOTSTRAP_URL="https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.1.3/graalvm-community-jdk-25i1-25.0.3_linux-x64_bin.tar.gz"

WORK=${WORK:-$PWD/work}
mkdir -p "$WORK" dist
rm -f dist/js-isolate-resources-linux-amd64.jar # un build raté ne laisse pas l'ancien jar
# Clones superficiels, JDK et bootstrap ne se mettent pas à jour en place : refaits quand les pins
# changent (mx, clone complet, se remet à jour plus bas). On n'efface que ce que ce script crée :
# WORK peut contenir autre chose. LW_PINS_ONLY=1 : s'arrêter là (build-noexec-hdd.sh).
pins=$(sed -n '/^### Pins/,/^WORK=/p' build.sh | grep -E '^[A-Z_]+=' | grep -v '^WORK=' | sed 's/[[:space:]]*#.*//')
if [ "$(cat "$WORK/.pins" 2> /dev/null)" != "$pins" ]; then
    echo "Pins nouveaux ou changés : clones, JDK et bootstrap de $WORK refaits"
    (cd "$WORK" && rm -rf graal graaljs graalpython jdks bootstrap bootstrap.tmp bootstrap.tar.gz harness-out host-classpath.txt)
fi
echo "$pins" > "$WORK/.pins"
[ -z "${LW_PINS_ONLY:-}" ] || exit 0
export MX_PYTHON=python3
export MX_CACHE_DIR="$WORK/mx-cache"

step() { echo; echo "=== $* ==="; }
# Échec à nommer : en annotation dans GitHub Actions, que `gh run view` affiche sans le journal.
fail() {
    if [ "${GITHUB_ACTIONS:-}" = true ]; then echo "::error::$*"; else echo "$*" >&2; fi
    exit 1
}

### 1. Sources
step "Clones (graal @ $GRAAL_COMMIT, graaljs @ $GRAALJS_TAG, mx @ $MX_VERSION)"
if [ ! -d "$WORK/mx" ]; then git clone https://github.com/graalvm/mx.git "$WORK/mx"; fi
git -C "$WORK/mx" fetch --tags -q && git -C "$WORK/mx" checkout -q "$MX_VERSION"
if [ ! -d "$WORK/graal" ]; then
    git clone --depth 1 --branch "$GRAALJS_TAG" https://github.com/oracle/graal.git "$WORK/graal"
fi
# Hors du if : un fetch qui a échoué au run précédent est refait.
git -C "$WORK/graal" cat-file -e "$GRAAL_COMMIT^{commit}" 2> /dev/null \
    || git -C "$WORK/graal" fetch --depth 1 origin "$GRAAL_COMMIT"
git -C "$WORK/graal" checkout -q "$GRAAL_COMMIT"
if [ ! -d "$WORK/graaljs" ]; then
    git clone --depth 1 --branch "$GRAALJS_TAG" https://github.com/oracle/graaljs.git "$WORK/graaljs"
fi
git -C "$WORK/graaljs" checkout -q "$GRAALJS_TAG"
if [ ! -d "$WORK/graalpython" ]; then
    git clone --depth 1 --branch "$GRAALPYTHON_TAG" https://github.com/oracle/graalpython.git "$WORK/graalpython"
fi
git -C "$WORK/graalpython" checkout -q "$GRAALPYTHON_TAG"

### 2. Patches + source de l'instrument
step "Application des patches"
git -C "$WORK/graal" checkout -q -- . && git -C "$WORK/graal" apply "$PWD/patches/graal.patch"
git -C "$WORK/graaljs" checkout -q -- . && git -C "$WORK/graaljs" apply "$PWD/patches/graaljs.patch"
# Comptage des operations Python par EXPRESSION (Tag(Expression) sur les operateurs, appels,
# acces, litteraux, iterations de comprehension) : GraalPy ne tague que les statements.
git -C "$WORK/graalpython" checkout -q -- . && git -C "$WORK/graalpython" apply "$PWD/patches/graalpython.patch"
rm -rf "$WORK/graaljs/graal-js/src/com.leekwars.truffle.instrument"
cp -r "$PWD/src/com.leekwars.truffle.instrument" "$WORK/graaljs/graal-js/src/"

### 3. JDKs
step "JDK de build ($JDK_ID) + bootstrap GraalVM (graal-25.1.3 stable)"
if [ ! -d "$WORK/jdks" ] || ! ls -d "$WORK"/jdks/labsjdk-* >/dev/null; then
    bash "$WORK/mx/mx" fetch-jdk --jdk-id "$JDK_ID" \
        --configuration "$WORK/graal/common.json" --to "$WORK/jdks"
fi
JAVA_HOME=$(ls -d "$WORK"/jdks/labsjdk-*_amd64 | sort | tail -1)
export JAVA_HOME
if [ ! -d "$WORK/bootstrap" ]; then
    curl -fsSL -o "$WORK/bootstrap.tar.gz" "$BOOTSTRAP_URL"
    # Extrait à côté puis renommé : une extraction interrompue n'est pas prise pour complète.
    rm -rf "$WORK/bootstrap.tmp" && mkdir "$WORK/bootstrap.tmp"
    tar -xzf "$WORK/bootstrap.tar.gz" -C "$WORK/bootstrap.tmp" --strip-components=1
    mv "$WORK/bootstrap.tmp" "$WORK/bootstrap"
    rm "$WORK/bootstrap.tar.gz"
fi
export BOOTSTRAP_GRAALVM="$WORK/bootstrap"
"$BOOTSTRAP_GRAALVM/bin/native-image" --version

### 4. Build de l'image isolate avec l'instrument
step "mx build (POLYGLOT_ISOLATES=js, LW_ISOLATE_INSTRUMENT=1)"
export POLYGLOT_ISOLATES=js
export LW_ISOLATE_INSTRUMENT=1
export LW_ISOLATE_PYTHON=1        # image COMBINEE js+python (une seule lib native)
export GENERATE_DEBUGINFO=false   # pas de debug info dans la .so
# Tas du builder native-image : l'image combinee manque de tas a 10 Go ([6/8] Compiling methods).
export NATIVE_IMAGE_OPTIONS="${NATIVE_IMAGE_OPTIONS:--J-Xmx20g}"
# LW_MIN_FREE_RAM_GB (machine partagee, posee par l'image du runner) : attendre LW_RAM_WAIT_MIN
# minutes au plus (30 par defaut) que ce nombre de Go soit disponible, plutot que de pousser les
# autres processus vers l'OOM.
if [ -n "${LW_MIN_FREE_RAM_GB:-}" ]; then
    wait_min=${LW_RAM_WAIT_MIN:-30}
    for minute in $(seq 0 "$wait_min"); do
        avail=$(awk '/^MemAvailable:/ {print int($2 / 1048576)}' /proc/meminfo)
        [ "$avail" -ge "$LW_MIN_FREE_RAM_GB" ] && break
        [ "$minute" -lt "$wait_min" ] || fail "RAM disponible : $avail Go après $wait_min min, il en faut $LW_MIN_FREE_RAM_GB"
        echo "RAM disponible : $avail Go, il en faut $LW_MIN_FREE_RAM_GB : nouvel essai dans une minute"
        sleep 60
    done
fi
# LW_SKIP_TOOLCHAIN_TEST=1 : seulement si le FS de build est noexec (voir README)
# --version-conflict-resolution ignore : graaljs et graalpython epinglent deux commits
# graal proches mais differents de la meme ligne ; on construit sur le pin graalpython.
bash "$WORK/mx/mx" -p "$WORK/graaljs/graal-js" --dy /substratevm,graalpython \
    --version-conflict-resolution ignore build \
    --dependencies graal-js:JS_ISOLATE_RESOURCES_LINUX_AMD64

### 5. Artefact + validation Gate2
step "Artefact + validation"
JAR="$WORK/graaljs/graal-js/mxbuild/linux-amd64/dists/jdk17/js-isolate-resources-linux-amd64.jar"
bash "$WORK/mx/mx" -p "$WORK/graaljs/graal-js" --dy /substratevm,graalpython --version-conflict-resolution ignore \
    classpath graal-js:JS_ISOLATE_LINUX_AMD64 | tail -1 > "$WORK/host-classpath.txt"
CP="$(cat "$WORK/host-classpath.txt"):$WORK/graaljs/graal-js/mxbuild/dists/lw-instrument.jar"
mkdir -p "$WORK/harness-out"
"$JAVA_HOME/bin/javac" -cp "$CP" -d "$WORK/harness-out" scripts/Gate2.java
# Gate2 sort en erreur sur un KO : dist/ ne reçoit qu'un jar validé.
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -cp "$CP:$WORK/harness-out" Gate2 \
    || fail "Gate2 KO (détail dans le journal du build)"
cp "$JAR" dist/

echo
echo "OK -> dist/js-isolate-resources-linux-amd64.jar ($(du -h dist/js-isolate-resources-linux-amd64.jar | cut -f1))"
