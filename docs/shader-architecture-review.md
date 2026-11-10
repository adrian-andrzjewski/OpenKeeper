# OpenKeeper shader architecture review

**Review date:** 2026-11-10  
**Reviewed revision:** `135f3f9a7ff4782fc662c01230c4535c549738d1` (`master`, 16 commits behind `origin/master`)  
**Scope:** checked-in Java, Gradle configuration, and shader/material assets under `assets/`. This is a source review; no code was changed, and no build, shader compilation, runtime capture, or benchmark was run. Original DKII files and locally converted `.j3o`/`.j3m` assets are not part of this review, so their material variants and real-scene light counts remain unverified.

## Executive assessment

The game uses jMonkeyEngine 3.9's `AssetManager`/`Material` pipeline, with a custom DKII-style lighting path as the dominant world shader. Terrain and most KMF models use a Gouraud/per-vertex shader. Java packs camera-visible point lights into one shared RGBA32F texture; the shader evaluates that list at every vertex. A second map-sized RGBA8 texture supplies bilinearly blended tile ambient and explored state. This shared-data design keeps neighboring terrain batches on the same light set and is a sound base for the current look.

The largest performance risk is the product of visible vertices and visible lights: camera-frustum culling is global, while the vertex shader tests every remaining light for every vertex. A second likely cost is Java scene-graph traversal and material writes repeated for every world model and effect each frame, even though the shared light texture already carries changing light values. Both are source-verified costs; their frame-time impact needs profiling.

The principal visual limitation is that the gameplay path has no wired shadow renderer, and the custom DK lighting material has no shadow techniques. Gameplay can attach an SSAO post-process when its setting is enabled, but the DK material definitions do not declare the `PreNormalPass` technique found on `LightingSprite`; coverage of terrain and KMF models should be confirmed in a rendered capture. The current DK shader is intentionally simple and retro-oriented: diffuse texture times additive point lighting, without normal/specular material response.

## Architecture map

