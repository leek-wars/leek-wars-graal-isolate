# graal-isolate — image isolate GraalVM custom Leek Wars (js + python)

Fork *par patches* des images isolate GraalVM : on recompile la lib isolate
(`libpolyglotisolate.so`) en une **image COMBINÉE js+python** qui **embarque notre instrument
Truffle `StatementCounter`**. Résultat, pour les IA polyglot JS/TS **et Python** :
- limite RAM par-poireau (`sandbox.MaxHeapMemory`, apport de l'isolate) ;
- compteur d'opérations **déterministe** (statements guest, bit-reproductible pour les replays
  et l'arène classée) — mutuellement exclusifs avec les artefacts officiels ;
- mesure de la **mémoire retenue** par chaque contexte (`System.usedRAM`), le calcul même de
  `sandbox.MaxHeapMemory` (depuis `combined-6`) ;
- **une seule lib native** : la limite GraalVM « une lib isolate in-process par JVM » ne
  s'applique plus (avant, un worker qui avait joué du js ne pouvait plus créer d'engine python).

Mesures du spike (2026-07-04) : déterminisme vérifié (run1 == run2), **overhead ~+3 %** sur le
compute guest (contre +4200 % pour l'`ExecutionListener` host-side), lecture hôte ~3 µs.

Tout est open source (variantes `-community`, MIT/UPL) : pas de fork de dépôt, juste deux
petits patches de la config de build mx + une classe Java, réappliqués sur les sources
officielles épinglées.

## Télécharger (sans builder)

Le jar est publié en **release publique** — inutile de refaire le build lourd. Le générateur
épingle la version qu'il utilise dans `libs/isolate-image.txt` ; dans son dépôt,
`sh libs/fetch-isolate.sh` la télécharge et vérifie son sha256 : c'est ce que fait tout build du
générateur, et ce dont un joueur a besoin pour tester une IA JS/Python en local.

## Reproduire (builder soi-même)

```bash
./build.sh          # produit dist/js-isolate-resources-linux-amd64.jar (~127 Mo, js+python)
```

> La CI (`.github/workflows/build.yml`) fait exactement ça sur le runner self-hosted
> `graal-isolate`, puis publie le jar en release sur un tag (cf. « Publier une version »).

Prérequis : linux-amd64, ~20 Go de disque sur un FS **exécutable**, build-essential +
zlib1g-dev, python3, git, curl, ~24 Go de RAM libre (`build.sh` donne 20 Go de tas au builder
native-image, à 10 Go il en manque). Avec `LW_MIN_FREE_RAM_GB=<Go>`, il attend que cette RAM soit
disponible, `LW_RAM_WAIT_MIN` minutes au plus (30 par défaut). Durée ~15-30 min (~10 min en
incrémental). Le script est idempotent (`work/` est réutilisé, et repart de zéro quand un pin de
version change) ; `WORK=/chemin ./build.sh` pour déplacer le répertoire de travail. Il finit par
le harnais `scripts/Gate2.java` (binding lisible, déterminisme, mémoire retenue), qui le fait
échouer sur un KO : `dist/` ne reçoit qu'un jar validé.

### Si le FS de build est noexec (ex : /media/hdd)

`scripts/build-noexec-hdd.sh` automatise tout ça (WORK sur le HDD, composants exécutables
relocalisés vers `~/.cache/lw-graal-isolate-exec` + symlinks). Composants qui veulent
exécuter des binaires depuis l'arbre de build :
- `libffi` (script `configure`) → symlinker `work/graal/truffle/mxbuild/linux-amd64/libffi`
  vers un FS exécutable ;
- le smoke test du toolchain LLVM → `export LW_SKIP_TOOLCHAIN_TEST=1` (hook posé par
  `patches/graal.patch`) ;
- le stage1 GraalVM/jimage → déjà évité par `BOOTSTRAP_GRAALVM` (le script le fait toujours) ;
- les JDKs (`work/jdks`, `work/bootstrap`) → symlinker vers un FS exécutable ;
- les outils téléchargés par mx dans `mx-cache` (`NINJA_*`, `MUSL_GCC_TOOLCHAIN_*`) →
  relocaliser + symlink ;
- le `LLVM_TOOLCHAIN` extrait dans `work/graal/sdk/mxbuild/linux-amd64` (clang++ du
  launcher nativebridge, PAS couvert par LW_SKIP_TOOLCHAIN_TEST) → relocaliser + symlink.

## Contenu

- `patches/graal.patch` — 2 fixes de build : `delattr(ignore)` inconditionnel de
  `PolyglotIsolateProject.resolveDeps` (casse hors config CI `--native-images`), et skip
  optionnel du smoke test LLVM (`LW_SKIP_TOOLCHAIN_TEST`).
- `patches/graaljs.patch` — enregistre le projet mx `com.leekwars.truffle.instrument` + la
  distribution module `LW_INSTRUMENT` dans la suite graal-js ; gaté `LW_ISOLATE_INSTRUMENT=1`,
  l'ajoute aux `additional_image_path_artifacts` de l'image isolate +
  `--initialize-at-build-time=com.leekwars.generator.polyglot` ; gaté `LW_ISOLATE_PYTHON=1`,
  ajoute `graalpython:PYTHON_POM` + `additional_language_ids=['python']` (image combinée).
- `patches/graalpython.patch` — comptage des opérations Python par EXPRESSION : le compilateur
  Bytecode DSL de GraalPy (`RootNodeCompiler`) n'émet que `Tag(Statement)`, et seulement au
  changement de ligne (une ligne = un événement, compréhensions et boucles sur une ligne
  gratuites). Le patch ouvre un `Tag(Expression)` sur chaque nœud d'expression exécuté
  (opérateurs, appels, attributs, indices, noms, constantes, littéraux, lambdas, compréhensions),
  comme GraalJS, plus un par itération de compréhension, et un `Tag(Statement)` par statement
  même sur la ligne de son parent. L'instrument compte statements + expressions pour tous les
  langages.
  Il rétroporte aussi le correctif amont `ad0345f` (oracle/graalpython#1083 : fuite des nœuds
  racine sous engine partagé, jusqu'au `MemoryError` de tout l'isolate). À retirer dès qu'une
  release le contient.
- `src/com.leekwars.truffle.instrument/` — l'instrument (copié dans l'arbre graaljs au build).
- `scripts/Gate2.java` — harnais de validation (instrument listé, binding lisible,
  déterminisme, coûts, mémoire retenue lue par l'hôte et depuis un appel guest → hôte).
- `scripts/Gate3Prod.java` — harnais config PROD (`SandboxPolicy.ISOLATED` + cascade
  `sandbox.Max*`) : option acceptée, binding lisible, déterminisme, anti-triche guest.
- `dist/` — artefact produit (publié en release, voir « Publier une version »).

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
   engine.SpawnIsolate was already loaded"). C'est la raison d'être de l'**image COMBINÉE** :
   une seule lib pour les deux langages, tout reste in-process. Un repli subsiste dans
   `PolyglotSandbox.engineFor` (si des images séparées revenaient) : le 2e langage bascule en
   isolate **processus externe** (`engine.IsolateMode=external`) — mais `sandbox.MaxHeapMemory`
   par contexte n'y est PAS appliqué et le multi-fichiers y est cassé.

## Publier une version

1. Pousser le commit, puis un tag `v<version GraalVM>-combined-<n>` : le workflow se déclenche
   sur le tag, construit sur le runner `graal-isolate` et publie la release de ce tag. Son résumé
   (et le sha256 des notes de la release) donne la ligne « `<tag> <sha256>` » à reporter. Si
   seule la publication a échoué : `gh run rerun --failed <run>` (le jar construit est repris de
   l'artefact ; supprimer d'abord un brouillon de release qu'elle aurait laissé). Lancé à la
   main, le workflow ne fait que le build et Gate2.
2. Dans le générateur, remplacer la ligne de `libs/isolate-image.txt` par celle-ci et pousser.

Le runner est un conteneur durable (`runner/Dockerfile`, lancé par `runner/run.sh`, qui contrôle
l'image avant de le créer) :
- aucun binaire setuid, `no-new-privileges`, pas de docker.sock ni de dossier de l'hôte, mémoire
  bornée, compilation première tuée si l'hôte en manque ; build retardé tant que la machine n'a pas
  la RAM du tas native-image (`LW_MIN_FREE_RAM_GB`) ; deux volumes : l'arbre de build (builds
  incrémentaux) et l'enregistrement ; recréé à chaque `runner/run.sh` (le runner repart de la
  version de l'image et se remet à jour) ;
- enregistré une fois par un jeton d'enregistrement du dépôt (valable une heure) ; il garde
  ensuite ses propres identifiants, qui ne servent qu'à recevoir les jobs de ce dépôt ;
- sa garde `runner/job-started.sh` n'accepte qu'un tag poussé ou un lancement manuel depuis
  master : une pull request, ou un lancement depuis une autre ref, exécuterait ses propres
  fichiers de workflow ;
- non éphémère : `runner/entrypoint.sh` remet sa configuration à zéro à chaque démarrage, mais un
  job accepté peut modifier le conteneur (binaires du runner compris) jusqu'à ce qu'il soit
  recréé, et l'arbre de build (volume `/work`, partagé par tous les builds, dont ceux d'un tag
  poussé sur n'importe quel commit) jusqu'à ce que son volume soit supprimé : le runner ne doit
  exécuter que du code de confiance ;
- le build n'a qu'un jeton en lecture ; la release est publiée par un second job, sur un runner
  hébergé par GitHub, sans jamais écraser une release existante.

## Intégration generator / worker

- L'artefact `dist/js-isolate-resources-linux-amd64.jar`, publié en release, est épinglé par le
  générateur (public, `leek-wars/leek-wars-generator`, cf. « Télécharger »). Il remplace les
  DEUX dépendances Maven `org.graalvm.polyglot:{js,python}-isolate-linux-amd64-community` dans
  `build.gradle` (l'uber-jar worker y gagne ~80 Mo).
- `PolyglotSandbox` pose `.option("lw-statement-counter", "true")` sur l'engine isolate JS et
  lit le compteur via les polyglot bindings du contexte ; `PolyglotEntityAI.getOperations()`
  retrouve sa branche déterministe (reset par tour via `execute(reset)`).
- L'instrument publie aussi `lwHeapSize` : `execute(stopAtBytes)` = octets retenus par le contexte
  (`TruffleInstrument.Env.calculateContextHeapSize`, calcul arrêté au-delà de `stopAtBytes`).
  `PolyglotEntityAI.getUsedRAM()` le lit pour `System.usedRAM` ; le calcul parcourt le tas
  (~2 ms par Mo), d'où un cache : la mesure suivante attend que l'IA ait calculé quatre fois la
  durée de la précédente.
- ✅ **Anti-triche : gratuit sous la config prod.** Sous `SandboxPolicy.ISOLATED`, le builtin
  `Polyglot` n'existe pas côté guest (`typeof Polyglot === 'undefined'`, vérifié par
  `Gate3Prod` [P4]) : un joueur ne peut ni importer ni reset le compteur. Seuls l'hôte et
  l'instrument (privilégiés) touchent les polyglot bindings.
- Python est DANS l'image combinée (`LW_ISOLATE_PYTHON=1`, mécanisme `additional_language_ids`
  déjà utilisé par GraalVM pour embarquer wasm dans l'image js) : mêmes garanties que js.

## Bump de version GraalVM (maintenance)

1. Mettre à jour les pins de `build.sh` : `GRAALJS_TAG`/`GRAALPYTHON_TAG` (nouveau tag),
   `GRAAL_COMMIT` (le plus RÉCENT des pins regex des suites graaljs ET graalpython —
   `mx.graal-js/suite.py` et `mx.graalpython/suite.py` → suites → regex → version),
   `MX_VERSION` (max des exigences, graalpython est le plus exigeant), `BOOTSTRAP_URL`
   (release stable de `graalvm/graalvm-ce-builds` de la même ligne), `GRAAL_VERSION`.
2. `./build.sh` (il repart de zéro : les pins ont changé) — si les patches ne s'appliquent plus,
   les régénérer (ils sont minuscules, cf. `patches/`).
3. Pour tester avant de publier : copier `dist/*.jar` dans `libs/` du generator (sans lancer
   `libs/fetch-isolate.sh`, qui remettrait le jar épinglé), bump la version des AUTRES deps
   GraalVM de `build.gradle` à l'unisson, suite de tests polyglot du generator, puis publier
   (cf. « Publier une version »).
