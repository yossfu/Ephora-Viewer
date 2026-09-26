EPHORA 2.28e — REAL SECOND LIFE ONLY + PRIM CAP UV/WINDING FIX

User-facing sections:
1. Chat local
2. Mundo 3D
3. Inventario
4. Mapa
5. Ajustes

Second Life target:
- Production Second Life (Agni) only.
- Login grid selector/custom grid removed.
- SLConnection rejects non-Agni targets.
- AndroidManifest exposes no offline/test/diagnostic Activity.
- FilamentWorldView is forced to RenderContent.REGION at runtime.
- SyntheticTextureDecoder is not used by the world texture pipeline.

PRIM geometry:
- Uncut, non-hollow BOX caps use the LLVolumeFace-style 3x3 planar grid.
- TOP cap swaps only the U components, matching LLVolumeFace::createUnCutCubeCap.
- TOP cap triangles wind toward +Z; BOTTOM cap triangles wind toward -Z.
- Native slcore regression tests pass with -Wall -Wextra -Werror.
