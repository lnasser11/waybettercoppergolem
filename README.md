# waybettercoppergolem
Fabric mod: copper golems organize an existing storage room using item-frame chest labels.

## Target environment (pinned)

| Component | Version |
|---|---|
| Minecraft Java | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.152.1+26.2 |
| Mappings | Mojang official (Yarn was discontinued after snapshot 25w46a and does not exist for 26.x; Loom 1.17 uses mojmap by default) |
| Java | 25 (toolchain and runtime) |

## Building

```
./gradlew build          # mod jar in build/libs/
./gradlew runClient      # launch a dev client
```

Requires JDK 25 and network access to:
`maven.fabricmc.net`, `meta.fabricmc.net`, `piston-meta.mojang.com`,
`piston-data.mojang.com`, `libraries.minecraft.net`,
`resources.download.minecraft.net`, `services.gradle.org`, Maven Central.
