---
# calit-dny1
title: Publish images to Docker Hub alongside GHCR
status: completed
type: feature
priority: normal
created_at: 2026-09-26T10:08:36Z
updated_at: 2026-09-26T11:42:42Z
---

Add docker.io/asm0dey/calit to the merge job (login + metadata images); imagetools copies the GHCR digests, no rebuild.

- [x] User: create Hub repo asm0dey/calit + Read&Write access token
- [x] User: add DOCKERHUB_USERNAME / DOCKERHUB_TOKEN repo secrets
- [x] ci.yml: Docker Hub login + second image in metadata-action
- [x] ci.yml: sync README (links absolutised) to the Hub description on release
- [x] Docs: mention docker.io image on docs-site install page + README

## Summary of Changes

Images now publish to docker.io/asm0dey/calit alongside GHCR (#235, 701ca102).

- merge job: Docker Hub login + second image in metadata-action; one imagetools create copies the GHCR digests to both registries, identical tags. First main run put edge/edge-native on Hub with digests matching GHCR.
- release job (v* only): sed absolutises README links, peter-evans/dockerhub-description syncs it. Not yet exercised; first real run is the next tag.
- Both new actions SHA-pinned (Sonar S7637).
- README badge + mention; docs-site install-page row and Unreleased changelog bullet.
