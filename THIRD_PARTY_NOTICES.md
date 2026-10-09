# Third-party components

The module's source code is licensed under GPL-3.0, as reproduced in `LICENSE`. The APK uses the Full-GPL FFmpegKit build with GPL codecs. Kazumi source was inspected in `upstream/Kazumi`; it is not compiled into this APK.

| Component | Version / artifact | License declared by its published POM | Source |
| --- | --- | --- | --- |
| libxposed API | `io.github.libxposed:api:102.0.0` (compile only) | Apache-2.0 | https://github.com/libxposed/api |
| libxposed service / interface | `io.github.libxposed:service:102.0.0` | Apache-2.0 | https://github.com/libxposed/service |
| ShadowHook | `com.bytedance.android:shadowhook:2.0.1` | MIT | https://github.com/bytedance/android-inline-hook |
| miuix | `top.yukonga.miuix.kmp:miuix-android:0.8.8` | Apache-2.0 | https://github.com/compose-miuix-ui/miuix |
| FFmpegKit Full-GPL | `com.antonkarpenko:ffmpeg-kit-full-gpl:2.2.3` | LGPL-3.0 / GPL-3.0; Full-GPL variant used | https://github.com/sk3llo/ffmpeg_kit_flutter_android/tree/full-gpl |

FFmpegKit's published POM identifies its native backend as FFmpeg 8.1.2 (`n8.1.2`). The Full-GPL backend includes additional native codec libraries; their individual copyright and source obligations remain applicable. The upstream FFmpegKit repository URL returned unavailable during this build, so this checkout does not claim to contain all corresponding third-party native build sources. The exact Maven coordinates above are retained for reproducible dependency resolution.

AndroidX, JetBrains Compose, Kotlin and their transitive dependencies retain their own licenses. Dependency versions are declared in the Gradle source files; licenses embedded in dependencies are retained by Android packaging.

License references: [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html), [LGPL-3.0](https://www.gnu.org/licenses/lgpl-3.0.html), [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0), [MIT](https://opensource.org/license/mit).
