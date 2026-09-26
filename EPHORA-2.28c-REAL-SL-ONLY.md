# Ephora 2.28c — Real Second Life only

## Purpose
This build removes user-facing offline/diagnostic render modes and keeps the production world view on the live Second Life region.

Main navigation is limited to: Chat local, Mundo 3D, Inventario, Mapa y Ajustes.

## PRIM texture fix
Uncut, non-hollow square LINE-path box caps now use the same 3x3 planar grid strategy as Second Life Viewer `LLVolumeFace::createUnCutCubeCap()` instead of the generic center-fan cap.

This preserves planar cap UVs and avoids the center-apex UV singularity that can distort floor/ceiling textures.

## Regression
`slcore_tests` includes a regression check for 6 box faces, 24 indices per top/bottom cap, and 54 vertices for the uncut detail-3 box.

## Build
Version name: Ephora 2.28c
Version code: 72
