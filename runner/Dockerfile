# Runner GitHub Actions `graal-isolate`, qui construit l'image isolate (cf README, « Publier une
# version ») ; lancé par run.sh.
FROM myoung34/github-runner:ubuntu-noble
USER root
# Prérequis de build.sh (cf README).
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential zlib1g-dev cmake python3 git curl ca-certificates procps \
 && rm -rf /var/lib/apt/lists/*
# Le job n'a pas besoin d'être root : plus aucun binaire setuid/setgid, sudo compris (l'entrypoint
# passe à runner par gosu, depuis root), et l'arbre de build lui appartient (un volume vide prend
# ces droits au premier montage).
RUN find / -xdev -type f -perm /6000 -exec chmod ug-s {} + \
 && mkdir -p /work \
 && chown runner /work
# Machine partagée : build.sh attend (2 h au plus) que 20 Go, le tas du builder native-image,
# soient disponibles avant de construire. Le build en prend un peu plus au pic : s'il manque
# alors, c'est la compilation la première tuée (build.sh), pas un voisin.
ENV WORK=/work LW_MIN_FREE_RAM_GB=20 LW_RAM_WAIT_MIN=120
COPY --chmod=755 job-started.sh /runner-hooks/job-started.sh
ENV ACTIONS_RUNNER_HOOK_JOB_STARTED=/runner-hooks/job-started.sh
COPY --chmod=755 entrypoint.sh /entrypoint-guard.sh
ENTRYPOINT ["/entrypoint-guard.sh"]
# runsvc.sh relance le Listener après sa mise à jour automatique ; lancé seul, il s'arrête pour
# se mettre à jour et le conteneur redémarre sur l'ancienne version, en boucle
# (myoung34/docker-github-actions-runner#187, #251).
CMD ["./bin/runsvc.sh"]
