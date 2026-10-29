#!/usr/bin/env bash
# Construit l'image du runner `graal-isolate` et (re)crée son conteneur, sur la machine qui
# construit l'image isolate (Docker, ~30 Go de RAM et ~25 Go de disque pour les volumes).
# Premier lancement : le script demande un jeton d'enregistrement du dépôt (Settings → Actions
# → Runners → New self-hosted runner, ligne `./config.sh … --token XXXX`), valable une heure.
# Ensuite le runner réutilise son enregistrement (volume de configuration).
# Chaque lancement recrée le conteneur (réglages de ce script appliqués, conteneur qu'un job aurait
# modifié remis à neuf), sauf pendant un build en cours ; un job attribué à cet instant même
# serait perdu : le relancer. Le runner repart alors de la version de l'image et se remet à jour
# (une minute environ).
set -euo pipefail
cd "$(dirname "$0")"

name=leek-wars-graal-isolate-runner # image et conteneur
config=$name-config
work=leek-wars-graal-isolate-work

# Sans --pull : l'image de base (tag mutable d'un tiers) ne change que sur un `docker pull` voulu.
# Contrôles et conteneur visent l'ID de l'image construite, pas son tag, qu'un autre build déplacerait.
iid=$(mktemp)
trap 'rm -f "$iid"' EXIT
docker build --iidfile "$iid" -t "$name" .
img=$(cat "$iid")
docker image inspect "$img" > /dev/null # l'ID se résout bien en image (sinon rien n'est touché)

busy() { # avant de toucher à l'ancien runner : ne pas couper un build
	if docker exec "$name" pgrep -x Runner.Worker > /dev/null 2>&1; then
		echo "Un build est en cours dans $name : relancer le script après lui." >&2
		exit 1
	fi
}
busy

fail=0
expect() { # <libellé> <commande…> : la commande doit réussir
	local label=$1
	shift
	if "$@" > /dev/null 2>&1; then echo "OK : $label"; else echo "ÉCHEC : $label"; fail=1; fi
}
in_image() { # <utilisateur> <script sh> : dans un conteneur jetable de l'image
	docker run --rm --entrypoint sh -u "$1" "$img" -c "$2"
}
guard() { # <code attendu> <libellé> <événement> <ref> : la garde des jobs telle que le runner la lance
	expect "garde : $2" in_image runner \
		"GITHUB_EVENT_NAME=$3 GITHUB_REF=$4 \"\$ACTIONS_RUNNER_HOOK_JOB_STARTED\"; test \$? -eq $1"
}

# Contrôles de l'image AVANT de toucher au runner. Un échec ferme : l'ancien runner est arrêté
# (il pourrait avoir été modifié par un job), aucun nouveau n'est créé.
expect "aucun binaire setuid" in_image root 'f=$(find / -xdev -type f -perm /6000) && [ -z "$f" ]'
expect "garde des jobs déclarée, exécutable, hors d'atteinte de runner" in_image runner \
	'h=$ACTIONS_RUNNER_HOOK_JOB_STARTED; [ "$h" = /runner-hooks/job-started.sh ] && [ -x "$h" ] && [ ! -w "$h" ] && [ ! -w "${h%/*}" ]'
guard 0 "tag v*-combined-* accepté" push refs/tags/v1-combined-1
guard 0 "lancement manuel depuis master accepté" workflow_dispatch refs/heads/master
guard 1 "lancement manuel depuis un tag refusé" workflow_dispatch refs/tags/v1-combined-1
guard 1 "pull request refusée" pull_request refs/pull/1/merge
guard 1 "push de branche refusé" push refs/heads/master
expect "runner écrit dans /work" docker run --rm --entrypoint test -u runner -v "$work:/work" "$img" -w /work
if [ "$fail" != 0 ]; then
	busy
	docker stop "$name" > /dev/null 2>&1 || true
	echo "Image refusée : aucun runner créé, l'ancien est arrêté." >&2
	exit 1
fi

registered=false
docker run --rm --entrypoint test -v "$config:/runner-config" "$img" -f /runner-config/.runner && registered=true
RUNNER_TOKEN="" # exporté, passé à docker sans valeur : hors de la ligne de commande
if [ "$registered" = false ]; then
	read -rsp "Jeton d'enregistrement du runner : " RUNNER_TOKEN
	echo
fi
export RUNNER_TOKEN
busy
docker rm -f "$name" > /dev/null 2>&1 || true
# Ni docker.sock ni dossier de l'hôte : deux volumes à lui. Mémoire bornée (build.sh donne
# 20 Go de tas au builder native-image ; build.sh désigne la compilation comme première victime
# de l'OOM, le Listener garde un score normal).
docker run -d --name "$name" --restart unless-stopped \
	--memory 30g --memory-swap 30g --cpus 12 --pids-limit 8192 \
	--cap-drop NET_RAW --security-opt no-new-privileges \
	-v "$config:/runner-config" \
	-v "$work:/work" \
	-e REPO_URL=https://github.com/leek-wars/leek-wars-graal-isolate \
	-e RUNNER_SCOPE=repo \
	-e RUNNER_NAME=graal-isolate \
	-e LABELS=graal-isolate \
	-e RUN_AS_ROOT=false \
	-e CONFIGURED_ACTIONS_RUNNER_FILES_DIR=/runner-config \
	-e DISABLE_AUTOMATIC_DEREGISTRATION=true \
	-e RUNNER_TOKEN \
	"$img" > /dev/null
# Interrompu avant la fin des contrôles : runner arrêté.
trap 'rm -f "$iid"; docker stop "$name" > /dev/null 2>&1' INT TERM HUP

# Le runner se met souvent à jour au démarrage, ce qui relance le Listener : attendre (5 min au
# plus) le même Listener pendant 30 s (StartedAt distingue un redémarrage du conteneur au même
# PID ; -x : nom exact du processus).
echo "Attente du Runner.Listener (5 min au plus)…"
prev='' stable=0
for _ in $(seq 60); do
	cur="$(docker container inspect -f '{{.State.StartedAt}}' "$name"):$(docker exec "$name" pgrep -u runner -x Runner.Listener 2> /dev/null || true)"
	if [ "${cur##*:}" != '' ] && [ "$cur" = "$prev" ]; then stable=$((stable + 1)); else stable=0; fi
	[ "$stable" -ge 6 ] && break
	prev=$cur
	sleep 5
done
pid=${cur##*:}
expect "Runner.Listener sous runner, même processus pendant 30 s" test "$stable" -ge 6
expect "runner enregistré (configuration gardée)" docker exec "$name" test -f /runner-config/.runner
expect "ce Runner.Listener a la garde dans son environnement" docker exec -u runner "$name" sh -c \
	"tr '\\0' '\\n' < /proc/$pid/environ | grep -qx ACTIONS_RUNNER_HOOK_JOB_STARTED=/runner-hooks/job-started.sh"
trap - INT TERM HUP
if [ "$fail" != 0 ]; then
	# Pas de job sur un runner mal contrôlé.
	if docker stop "$name" > /dev/null; then
		echo "Runner arrêté : comprendre l'ÉCHEC (docker logs $name), puis relancer ce script." >&2
	else
		echo "ÉCHEC de docker stop : arrêter $name à la main avant tout." >&2
	fi
fi
exit "$fail"