| Area | Current implementation |
|---|---|
| World materials | `KmfModelLoader` creates `MatDefs/DkLighting/Vertex.j3md` materials for KMF textures. Terrain is grouped into page `BatchNode`s and the resulting materials are bound to shared lighting textures. See [KmfModelLoader.java](../src/main/java/toniarts/openkeeper/tools/convert/KmfModelLoader.java#L676), [MapViewController.java](../src/main/java/toniarts/openkeeper/view/map/MapViewController.java#L227), and [MapViewController.java](../src/main/java/toniarts/openkeeper/view/map/MapViewController.java#L812). |
| Light data | `SceneLights` creates a one-row RGBA32F texture: one header texel plus three texels per light. The header carries count and global ambient; each light carries position/radius, color/intensity, and flags. Every update camera-culls the source list, rewrites active entries, and marks the image dirty. See [SceneLights.java](../src/main/java/toniarts/openkeeper/view/lighting/SceneLights.java#L61) and [SceneLights.java](../src/main/java/toniarts/openkeeper/view/lighting/SceneLights.java#L147). |
| Per-tile data | A map-sized RGBA8 texture stores halved ambient RGB and explored alpha. `Vertex.vert` samples it bilinearly in world XZ, then applies the global ambient and point lights. See [SceneLights.java](../src/main/java/toniarts/openkeeper/view/lighting/SceneLights.java#L273) and [Vertex.vert](../assets/Shaders/DkLighting/Vertex.vert#L57). |
| Thing/effect lighting | `AssetUtils.applyVertexLight` converts older `FlatLight` materials when needed and binds the shared textures. `PlayerEntityViewState` and `VisualEffect` call it during their update paths. See [AssetUtils.java](../src/main/java/toniarts/openkeeper/utils/AssetUtils.java#L306), [PlayerEntityViewState.java](../src/main/java/toniarts/openkeeper/view/PlayerEntityViewState.java#L251), and [VisualEffect.java](../src/main/java/toniarts/openkeeper/view/effect/VisualEffect.java#L632). |
| Other custom materials | `LightingSprite` serves animated sprite/material paths including torch flames and selected animated surfaces; `UnitFlower` draws unit status markers; `ProgressIndicator` draws a radial GUI indicator; `MovieMaterial` converts three Y/Cb/Cr planes for cutscenes. These are specialized paths rather than alternatives to the main world shader. |
| Engine/backend | Gradle pins jMonkeyEngine `3.9.0-stable` and includes `jme3-lwjgl3`; there is no Vulkan backend dependency or shader compilation task in the checked-in build configuration. See [build.gradle](../build.gradle#L12) and [build.gradle](../build.gradle#L74). |

## Prioritized findings

| Rank | Finding | Expected impact | Effort | Risk / confidence |
|---|---|---:|---:|---|
| 1 | Every `Vertex`-material vertex evaluates the full camera-visible light list | High in scenes with many lights and a distant camera zoom | Medium | Algorithm is verified; frame-time impact is unmeasured. Culling changes need seam/fog checks. |
| 2 | World models and effect meshes are recursively revisited every frame | Medium CPU cost; may create avoidable allocation/GC pressure | Low to medium | Repeated traversal and setters are verified; backend uniform/state cost is unmeasured. |
| 3 | Gameplay shadow integration is absent; SSAO coverage needs confirmation | High visual impact if shadows/AO coverage is a goal | High | No gameplay shadow renderer is wired in checked-in source. Missing DK normal-pass technique is verified; its SSAO effect should be confirmed at runtime. |
| 4 | Fixed light texture width, unconditional dirtying, and hard list cap | Medium portability/transfer risk; rare visual truncation | Medium | Dimensions and break-on-cap behavior are verified; actual upload path and overflow frequency are unmeasured. |
| 5 | Object-light exclusion appears unwired for dungeon-heart parts | Local visual correctness | Low | The unused IDs, unused `objectId`, and `false` call argument are verified; confirm the intended scene behavior. |
| 6 | `LightingSprite` material contract is much broader than its main shader; alpha path is suspect | Low to medium visual/maintenance impact | Low | Source mismatch is verified; texture conventions and visible effect need asset/runtime validation. |
| 7 | Shader/CPU contracts and backend assumptions are maintained manually | Medium future compatibility risk | Medium to high | GLSL versions, duplicated constants, and absent Vulkan dependency are verified. |
| 8 | UI/video shaders have small, targeted optimization opportunities | Low except during video playback | Low to medium | Operations are visible in source; likely impact is small and workload-dependent. |

### 1. Full light-list work per `Vertex`-material vertex

`SceneLights.MAX_LIGHTS` is 3,084 and must match `DK_MAX_LIGHTS` in the shader. `SceneLights.update` rejects lights outside the camera frustum expanded by a fixed margin, but does not create a light list for each terrain page or mesh. For each vertex, [Vertex.vert](../assets/Shaders/DkLighting/Vertex.vert#L90) loops up to the active count, fetches three texels per light, decodes flags, and calls the point-light function. The distance/radius rejection occurs only after the three data fetches. The point-light routine also evaluates the normal response and falloff for every candidate ([DkFalloff.glsllib](../assets/Shaders/DkLighting/DkFalloff.glsllib#L57)).

**Why it matters:** work scales approximately with `visible vertices × camera-visible lights` across terrain and world models, including lights that cannot affect a particular page or mesh. The frustum cull helps when many lights are off-camera, but zooming out can make many map lights visible at once. This is the leading GPU bottleneck hypothesis; no GPU timings were collected.

**Recommendation:** first measure the slope against visible vertex and light counts. If it is material, build conservative page/cell light lists from each light's radius plus the 0.1-tile falloff tail. A page can omit a light only when its influence volume cannot intersect that page; retain consistent inclusion at shared boundaries to avoid seams. A clustered/tiled list is a later option if the page approach leaves too many candidates. Keep the current exact per-vertex evaluator as the visual reference while changing list construction.

### 2. Per-frame model traversal and material writes

Every `PlayerEntityViewState.update` walks all object, creature, door, and trap models and calls `applyVertexLight` ([PlayerEntityViewState.java](../src/main/java/toniarts/openkeeper/view/PlayerEntityViewState.java#L262)). Effects similarly revisit a light-bearing model and each spawned mesh instance ([VisualEffect.java](../src/main/java/toniarts/openkeeper/view/effect/VisualEffect.java#L635), [VisualEffect.java](../src/main/java/toniarts/openkeeper/view/effect/VisualEffect.java#L669)). Each call creates a material list and visitor, walks geometry, sets stable values such as `MinLight`, `IgnoreObjectLights`, and `AmbientScale`, then scans the list to bind textures. Most models already use `Vertex` materials, so the common path still performs traversal and parameter writes.

**Recommendation:** do material conversion, classification, and shared-texture binding when a model is attached or its materials change. Keep a compact set of affected materials for lifecycle changes. Since light values arrive through the one shared texture, do not revisit every mesh just because a nearby light flickered. Update only genuinely dynamic per-material values. Verify whether jME skips equal-value parameter writes before attributing GPU uniform traffic; the CPU traversal and allocations are already evident.

### 3. Gameplay shadows and SSAO normal pass

The gameplay map marks floors `Receive` and walls `CastAndReceive`, but those flags alone do not render shadows. The only `DirectionalLightShadowRenderer` construction found is in `ModelViewer` ([ModelViewer.java](../src/main/java/toniarts/openkeeper/tools/modelviewer/ModelViewer.java#L394)); no gameplay shadow renderer/filter is attached in `Main`. In addition, `DkLighting/Vertex.j3md` and `Flat.j3md` have only their default technique, while `LightingSprite.j3md` declares `PreShadow`, `PostShadow`, and `PreNormalPass` techniques. The main application does attach an `SSAOFilter` when the setting is enabled ([Main.java](../src/main/java/toniarts/openkeeper/Main.java#L673)).

**Recommendation:** capture the SSAO normal pre-pass with terrain, walls, and KMF models visible. Confirm whether the dominant DK materials contribute; if not, provide an appropriate normal technique or a deliberate fallback. For dynamic shadows, decide on a tiered design rather than enabling an expensive all-scene pass by accident: start with a small set of tested receivers/casters, shadow distance and resolution limits, and a comparison with the current SSAO-only path. Preserve an unshadowed DK-style option if that is the intended visual baseline.

### 4. Fixed-width light texture and truncation behavior

At capacity, `SceneLights` allocates a 9,253 × 1 RGBA32F image (`1 + 3 × 3,084` texels), or 148,048 bytes of pixel data. `update` marks the image dirty every frame, even when the number of active lights is small or unchanged. This is a full-image size, but whether the backend re-uploads all bytes should be checked with a GPU capture. The texture also requires an implementation `MAX_TEXTURE_SIZE` of at least 9,253; OpenGL rejects texture dimensions above the implementation limit ([OpenGL 3.2 specification, texturing](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf)). The current code does not query that limit or adapt the allocation.

When more than 3,084 lights survive camera culling, the code logs once and breaks out of the source loop. Later sources are dropped in list order, without ranking by likely screen contribution.

**Recommendation:** record current visible count, bytes uploaded, update frequency, and the runtime texture-size limit. Consider a 2D-packed light texture or a buffer-texture path where supported, with a compatible fallback. Avoid resizing/reallocating the image every frame. If capacity is exceeded in real maps, choose a deterministic, relevance-aware subset or spatially partition the list rather than silently discarding the tail.

### 5. Dungeon-heart object-light exception

`PlayerEntityViewState` declares `DUNGEON_HEART_PART_IDS` and reads each object's `objectId`, with a comment saying those parts should not receive object-carried lights. The value is unused, and the object path always calls `applyVertexLight(..., false)` ([PlayerEntityViewState.java](../src/main/java/toniarts/openkeeper/view/PlayerEntityViewState.java#L168), [PlayerEntityViewState.java](../src/main/java/toniarts/openkeeper/view/PlayerEntityViewState.java#L276)). The shader's `IgnoreObjectLights` branch is therefore not selected for these objects. `Ceiling` explicitly selects that branch, demonstrating that the filter is active elsewhere ([Ceiling.java](../src/main/java/toniarts/openkeeper/view/map/Ceiling.java#L112)).

**Recommendation:** verify the dungeon-heart scene with object lighting enabled. If the exception is still required, pass the object classification through when setting up its material; otherwise remove the stale set/read/comment so the shader contract reflects the intended behavior.

### 6. `LightingSprite` material/shader mismatch and alpha semantics

`LightingSprite.j3md` exposes normal/specular/parallax maps, fog, environment maps, skinning, morphing, instancing, several shadow maps, and duplicated `SinglePass`/`MultiPass` techniques. Its main vertex/fragment shaders only animate U coordinates and sample `DiffuseMap`; the main fragment shader does not implement the advertised lighting/material features ([LightingSprite.j3md](../assets/MatDefs/LightingSprite.j3md#L3), [LightingSprite.frag](../assets/Shaders/LightingSprite.frag#L1)). This makes the material definition difficult to reason about and creates a larger apparent feature surface than the active shader supports.

There is also an alpha contract to validate: `AssetUtils` marks animated alpha resources transparent, selects alpha blending, and sets `AlphaDiscardThreshold` ([AssetUtils.java](../src/main/java/toniarts/openkeeper/utils/AssetUtils.java#L482)). The shader tests **RGB** against that threshold, not the sampled alpha, then writes output alpha as `1.0` ([LightingSprite.frag](../assets/Shaders/LightingSprite.frag#L13)). That behaves as a black-RGB cutout, not general texture-alpha blending. It may match current converted assets, but it will lose semitransparent edges if those assets rely on alpha.

**Recommendation:** inspect representative animated alpha textures and compare their source/conversion convention to the shader output. Then either make the shader's alpha test/blend mode match that convention, or document a deliberate black-key/additive contract. Reduce the material parameters and techniques to those used by this sprite path, or split out a small dedicated animated-sprite material. Retain the shadow technique only if an active caller needs it.

### 7. Shader contracts, compilation coverage, and backend portability

Several values are duplicated across Java and GLSL: `MAX_LIGHTS`/`DK_MAX_LIGHTS`, the three-texel packing and flag bits, and the CPU/GPU falloff equations. `SceneLights.computeFlatLight` is also not a full semantic twin of the vertex shader: it does not apply the shader's fog visibility, torch edge tint, or yielding-light blend. `Flat` appears to be a legacy path in checked-in Java: KMF creation uses `Vertex`, and `applyVertexLight` promotes materials with a `FlatLight` parameter. Legacy `.j3m` files outside the checked-in tree may still use it.

The main `Vertex` and `Flat` material definitions declare only `GLSL150`; other custom definitions declare different profile sets, including `GLSL100` and `GLSL310`. The build packages assets but has no shader compiler/variant test task. Existing lighting tests exercise Java-side light behavior, not GLSL compilation or Java/GLSL packing parity. The renderer dependency is OpenGL/LWJGL3 and the source has OpenGL-specific debug setup; there is no Vulkan backend in the checked-in dependencies.

**Recommendation:** define the light-buffer layout and flags once in a small contract and validate the shader-facing values against it. Add a compile/smoke matrix for each material technique and the minimum supported profile, plus focused parity checks for falloff, fog, and flags. For future backends, preserve a backend-neutral light/material feature contract and provide separate OpenGL and Vulkan resource/shader implementations; do not assume the current `sampler2D`/GLSL150 binding layout transfers directly to storage buffers and SPIR-V.

## Visual quality and modernization opportunities

Keep the DK-style diffuse and point-light path as the baseline. If higher fidelity is wanted, add a separate material path for selected actors, hero objects, or close-up props with normal and roughness/specular response; avoid requiring new maps across all original terrain assets. Validate the texture color-space and framebuffer behavior before adding tone mapping or gamma transforms, since the current DK shaders do not make those conversions explicit.

The existing SSAO pass is the lowest-cost starting point for depth cues, once its normal-buffer coverage is verified. Dynamic shadows should be introduced as a quality tier with a bounded shadow distance, resolution, and caster set. The page/cell light-list recommendation above is also the best route to modern clustered lighting: it reduces per-vertex work while preserving the current material response, and its data contract can later map to OpenGL buffer resources or Vulkan storage buffers.

### 8. Lower-priority shader opportunities

- `UnitFlower.vert` forwards eight `vec4` color values as varyings, although they are per-material colors; `UnitFlower.frag` chooses among them using time. These can be fragment uniforms or a uniform color array, reducing interpolation inputs. The status marker is a small workload, so profile before changing it ([UnitFlower.vert](../assets/Shaders/UnitFlower.vert#L12), [UnitFlower.frag](../assets/Shaders/UnitFlower.frag#L19)).
- `ProgressIndicator.frag` evaluates `atan` per fragment and discards outside the sweep. This is reasonable for a small icon; a CPU-updated mesh or cheaper angular representation is only worthwhile if many large indicators are drawn ([ProgressIndicator.frag](../assets/Shaders/Gui/ProgressIndicator.frag#L15)).
- `MovieMaterial.frag` samples luma, Cb, and Cr separately and applies YCbCr conversion; `MovieMaterial.update` updates three plane textures per new frame. This is isolated to video playback. An interleaved chroma plane can reduce sampler count, but may add CPU packing or upload cost, so compare total frame time and video upload time before changing it ([MovieMaterial.frag](../assets/Shaders/Video/MovieMaterial.frag#L33), [MovieMaterial.java](../src/main/java/toniarts/openkeeper/video/MovieMaterial.java#L157)).
- `Vertex.vert` declares and writes `vGlow` as zero, and `Vertex.frag` carries it through an add/max path. That is a small removable varying/operation opportunity if no intended glow producer is missing. The `Flat` and `Vertex` fragments also repeat alpha-discard and tint logic; shared helpers or an explicitly retired legacy path would reduce drift.

## Recommended validation sequence

1. **Establish a baseline:** on the same build, map, camera, resolution, and quality settings, capture CPU frame time, GPU time for the main world pass and SSAO, visible vertex/triangle counts, camera-visible lights, and material count. Include a dense torch/terrain-light map, zoomed-out view, and an effect-heavy scene.
2. **Test scaling:** sweep light counts while keeping geometry fixed, then vary geometry while keeping lights fixed. Record per-page visible-light counts and use GPU timers to tell vertex-light cost from draw/fragment cost. Separately time `PlayerEntityViewState.update` and effect lighting; count visited geometries and temporary allocations.
3. **Inspect transfers and limits:** capture `LightData` texture creation/update calls, actual bytes transferred, and `GL_MAX_TEXTURE_SIZE` on each supported renderer/profile. Test the 3,084-light boundary and verify the reported overflow behavior.
4. **Validate image contracts:** capture fog boundaries, shared page edges, torch colors, water/lava surfaces, dungeon-heart parts, and animated alpha sprites before and after any change. Check the SSAO normal buffer and compare shadow/AO variants at fixed settings.
5. **Compile variants:** compile every custom material technique on the supported OpenGL profiles, including animated/static, alpha/no-alpha, and `IgnoreObjectLights` variants. A future Vulkan spike should separately validate descriptor layout, coordinate/depth conventions, and image output; a successful OpenGL build is not Vulkan evidence.

No benchmark or runtime acceptance claim is made in this report. The priorities above are architectural estimates to guide measured follow-up work.
