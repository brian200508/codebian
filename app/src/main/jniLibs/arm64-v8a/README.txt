This directory receives `libproot.so` (the proot binary renamed as a JNI
library so Android's PackageManager extracts it into the app's
`nativeLibraryDir`, the one location guaranteed to be exec-able on Android
10+). Run `scripts/fetch-assets.ps1` to populate it -- it is intentionally
gitignored since it's a binary build artifact, not source.
