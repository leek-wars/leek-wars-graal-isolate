#!/usr/bin/env bash
# Enveloppe (root) de l'entrypoint de myoung34/github-runner. Celui-ci recopie à chaque démarrage
# tout le volume de configuration (inscriptible par runner) dans /actions-runner, où le runner lit
# aussi .env et .path, qui priment sur l'environnement de l'image. Un job pourrait donc y laisser
# de quoi désactiver la garde des jobs : on n'y garde que l'enregistrement du runner.
set -euo pipefail
shopt -s dotglob nullglob
for f in /runner-config/*; do
	case "${f##*/}" in
		.runner | .runner_migrated | .credentials | .credentials_migrated | .credentials_rsaparams) ;;
		*) rm -rf -- "$f" ;;
	esac
done
rm -f /actions-runner/.env /actions-runner/.path
exec /entrypoint.sh "$@"
