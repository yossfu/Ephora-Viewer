# Ephora 2.28d — real Second Life only

This build removes user-facing offline/test render modes and restricts login to the production Second Life Agni grid.

## Surfaces kept
- Chat local
- Mundo 3D (region real de Second Life)
- Inventario
- Mapa
- Ajustes

## Renderer
- The 3D world is hard-forced to `RenderContent.REGION`.
- Synthetic texture decoding is not used by the live world.
- Diagnostic content/camera/probe hooks are no longer called from the production frame loop.
- Existing primitive geometry implementation is retained; this build does not claim a visual texture fix until tested on a real SL region.

## Version
Ephora 2.28d / versionCode 73
