# Build-time branding

`APP_NAME` and `APP_LOGO` control generated Android resources for all variants.
The permanent live application ID remains `com.okoda.usual`. Defaults are
`Usual` and the provisional `assets/logo.png` (matching `assets/logo.svg`).
Branding changes take effect in a rebuilt/reinstalled APK, not an installed
application when its environment changes.

From the repository root:

```sh
APP_NAME='Our Usual' APP_LOGO=assets/logo.png \
  ./android/gradlew -p android :app:assembleLiveDebug
```

Or create the ignored `branding.local.properties` at the repository root:

```properties
APP_NAME=Our Usual
APP_LOGO=assets/logo.png
```

Then run `./android/gradlew -p android :app:assembleLiveDebug` normally.
Environment values override that file; omitted properties use defaults. Relative
logo paths resolve from the repository root; absolute local build paths also
work. The live flavor requires your ignored
`android/app/src/live/google-services.json`. For a secret-free local build use
`:app:assembleEmulatorDebug` instead. Outputs are under
`android/app/build/outputs/apk/{live|emulator}/debug/`.

Names must be 1–60 characters on one line. Logos must be readable, valid square
PNGs, 192–4096 pixels per side and at most 10 MiB. Transparent PNGs are
supported. Explicit blank, missing, damaged or unsuitable assets fail the build
with an `APP_LOGO` error; there is no silent fallback for a configured invalid
file. Keep large unrelated images and private branding files outside tracked
source.

The `generateBranding` Gradle task creates the launcher label, in-app logo,
legacy density icons, adaptive foreground/background and round icon resources
under `android/app/build/generated/branding/res`. Generated assets are ignored.
Adaptive icons use Android's safe zone; leave intentional internal padding in
your source logo when needed. Notification status-bar icons remain a simple
monochrome mark for Android legibility. Debug and release share the same
branding inputs; use the intended configuration for each build. CI uses
defaults.

Gradle tracks the name and logo contents, so changing either regenerates
resources on rebuild without editing Kotlin source or unrelated configuration.
Remove/unset overrides to restore defaults. Release packaging/signing is a
separate development step; branding does not change the Firebase registration,
OAuth configuration or application ID.
