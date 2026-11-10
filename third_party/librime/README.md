# librime prebuilt

This directory is no longer used by the build. Zhuyin goes through ChiaKey, and librime is not linked. CI does not build it with `tools/ci-engine.sh`. Do not commit binaries.

```
third_party/librime/
  include/rime_api.h
  jniLibs/arm64-v8a/librime.so
  jniLibs/arm64-v8a/<every DT_NEEDED library>
  jniLibs/x86_64/librime.so
  jniLibs/x86_64/<every DT_NEEDED library>
```

`rime_api.h` comes from the same librime revision as the `.so`.
The app no longer reads `rime.enabled`, and CMake does not link these libraries.

See the repository README for the load steps. librime itself is BSD-3-Clause.
OpenCC dictionaries, if linked, are Apache-2.0.
