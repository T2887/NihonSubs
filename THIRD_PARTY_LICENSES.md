# 第三方组件许可声明

本仓库自研代码（Java + Rust + `run.sh`）采用 MIT 许可（见 `LICENSE`）。
此外，App 运行时和构建过程引用了以下第三方组件，本仓库**只包含源码**，
**不打包以下组件的源码或预编译二进制**（`.gitignore` 已排除）。请按各自许可使用。

安装 App 所需的 .so 二进制、交叉编译工具链、ASR/翻译模型均需自行从下方来源获取。

## 运行时二进制（打进 APK 的 jniLibs）

| 组件 | 仓库内文件名 | 许可 | 来源 |
|---|---|---|---|
| FFmpeg | `libffmpeg.so` | LGPL-2.1+ | [FFmpeg](https://ffmpeg.org/) |
| llama.cpp (llama-server) | `libllamaserver.so` | MIT | [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) |
| sherpa-onnx | `libsherpaoffline.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so` | Apache-2.0 | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| ONNX Runtime | `libonnxruntime.so` | MIT | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) |

> (`libvadasr.so` 为开发期残留，未在流水线中使用，见 docs 内部说明 6.2/4.9。)

## ASR 模型（运行时额外下载，不进 APK）

| 模型 | 许可 | 说明 |
|---|---|---|
| FunASR/SenseVoice (SenseVoice-Small int8) | 官方 [Code of Conduct / 许可](https://github.com/modelscope/FunASR)（学术与研究许可，商用需确认） | 日文转写，补漏车道 |
| sherpa-onnx zipformer-ja (ReazonSpeech) | Apache-2.0 / 各自数据许可 | 日文转写，主车道 |
| Silero VAD | MIT | 语音活动检测（`assets/silero_vad.onnx` 打包在 APK） |

## 翻译模型

| 模型 | 许可 | 说明 |
|---|---|---|
| Hy-MT2-1.8B（自定义名） | 取决于实际基座模型的许可（未随仓库分发） | 本地日语→中文翻译 |

## 构建工具（仅在开发者机器上使用，不入库）

- Android SDK / build-tools / NDK —— Google 许可
- Rust + `aarch64-linux-android` target —— MIT/Apache-2.0
- 交叉编译工具链（ffmpeg 静态库 etc.）

> ⚠️ 发布者责任提醒：分发 APK 前请自行核对以上每个依赖在你目标市场（尤其国内应用市场）
> 的许可合规性，尤其是 SenseVoice 的商用条款。

## 完整许可证文本

各上游组件的完整许可文本请前往上表"来源"链接的官方仓库查看，
本仓库不重复托管以避免混淆来源。
