# Third-party notices

## Build-resolved dependencies (not bundled)

Kotlin, Compose Multiplatform and kotlinx.coroutines are licensed under Apache License 2.0.
Compose Desktop uses Skiko/Skia and additional dependencies, each with its upstream licensing and
notice files. The JDK is supplied by the user and has its own distribution terms. JUnit is a
test-only dependency under EPL 1.0. Pillow is optional tooling for sample regeneration, under the
HPND license; it is neither required at runtime nor distributed here.

The vendored source does not bundle those libraries or their native binaries. A compiled distribution must
retain their applicable license/NOTICE files, including Skia's third-party codec notices. Do not
use this short dependency summary as a substitute for the notices in the actual binary artifacts.

## Generated media

All files in `deck-effects/src/desktopTest/resources/animated-images/` were generated from
simple geometric shapes for the original bundle. Those media files are dedicated to the public domain
under CC0 1.0 Universal; see `deck-effects/src/desktopTest/resources/animated-images/CC0-1.0.txt`.
