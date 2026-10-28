#!/bin/bash
# Garde du runner (ACTIONS_RUNNER_HOOK_JOB_STARTED), dans l'image, hors de portée du dépôt : seuls
# un tag poussé ou un lancement manuel depuis master, que seuls les mainteneurs déclenchent,
# passent. Une pull request, même d'un fork, exécute ses propres fichiers de workflow et pourrait
# sinon viser ce runner ; un lancement manuel depuis une autre ref exécuterait le workflow de cette
# ref (celui des anciens commits publie et écrase la release).
case "${GITHUB_EVENT_NAME:-}:${GITHUB_REF:-}" in
	push:refs/tags/v*-combined-* | workflow_dispatch:refs/heads/master) ;;
	*) echo "Runner graal-isolate : job refusé (${GITHUB_EVENT_NAME:-?} ${GITHUB_REF:-?})" >&2; exit 1 ;;
esac
