# NihonSubs — 日文字幕生成器（安卓端离线）

把手机上的 ASMR 音视频**离线**翻译成中文字幕：

输入音视频 → ffmpeg 转 16kHz 单声道 → 双车道 ASR 识别（zipformer 主 + SenseVoice 补漏）→ 本地 llama 翻译 → .chs.srt

全程不联网、不上传、只在本地推理。

## 特性

- **双车道识别融合**（zipformer 主 + SenseVoice 补漏）：zipformer 词级最准但可能冷启动丢句，
  SenseVoice 覆盖最广但字词常错；融合只对 zipformer 的"哑窗/空窗"用 SenseVoice 补，
  既准又省（SenseVoice 不用全片跑）。
- **纯离线翻译**：Hy-MT2 系列本地小模型，CPU 推理。
- **可执行二进制以 lib*.so 形式打包**，绕过 Android 10+ 不允许私有目录 exec 的限制。
- 只适配 arm64-v8a（ARM 64 位），模型与二进制均为此交叉编译。

## 源码结构（只含自研代码）

```
asmr-mobile-os/
├─ 自研代码
│  ├─ app/src/main/java/com/nihonsubs/   Java 界面 + 服务
│  ├─ app/src/main/assets/run.sh            识别/翻译/写字幕流水线
│  ├─ rust/asmr_mobile/                     Rust 工具（解析/融合/调翻译写 SRT）
│  └─ build_apk.ps1                         手工打包 APK
├─ 文档
│  ├─ LICENSE                               MIT
│  ├─ THIRD_PARTY_LICENSES.md               第三方许可声明
│  └─ docs/                                 使用说明 + 内部接线说明
└─ 需自行获取（不在仓库）
   ├─ ffmpeg/sherpa/llama/onnxruntime 的 .so
   └─ 模型：SenseVoice / zipformer-ja / 翻译模型
```

## 版本自检

App 会自动到 GitHub（兜底 Gitee）的 `latest.txt` 检查最新版本，发现新版时提示下载。

## 构建 APK

前提：
- Java / Android SDK build-tools + platform 34（用于 aapt2 / d8 / apksigner）
- 编译好的 Rust 二进制 `asmr_mobile`（aarch64-linux-android release），改名 `libasmrmobile.so`
- ffmpeg / sherpa-onnx / llama-server / onnxruntime 的 arm64 静态 .so
- 签名用的 keystore（.gitignore 已排除，需自备或生成）

把这些 .so 放进 `app/src/main/jniLibs/arm64-v8a/`，然后：

```powershell
cd app 目录
# 改 build_apk.ps1 里的 Sdk / Jdk / Python 路径为你本机
powershell -ExecutionPolicy Bypass -File build_apk.ps1
```

产出 `asmr-mobile.apk`。

## 运行依赖（模型，不进 APK）

首次使用前，把模型放到设备：

```
/sdcard/Android/data/com.nihonsubs/files/models/
    model.int8.onnx       SenseVoice int8（ONNX）
    hy.gguf               Hy-MT2 系列 Q4_K_M 翻译模型
    zipformer-ja/         可选：encoder.int8.onnx / decoder.onnx / joiner.onnx / tokens.txt
```

- 只放齐 zipformer-ja → 单车道 zipformer
- 只放齐 SenseVoice → 单车道 SenseVoice
- 两套都齐 → 双车道融合（推荐）
- 都不齐 → App 报错提示放置

模型体积合计约 1.4~2 GB，APK 本身约 58 MB。

## 实测性能（Redmi K30U / 天玑 1000+，双车道）

- 90 秒音频：约 0.5~0.7 分钟
- 16 分钟音频：约 6~7 分钟
- 更详细的各模型质量对比与融合设计见 docs/内部交接说明.md §4.9 / §4.10

## 许可

- 本仓库自研代码：MIT（见 LICENSE）
- 引用的第三方组件（ffmpeg / sherpa-onnx / llama.cpp / onnxruntime / 模型）：见 THIRD_PARTY_LICENSES.md，
  分发 APK 前请自行核对目标市场的许可合规性。
