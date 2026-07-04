# graal-isolate — image isolate GraalJS custom Leek Wars

Fork *par patches* de l'image `js-isolate-linux-amd64-community` de GraalVM : on recompile la
lib isolate (`libpolyglotisolate.so`) en **embarquant notre instrument Truffle
`StatementCounter`** dans l'image native. Résultat : les IA polyglot (JS/TS) ont **à la fois**
la limite RAM par-poireau (`sandbox.MaxHeapMemory`, apport de l'isolate) **et** un compteur
d'opérations **déterministe** (statements guest, bit-reproductible pour les replays et
l'arène classée) — deux propriétés mutuellement exclusives avec les artefacts officiels.

Mesures du spike (2026-07-04) : déterminisme vérifié (run1 == run2), **overhead ~+3 %** sur le
compute guest (contre +4200 % pour l'`ExecutionListener` host-side), lecture hôte ~3 µs.

Tout est open source (variantes `-community`, MIT/UPL) : pas de fork de dépôt, juste deux
petits patches de la config de build mx + une classe Java, réappliqués sur les sources
officielles épinglées.

## Reproduire

```bash
./build.sh          # produit dist/js-isolate-resources-linux-amd64.jar (~57 Mo)
```

Prérequis : linux-amd64, ~20 Go de disque sur un FS **exécutable**, build-essential +
zlib1g-dev, python3, git, curl, ~8 Go de RAM. Durée ~15-30 min (image native ~5 min / 24 cœurs).
Le script est idempotent (`work/` est réutilisé) ; `WORK=/chemin ./build.sh` pour déplacer le
répertoire de travail. Il finit par exécuter le harnais `scripts/Gate2.java` : il DOIT afficher
`[2] OK` et `[3] ... -> DETERMINISTE`.

### Si le FS de build est noexec (ex : /media/hdd)

Trois composants veulent exécuter des binaires depuis l'arbre de build :
- `libffi` (script `configure`) → symlinker `work/graal/truffle/mxbuild/linux-amd64/libffi`
  vers un FS exécutable ;
- le smoke test du toolchain LLVM → `export LW_SKIP_TOOLCHAIN_TEST=1` (hook posé par
  `patches/graal.patch`) ;
- le stage1 GraalVM/jimage → déjà évité par `BOOTSTRAP_GRAALVM` (le script le fait toujours).

## Contenu

- `patches/graal.patch` — 2 fixes de build : `delattr(ignore)` inconditionnel de
  `PolyglotIsolateProject.resolveDeps` (casse hors config CI `--native-images`), et skip
  optionnel du smoke test LLVM (`LW_SKIP_TOOLCHAIN_TEST`).
- `patches/graaljs.patch` — enregistre le projet mx `com.leekwars.truffle.instrument` + la
  distribution module `LW_INSTRUMENT` dans la suite graal-js, et (gaté `LW_ISOLATE_INSTRUMENT=1`)
  l'ajoute aux `additional_image_path_artifacts` de l'image isolate +
  `--initialize-at-build-time=com.leekwars.generator.polyglot`.
- `src/com.leekwars.truffle.instrument/` — l'instrument (copié dans l'arbre graaljs au build).
- `scripts/Gate2.java` — harnais de validation (instrument listé, binding lisible,
  déterminisme, coûts).
- `scripts/Gate3Prod.java` — harnais config PROD (`SandboxPolicy.ISOLATED` + cascade
  `sandbox.Max*`) : option acceptée, binding lisible, déterminisme, anti-triche guest.
- `dist/` — artefact produit (recopié dans le repo generator, voir Intégration).

## Les 6 découvertes qui font tenir le montage

1. **Le lookup de service hôte ne traverse pas la frontière isolate** :
   `engine.getInstruments().get(ID).lookup(Counter.class)` = null (le nativebridge ne
   marshalle pas un service custom). Canal de lecture = **polyglot bindings** : l'instrument
   publie un exécutable `lwStatementCounter` dans chaque contexte (ContextsListener +
   `context.enter`), l'hôte lit `context.getPolyglotBindings().getMember(..).execute()`
   (execute() = lire, execute(x) = reset) — API standard, donc bridgée.
2. **Un instrument Truffle est lazy** et son activateur classique (le lookup) est cassé sous
   isolate → activation par **option** : `@Option` vide sur l'instrument →
   `.option("lw-statement-counter", "true")` sur l'Engine (comme cpusampler).
3. **`ThreadLocal` est blocklisté** en compilation runtime native-image ("Blocklisted methods
   are reachable for runtime compilation") → le compteur est un simple champ `long` (correct :
   un engine = un combat = un thread ; et l'incrément se PE-compile en add).
4. Le provider généré par le DSL doit être **`--initialize-at-build-time`** (package
   `com.leekwars.generator.polyglot`).
5. **La policy sandbox valide l'instrument ET son option** : sans `sandbox = SandboxPolicy.ISOLATED`
   sur `@TruffleInstrument.Registration` **et** sur `@Option` (défaut TRUSTED des deux côtés), un
   engine `SandboxPolicy.ISOLATED` (la config prod) refuse d'activer l'instrument. L'option est
   aussi `OptionStability.STABLE` pour ne pas exiger `allowExperimentalOptions(true)`.
6. **UNE seule lib isolate in-process par JVM** (limite GraalVM, indépendante de ce fork) : un
   worker qui a chargé l'isolate js ne peut plus charger l'isolate python ("A native library for
   engine.SpawnIsolate was already loaded"). Repli implémenté dans `PolyglotSandbox.engineFor` :
   le 2e langage bascule en isolate **processus externe** (`engine.IsolateMode=external`) — mais
   `sandbox.MaxHeapMemory` par contexte n'y est PAS appliqué (RAM bornée seulement par
   `engine.MaxIsolateMemory`).

## Intégration generator / worker

- L'artefact `dist/js-isolate-resources-linux-amd64.jar` est copié dans le repo generator
  (branche polyglot, privé) sous `libs/` et remplace la dépendance Maven
  `org.graalvm.polyglot:js-isolate-linux-amd64-community` dans `build.gradle`.
- `PolyglotSandbox` pose `.option("lw-statement-counter", "true")` sur l'engine isolate JS et
  lit le compteur via les polyglot bindings du contexte ; `PolyglotEntityAI.getOperations()`
  retrouve sa branche déterministe (reset par tour via `execute(reset)`).
- ✅ **Anti-triche : gratuit sous la config prod.** Sous `SandboxPolicy.ISOLATED`, le builtin
  `Polyglot` n'existe pas côté guest (`typeof Polyglot === 'undefined'`, vérifié par
  `Gate3Prod` [P4]) : un joueur ne peut ni importer ni reset le compteur. Seuls l'hôte et
  l'instrument (privilégiés) touchent les polyglot bindings.
- Python : les artefacts officiels `python-isolate-*` restent sans instrument (ops = temps CPU).
  Le même montage est à répliquer sur le build GraalPy si on veut le déterminisme Python.

## Bump de version GraalVM (maintenance)

1. Mettre à jour les pins de `build.sh` : `GRAALJS_TAG` (nouveau tag), `GRAAL_COMMIT` (lire
   `graal-js/mx.graal-js/suite.py` → suites → regex → version dans le tag graaljs),
   `MX_VERSION` (`graal/common.json` → mx_version), `BOOTSTRAP_TAG` (release du repo
   `graalvm/graalvm-ce-dev-builds` de la même ligne), `GRAAL_VERSION`.
2. `rm -rf work && ./build.sh` — si les patches ne s'appliquent plus, les régénérer (ils sont
   minuscules, cf. `patches/`).
3. Vérifier la sortie Gate2 (`[3] DETERMINISTE`), recopier `dist/*.jar` dans le generator,
   bump la version des AUTRES deps GraalVM de `build.gradle` à l'unisson, suite de tests
   polyglot du generator, push develop → CI worker-beta.
