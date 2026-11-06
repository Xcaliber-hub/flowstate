# Notices

## Ambient sounds

Since 1.7.1, four of the seven ambient sounds are short recorded loops shipped in
`app/src/main/res/raw/`. They were trimmed, resampled to 48 kHz, loudness-matched, made seamless
(the end is crossfaded into the start) and re-encoded as Ogg Vorbis for Flowstate.

| Sound | File in the app | Source | Author | Original file | Licence |
|---|---|---|---|---|---|
| Waves | `ambient_waves.ogg` | Pixabay | stereogenicstudio | "Beach 02" (`stereogenicstudio-beach-02-404144.mp3`) | Pixabay Content License |
| Café | `ambient_cafe.ogg` | Pixabay | mfcc | "Jazz restaurant cafe" (`mfcc-jazz-restaurant-cafe-616067.mp3`) | Pixabay Content License |
| Crickets | `ambient_crickets.ogg` | Pixabay | freesound_community | "Ambience night field cricket 01" (`freesound_community-ambience-night-field-cricket-01-7015.mp3`) | Pixabay Content License |
| Rain | `ambient_rain.ogg` | Mixkit | Mixkit | "Light rain loop" #2393 (`mixkit-light-rain-loop-2393.wav`) | Mixkit Sound Effects Free License |

- Pixabay Content License: https://pixabay.com/service/license-summary/
- Mixkit Sound Effects Free License: https://mixkit.co/license/#sfxFree

Both licences allow these sounds to be used inside an app, free and without attribution (credited
here anyway). They do **not** allow the audio to be redistributed on its own: the files in
`res/raw/` are part of Flowstate and must not be extracted and shared, sold or uploaded as
standalone sound files or sound packs.

The other three sounds (Library, Brown noise, Fireplace) are generated on the device at run time by
`app/src/main/java/com/flowstate/focus/service/ambient/AmbientSynth.kt` (original code in this
repository: noise generators, filters and randomly timed synthetic events) and use no third-party
audio.

## Code

Third-party code libraries are listed in `app/build.gradle.kts` and `gradle/libs.versions.toml` and keep
their own licences (mostly Apache 2.0).
