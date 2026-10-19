# librime prebuilt

GitHub Actions fills this directory on the runner (`tools/ci-engine.sh`). A local build can do the same. Do not commit the binaries.

```
third_party/librime/
  include/rime_api.h
  jniLibs/arm64-v8a/librime.so
  jniLibs/arm64-v8a/<every DT_NEEDED library>
  jniLibs/x86_64/librime.so
  jniLibs/x86_64/<every DT_NEEDED library>
```

`rime_api.h` comes from the same librime revision as the `.so`.
After the files are in place, set `rime.enabled=true` in `gradle.properties`.

See the repository README for the load steps. librime itself is BSD-3-Clause.
OpenCC dictionaries, if linked, are Apache-2.0.
