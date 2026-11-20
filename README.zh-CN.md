<h1 align="center">
  <img src="app/src/main/assets/logo.svg" width="48" height="48" align="absmiddle" alt=""> Operit AI — Cosio Fork
</h1>

<div align="center">
  <a href="README.md">English</a> | <a href="README.es.md">Español</a> | <a href="README.zh-CN.md">中文</a>
  <br>
  <img src="https://img.shields.io/github/last-commit/Cosio33/Operit_CosioFork" alt="Last Commit">
  <img src="https://img.shields.io/badge/Platform-Android_8.0%2B-brightgreen.svg" alt="Platform">
  <img src="https://img.shields.io/github/v/release/Cosio33/Operit_CosioFork" alt="Latest Release">
  <img src="https://img.shields.io/badge/license-LGPL--3.0-blue.svg" alt="License">
</div>

<div align="center">
  <img src="docs/assets/readme/operit-ai-banner-zh-cn.webp" width="100%" alt="Operit AI">
</div>

## 关于本仓库

这是 **[AAswordman/Operit](https://github.com/AAswordman/Operit) 的 Cosio33 分支**，一个开源的
Android AI 智能体平台。上游项目是这里一切的基座 —— 应用、工具生态、市场、记忆系统、工作区与终端 ——
并且仍然是代码库的上游权威来源。

本分支存在的唯一理由：**让内置的 llama.cpp 引擎在真实的 Android 硬件上真正可用。** 这意味着把原生
引擎编译成优化版本、让长提示词能够通过 prefill、解锁 ARM dot-product 内核、修掉会让进程崩溃的
缺陷，以及发布可以**直接覆盖安装**在旧版本之上、不丢失配置与聊天记录的新版本。

> **本 README 是为本分支重写的，并非照搬上游。** 完整的 Operit AI 功能清单请查看
> [上游 README](https://github.com/AAswordman/Operit#readme) 与[用户指南](https://operit.app/)。

### 本分支的改动一览

| 领域 | 上游 | 本分支 |
|------|------|--------|
| llama.cpp 版本 | 较旧的固定提交 | **v0.8.0**（`b11960`）—— 首个支持 **LiquidAI LFM3** 的版本 |
| 原生编译类型 | debug 变体为 `Debug` → 内核 **-O0** | 强制 **`Release`** → 内核 **-O2** |
| 长提示词 | 超过 `n_batch` 直接中止进程 | prefill 按 `min(n_batch, n_ubatch)` **分块** |
| ARM dot-product | 无法启用（单一 `arm64` 库） | 运行时加载 **7 个 CPU 变体**（`GGML_BACKEND_DL`） |
| 权重重排 | `GGML_CPU_REPACK=ON` → arm64 上 **SIGSEGV** | **已关闭**（`GGML_CPU_REPACK OFF`） |
| 工具调用 | 语法可能中止进程 / 输出 **0 token** | try/catch、语法置于 chain 首位、**只 accept 一次** |
| 发布签名 | 临时 debug keystore → 每次更新都要重装 | **稳定 keystore** → 更新可**覆盖安装** |
| 性能监控 | 仅有中文文案 | 已**翻译为西班牙语**（22 条字符串） |
| JVM 测试工作流 | 自导入上游后一直损坏 | **1333 个测试，0 失败** |

---

## 安装

### 环境要求

- **Android 8.0 (API 26) 及以上**，仅支持 **ARM64 (`arm64-v8a`)**
- APK 约 450 MB，另需为下载的模型预留空间
- 使用**本地模型**时需要「所有文件访问权限」（`MANAGE_EXTERNAL_STORAGE`）

### 方式 A — Obtanium（推荐，支持覆盖更新）

1. 打开 **Obtanium** → *Add app* → 来源选择 **GitHub Releases**
2. 仓库：`Cosio33/Operit_CosioFork`，tag：`RealeseV6`（或使用正则 `Realese.*` 跟随全部版本）
3. 安装。后续版本会作为**更新**出现，直接**覆盖安装**在当前版本之上，保留配置、聊天与模型。

### 方式 B — 直接下载 APK

从[发布页面](https://github.com/Cosio33/Operit_CosioFork/releases)下载
[`Operit-llamav0.8.0-v6.apk`](https://github.com/Cosio33/Operit_CosioFork/releases/download/RealeseV6/Operit-llamav0.8.0-v6.apk)
并安装。

> **安全提示：** 仅从[官方发布页面](https://github.com/Cosio33/Operit_CosioFork/releases)或
> [Operit 官网](https://operit.app/)获取安装包。来源不明的包可能被篡改，危及你的数据与设备。

> **覆盖更新：** 自 `RealeseV5` 起所有版本使用同一签名密钥，因此 `adb install -r` 与 Obtanium 更新
> 都可以直接覆盖安装。已在 CUBOT KINGKONG 9 EEA（Helio G99，Android 14）上验证：`firstInstallTime`
> 不变、12 个 `files/datastore/*.preferences_pb` 逐字节一致（sha256）、`objectbox/data.mdb` 未变，
> 且 `MANAGE_EXTERNAL_STORAGE` 仍然有效。只有 `RealeseV5` 本身因为引入新密钥需要先卸载一次。

---

## 本地推理：Android 上的 llama.cpp

本分支面向 **LiquidAI LFM3** 系列（LFM3-1.7B、LFM3-Omni-2B 等），通过 llama.cpp 的 JNI 绑定完全
在设备上运行。要在 ARM64 中端手机上跑通，需要解决五个并不显而易见的问题。

1. **原生引擎此前完全没有优化。** Android Gradle 插件在 debug 变体中设置
   `CMAKE_BUILD_TYPE=Debug`，而 CMake 的 Debug 只有 `-g`：llama.cpp 与 ggml 实际以 `-O0` 编译。用
   `llvm-objdump -d` 测量热点 `ggml_vec_dot_q4_0_q8_0`：`-O0` 为 **2592 字节**，`-O2` 仅 **696
   字节**。因此在 `add_subdirectory()` 之前强制改为 Release。
2. **超过 `n_batch` 的提示词会杀死进程。** 旧版把整个 prompt 塞进一次 `llama_batch_get_one()`，
   `n_batch=512` 时历史一长就触发 `GGML_ASSERT(n_tokens_all <= cparams.n_batch)`，且该断言在
   Release 下依然生效，进程直接 abort 并陷入重启循环。现在 prefill 按
   `min(n_batch, n_ubatch)` 分块，decoder 与 encoder 同样处理。日志会打印
   `Prefill decode start: prompt_tokens=… chunk_limit=512 chunks=N`。
3. **ARM dot-product 内核无法启用 —— 且强行启用会致命。** ggml 用编译期宏
   `__ARM_FEATURE_DOTPROD` 门控 int8 dot-product 内核，没有运行时回退；单一 `+dotprod` 库在
   ARMv8.0 设备上会 **SIGILL**。安全做法是按特性集分别编译共享库并在运行时择优（需要
   `BUILD_SHARED_LIBS` + `GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS`）。Android 上
   `ggml_backend_load_all()` 会在 `/proc/self/exe`（即 `app_process64`）中查找，因此改用
   `ggml_backend_load_all_from_path()` 传入真实目录。
4. **权重重排在 arm64 上会 SIGSEGV。** `GGML_CPU_REPACK` 默认开启，会在加载时把量化权重重排为
   「8x4」布局，而该路径与多变体 CPU 后端不兼容
   （[ggml-org/llama.cpp#30583](https://github.com/ggml-org/llama.cpp/issues/30583)），崩溃栈位于
   `ggml_gemv_q4_K_8x4_q8_K`。关闭重排不影响 dot-product：常规量化矩阵乘法路径由
   `__ARM_FEATURE_DOTPROD` 单独门控，`armv8.2` 变体依然使用 `sdot`。
5. **工具调用可能中止进程或返回 0 token。** 一方面 `llama_grammar_accept_token()` 会抛
   `std::runtime_error` 且原绑定没有 `try/catch`，异常跨越 JNI 导致进程终止；另一方面原代码对
   同一 token 调用了两次 `llama_sampler_accept()`，lazy 语法第二次找不到可接受项 →
   `grammar.stacks` 为空 → 抛出 → 工具调用输出 0 token（同时也让惩罚采样器对每个 token 重复计数）。
   现在的做法与 upstream `common/sampling.cpp` 一致：只 accept 一次、语法采样器置于 chain 首位、
   语法采样器保持在 chain 之外、sample+accept 外层加 `try/catch`。

---

## 性能实测

在 **CUBOT KINGKONG 9 EEA**（Helio G99，Android 14）上，`n_ctx=4096`、`max_new=256`、同一对话
连续 5 次生成：

| 线程数 | Prefill | Decode |
|--------|---------|--------|
| 4 | **9.01 tok/s** | **5.60 tok/s** |
| 8 | 10.05 tok/s | 4.09 tok/s |

拟合耗时模型：`total ≈ 40.5 + 0.1146 × prompt_tokens`（4 线程）、`≈ 42.5 + 0.1170 × prompt_tokens`
（8 线程），**4 线程在所有提示词长度下都更优**，因此本分支默认 `threads=4`。

关键结论：**prefill 占总耗时的 65–77 %** 且属于计算密集型，而 decode 受内存带宽限制 —— 这正是
解锁 dot-product 内核成为正确优化方向的原因。

> `n_ctx` 目前由会话工厂硬编码为 **2048**（`AIServiceFactory.buildAndroidLlamaSessionConfig()`），
> 设置界面没有对应选项。提示词接近该上限时，绑定会从前方截断，而 Jinja 模板中最先丢失的正是
> system prompt 与工具 schema。

---

## 从源码构建

构建需要外部的 `subpack.zip`、`jniLibs.zip`、`libs.zip` 以及初始化好的 `terminal` 子模块；默认
本地 STT 模型按 `app/config/stt-model-assets.properties` 下载并校验。详见上游的
[构建指南](https://github.com/AAswordman/Operit/blob/main/docs/doc-src/dev-core/BUILDING.md)。

llama.cpp 源码**未内嵌仓库**，由 `cmake/operit_git_source.cmake` 在构建时拉取，并在
`llm/llama/CMakeLists.txt` 中以 SHA 固定为 v0.8.0（`8642541b…`）：

```cmake
operit_prepare_git_source(
    OPERIT_LLAMA_CPP_SOURCE_DIR
    OPERIT_LLAMA_CPP_BINARY_DIR
    llama_cpp
    "https://github.com/ggml-org/llama.cpp.git"
    "8642541b255bfb5dc39cd351a178be09013a55d6"
)
```

可用 `-DOPERIT_LLAMA_CPP_GIT_REF=<sha|tag>` 覆盖。

---

## CI 与发布

- **`Android Build`** —— 产出 APK 制品。
- **`Android JVM tests`** —— JVM 单元测试（**1333 个测试，0 失败**）。该工作流自导入上游后一直损坏，
  修复方式是移植上游的测试改动以及上游对 `ReleasedProviderModelKeyDecoder` 的生产代码修改。

自 `RealeseV5` 起，所有 release 使用**稳定密钥**签名（仓库 Secrets 中的
`OPERIT_KEYSTORE_BASE64`、`OPERIT_KEYSTORE_PASSWORD`、`OPERIT_KEY_ALIAS`、
`OPERIT_KEY_PASSWORD`），因此更新可以直接覆盖安装。

> 发布时**必须递增 `versionCode`**：版本号相同的情况下，即使签名一致，Obtanium 也不会提示更新。

上传 asset 请使用 **`uploads.github.com`**（`api.github.com` 会上传返回 `404`）。

---

## 已知限制

- **小模型的工具调用不稳定。** LFM3-1.7B 常生成看似合理却不符合 schema 的工具调用；绑定层已不再
  崩溃也不再丢 token，剩余差距属于模型能力问题。
- **`n_ctx` 固定为 2048** 且未在界面暴露，长对话会从前方截断。
- **Ubuntu/proot rootfs 在完全重装后会重新生成**（覆盖更新则保留）。
- **本地模型需要 `MANAGE_EXTERNAL_STORAGE`**，卸载会丢失权限，更新不会。

---

## 上游、致谢与许可证

- **上游项目：** [AAswordman/Operit](https://github.com/AAswordman/Operit)
- **跨平台后续版本：** [Operit 2](https://github.com/AAswordman/Operit2)
- **推理引擎：** [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) v0.8.0，构建时拉取，
  未内嵌于本仓库。

本仓库主要代码采用 **[GNU LGPL v3 (LGPL-3.0-only)](https://spdx.org/licenses/LGPL-3.0-only.html)**
许可证；工具、示例、模板与第三方依赖可能采用其他许可证，请查阅 [LICENSE](LICENSE) 及各目录下的
许可文件。

<div align="center">
  <sub>Operit AI by the Operit Team · 分支维护者 <a href="https://github.com/Cosio33">Cosio33</a></sub>
</div>
