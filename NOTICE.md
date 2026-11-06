# Notices

## Ambient sounds (since 1.7.0)

Flowstate ships **no recorded audio**. Rain, Library, Brown noise, Café, Fireplace and Waves are all
generated on the device at run time by `app/src/main/java/com/flowstate/focus/service/ambient/AmbientSynth.kt`
(original code in this repository: white/pink/brown noise generators, one-pole and band-pass filters,
and randomly timed synthetic events). No third-party samples, loops or sound libraries are used, so
there are no audio licences to attribute.

Third-party code libraries are listed in `app/build.gradle.kts` and `gradle/libs.versions.toml` and keep
their own licences (mostly Apache 2.0).
