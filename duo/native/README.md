# Rebuilding libvulkan_renderer.so

GameNative ships the Vulkan renderer as a prebuilt library (`app/src/modern/jniLibs/arm64-v8a/libvulkan_renderer.so`);
Gradle does not compile `app/src/main/cpp`. GameNativeDuo adds `winlator/DuoSecondaryOutput.cpp` plus one-line hooks in
`VulkanRendererContext.{h,cpp}`, so the library must be rebuilt after changing that code **and after every upstream
merge that touches the renderer sources or the prebuilt .so**.

```bash
git submodule update --init --recursive app/src/main/cpp/extras/adrenotools app/src/main/cpp/lsfg-vk-android
mkdir -p /tmp/vkr && cd /tmp/vkr
cmake -G "Unix Makefiles" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/27.3.13750724/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release <repo>/duo/native
make -j16 vulkan_renderer
cp -f libvulkan_renderer.so <repo>/app/src/modern/jniLibs/arm64-v8a/libvulkan_renderer.so
```

Needs `glslangValidator` on PATH. Sanity check: `llvm-nm -D --defined-only` of the new and upstream libraries should
list the same symbols plus `Java_com_winlator_renderer_VulkanRenderer_nativeDuo*`.
