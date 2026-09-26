---
# calit-dny1
title: Publish images to Docker Hub alongside GHCR
status: in-progress
type: feature
priority: normal
created_at: 2026-09-26T10:08:36Z
updated_at: 2026-09-26T10:33:39Z
---

Add docker.io/asm0dey/calit to the merge job (login + metadata images); imagetools copies the GHCR digests, no rebuild.

- [ ] User: create Hub repo asm0dey/calit + Read&Write access token
- [ ] User: add DOCKERHUB_USERNAME / DOCKERHUB_TOKEN repo secrets
- [x] ci.yml: Docker Hub login + second image in metadata-action
- [x] ci.yml: sync README (links absolutised) to the Hub description on release
- [ ] Docs: mention docker.io image on docs-site install page + README
