# Build-time branding plan

Status: **not implemented**. There is no Gradle configuration or APK yet.
[Issue #3](https://github.com/atluixx/usual/issues/3) tracks the smallest
Android scaffold and branding implementation. `assets/logo.svg` is a provisional
README mark, not a generated launcher icon.

## Configuration contract to implement

```dotenv
APP_NAME=Usual
APP_LOGO=path/to/logo.png
```

Resolve environment variables first, then ignored local
`android/branding.local.properties`, then bundled defaults when values are
absent. Do not auto-load arbitrary `.env` files. An explicitly empty or invalid
configured value is an error, not a reason to silently use a default.

`APP_LOGO` names a local PNG build input, resolved relative to the `android/`
root unless absolute. URLs and runtime file access are unsupported. Validate
file existence/readability, PNG decoding, supported size/aspect ratio, and
decoded pixel bounds. Errors should name the configuration key and offending
path clearly. The Android issue must choose and document exact supported
dimensions and default asset location.

Use one Gradle task/provider configuration to generate escaped application-name
resources, an in-app brand image, density-specific legacy launcher images, and
adaptive/round launcher resources into the build directory. Register both the
name value and logo bytes as task inputs so changing either invalidates outputs.
Avoid editing checked-in source resources during a build or using an external
image CLI that is not part of the reproducible toolchain.

Wire the manifest launcher label and Compose brand surfaces to generated
resources. Keep a stable application ID, independent of name/logo. Debug/release
must resolve the documented intended branding through the same path, without
hidden release-only defaults. Signing keys stay outside the repository.

## Verification required in the Android PR

- Build the default debug APK and inspect its launcher label/resources.
- Rebuild with a second name/logo; verify changed label/icon/in-app surfaces and
  unchanged application ID. Include names needing XML escaping.
- Test missing/unreadable/corrupt/wrong-type/unsupported-dimension assets and
  empty explicit values. Ensure useful build failures.
- Build twice with different values without manual source edits or stale assets;
  verify task input/cache behavior and unsigned release resources.
- Document exact working commands, local file contents, paths, supported logo
  constraints, and APK output paths once those commands exist.

Changing environment variables affects the next build. Installing the resulting
APK is necessary to change the installed application's name/icon. It cannot
rebrand an already installed app dynamically. Runtime branding infrastructure is
outside scope.
