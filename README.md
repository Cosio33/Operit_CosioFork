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
  <img src="docs/assets/readme/operit-ai-banner-en.webp" width="100%" alt="Operit AI">
</div>

## About this repository

This is **Cosio33's fork of [AAswordman/Operit](https://github.com/AAswordman/Operit)**, an open-source AI
Agent platform for Android. The upstream project is the base of everything here — the app, the tool
ecosystem, the marketplace, the memory system, the workspaces and the terminal — and it remains the
upstream of record for the code base.

The fork exists for one reason: **making the on-device llama.cpp engine actually usable on real
Android hardware.** That means building the native engine optimized instead of unoptimized, making
large prompts survive the prefill, unlocking the ARM dot-product kernels, fixing the crashes that
took the process down, and shipping releases that can be **installed on top of the previous one**
without losing your configuration, your chats or your models.

> **This README is written for the fork, not copied from upstream.** It documents what this fork
> changed, why, and how to build, install and verify it. For the full feature catalogue of Operit
> AI, see the [upstream README](https://github.com/AAswordman/Operit#readme) and the
> [user guide](https://operit.app/).

### What this fork changes, at a glance

| Area | Upstream | This fork |
|------|----------|------------|
| llama.cpp version | older pin | **v0.8.0** (`b11960`) — first release with **LiquidAI LFM3** support |
| Native build type | `Debug` in debug variants → kernels at **-O0** | forced **`Release`** → kernels at **-O2** |
| Large prompts | aborts the process past `n_batch` | prefill **chunked** into `min(n_batch, n_ubatch)` |
| ARM dot-product | not reachable (single `arm64` lib) | **7 CPU variants** loaded at runtime (`GGML_BACKEND_DL`) |
| Weight repack | `GGML_CPU_REPACK=ON` → **SIGSEGV** on arm64 | **disabled** (`GGML_CPU_REPACK OFF`) |
| Tool calling | grammar could abort the process / emit **0 tokens** | try/catch, grammar first in the chain, **single accept** |
| Release signing | ephemeral debug keystore → every update forced a reinstall | **stable keystore** → updates install **in place** |
| Performance monitor | Chinese-only strings | **Spanish** translation (22 strings) |
| JVM test workflow | broken since the upstream import | **1333 tests, 0 failures** |

---

## Installation

### Requirements

- **Android 8.0 (API 26) or newer**, **ARM64 (`arm64-v8a`)** only
- ~450 MB for the APK, plus the size of the models you download
- For **local models**: the "All files access" permission (`MANAGE_EXTERNAL_STORAGE`)

### Option A — Obtanium (recommended, supports in-place updates)

1. Open **Obtanium** → *Add app* → source **GitHub Releases**
2. Repository: `Cosio33/Operit_CosioFork`, tag: `RealeseV6` (or the regex `Realese.*` to follow every release)
3. Install. Future releases appear as **updates** and install **on top** of the current version,
   keeping your configuration, chats and models.

### Option B — Direct APK

Download [`Operit-llamav0.8.0-v6.apk`](https://github.com/Cosio33/Operit_CosioFork/releases/download/RealeseV6/Operit-llamav0.8.0-v6.apk)
from the [Releases page](https://github.com/Cosio33/Operit_CosioFork/releases) and install it.

> **Security notice:** only install packages from the official
> [Releases page](https://github.com/Cosio33/Operit_CosioFork/releases) or the
> [Operit website](https://operit.app/). Packages from unknown sources may be modified and may put
> your data or device at risk.

> **Updating in place:** every release published from `RealeseV5` onwards is signed with the same
> key, so `adb install -r` / Obtanium upgrades succeed without uninstalling. Verified on a
> CUBOT KINGKONG 9 EEA (Helio G99, Android 14): `firstInstallTime` unchanged, all 12
> `files/datastore/*.preferences_pb` byte-identical (sha256), `objectbox/data.mdb` unchanged, and
> `MANAGE_EXTERNAL_STORAGE` still granted. The only version that had to be uninstalled first was
> `RealeseV5` itself, because it introduced the stable key.

---

## Local inference: llama.cpp on Android

This fork targets the **LiquidAI LFM3** family (LFM3-1.7B, LFM3-Omni-2B, …) running fully
on-device through a JNI binding of llama.cpp. Five non-obvious problems had to be solved to make
that work on a mid-range ARM64 phone.

### 1. The native engine was being compiled without optimizations

The Android Gradle plugin sets `CMAKE_BUILD_TYPE=Debug` for the `debug` and `clone` variants, and
CMake's `Debug` flags are `-g` only — so **llama.cpp and ggml were compiled at `-O0`** in every
APK that shipped. The symptom is invisible: the app runs, it is just several times slower than it
should be. Measured with `llvm-objdump -d`, the hot quantised matmul
`ggml_vec_dot_q4_0_q8_0` occupied **2592 bytes at -O0** against **696 bytes at -O2**.

```cmake
# llm/llama/CMakeLists.txt
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()
```

> The absence of `.debug_info` in the APK is **not** evidence of an optimized build: AGP strips
> the native libraries when packaging. Compare function sizes instead.

### 2. Prompts larger than `n_batch` killed the process

The binding pushed the whole prompt in a single `llama_batch_get_one()`. With `n_batch = 512` and a
conversation history that keeps growing, the prompt crossed the limit and
`GGML_ASSERT(n_tokens_all <= cparams.n_batch)` aborted the **process** — `GGML_ASSERT` is
unconditional, it is not disabled in release builds. The app then restarted, reloaded the same
history and aborted again: an instant crash loop.

The fix is to feed the prefill in chunks of `min(n_batch, n_ubatch)`, for both the decoder and the
encoder (the encoder has its own `GGML_ASSERT(n_ubatch >= n_tokens)`):

```cpp
// llm/llama/src/main/cpp/llama_jni_stub.cpp
uint32_t limit = llama_n_batch(ctx);
const uint32_t nUbatch = llama_n_ubatch(ctx);
```

Upstream semantics are preserved: with `pos == nullptr` the positions are derived from the KV cache
state, and with `logits == nullptr` only the last token of each batch is an output — so chunking
is equivalent to a single batch for causal models and the sampling result does not change.

The log line that proves it is working:

```
Prefill decode start: prompt_tokens=587 n_ctx=2048 n_batch=512 n_ubatch=512 chunk_limit=512 chunks=2
```

### 3. ARM dot-product kernels were unreachable — and enabling them naively is fatal

ggml gates the int8 dot-product kernels behind the **compile-time** macro
`__ARM_FEATURE_DOTPROD` (`ggml/src/ggml-cpu/arch/arm/quants.c`), with no runtime fallback. A single
library built with `+dotprod` would execute the `sdot` instruction on every device and die with
**SIGILL** on CPUs that do not implement it (ARMv8.0 / A53).

The safe path is what upstream does: ship one shared library per feature set and pick the best
supported one at runtime, using the score function in
`ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp`. That requires the backend to be a dynamically loaded
module.

```cmake
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
```

llama.cpp v0.8.0 already ships the Android variants, so no upstream patch is needed:
`android_armv8.0_1`, `android_armv8.2_1` *(DOTPROD)*, `android_armv8.2_2`, `android_armv8.6_1`,
`android_armv9.0_1`, `android_armv9.2_1`, `android_armv9.2_2`.

**Android-specific trap:** `ggml_backend_load_all()` searches `get_executable_path()`, which on
Android is `/proc/self/exe` → `/system/bin/app_process64`, not the directory that holds the `.so`
files. The variants are therefore loaded explicitly with the real path, obtained with `dladdr()`:

```cpp
ggml_backend_load_all_from_path(dir.c_str());
```

Because the engine is no longer a single object file, **every** shared library and every
dynamically loaded backend module also needs 16 KB page alignment (Android 15+ requirement):

```cmake
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
```

### 4. Weight repacking crashed on arm64 (SIGSEGV)

`GGML_CPU_REPACK` is `ON` by default in ggml and re-orders the quantised weights at load time into
the "8x4" layout used by the llamafile-style kernels (`ggml_gemv_q4_K_8x4_q8_K` and friends). That
path is **not supported together with the multi-variant CPU backend**
([ggml-org/llama.cpp#30583](https://github.com/ggml-org/llama.cpp/issues/30583)). On this device it
crashed with a **SIGSEGV** inside the dot-product variant:

```
#00 ggml_gemv_q4_K_8x4_q8_K
#01 ggml::cpu::repack::tensor_traits<block_q4_K, 4, 8, (ggml_type)15>::forward_mul_mat_one_chunk
...
#07 libomp.so (__kmp_invoke_microtask)
```

i.e. the repack kernel reads past the end of the re-ordered weight buffer. The same happens with
the Q6_K kernel (`ggml_gemm_q6_K_8x4_q8_K`).

Turning it off costs nothing: the regular quantised matmul path is gated by `__ARM_FEATURE_DOTPROD`
on its own, so the `armv8.2` variant still uses `sdot`.

```cmake
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)
```

### 5. Tool calling could abort the process, or return 0 tokens

Two separate defects, both in the sampler chain:

**Abort.** `llama_grammar_accept_token()` throws `std::runtime_error` when the grammar has no valid
continuation (`src/llama-grammar.cpp`). The binding called it without a `try`/`catch`, so the
exception crossed the JNI boundary → `std::terminate` → the process died. It also prefilled the
grammar differently from upstream: upstream accepts into the **grammar sampler only**, and only
when the grammar is not lazy, while the fork accepted into the whole chain. With a lazy grammar
(the tool-call grammar always carries a trigger), the trigger fired before the model produced
anything and the model's own `<tool_call>` token was rejected → empty grammar stack → throw.

**0 tokens.** `llama_sampler_sample()` already accepts the token into the chain
(`src/llama-sampler.cpp`), and the fork accepted it a **second** time. The lazy grammar received
the same `<tool_call>` twice: the first accept advanced the grammar, the second found no
continuation → `grammar.stacks` empty → throw. The tool call then returned
`输出token数: 0`. As a side effect, the penalties sampler was receiving every token **twice** in
every normal generation, duplicating repetition/frequency/presence penalties.

The fix mirrors upstream `common/sampling.cpp`:

- one single `llama_sampler_accept()` per token;
- the **grammar sampler is first in the chain**, so a valid token cannot have been discarded by
  top-k before the grammar sees it;
- the grammar sampler is kept **outside** the chain, with separate apply/accept calls;
- a `try`/`catch` around sample+accept, degrading gracefully to the text already emitted.

> **Remaining limitation (not a binding bug):** with LFM3-1.7B the *model* is not very reliable at
> producing a valid tool-call JSON. The grammar no longer rejects tokens and the crash is gone, but
> the quality of the structured output depends on the model's capability.

---

## Performance notes (measured)

Measurements on a **CUBOT KINGKONG 9 EEA** (Helio G99, Android 14), `n_ctx=4096`, `mmap=false`,
5 generations, `max_new=256`, same chat, history growing ~270 tokens per turn:

| Threads | Prefill | Decode |
|---------|---------|--------|
| 4 | **9.01 tok/s** (0.1109 s/token) | **5.60 tok/s** (0.1785 s/token) |
| 8 | 10.05 tok/s | 4.09 tok/s |

Fitted cost model: `total ≈ 40.5 + 0.1146 × prompt_tokens` (4 threads) and
`≈ 42.5 + 0.1170 × prompt_tokens` (8 threads). **Four threads wins at every prompt size**, so the
fork ships `threads=4`.

The key conclusion: **the prefill is 65–77 % of the total time** and it is compute-bound, while the
decode is memory-bandwidth-bound. That is exactly why unlocking the dot-product kernels is the
right lever — dot-product barely helps the decode, but it accelerates the dominant prefill.

> `n_ctx` is currently hardcoded to **2048** by the session factory
> (`AIServiceFactory.buildAndroidLlamaSessionConfig()`); the model configuration screen has no field
> for it. When the prompt approaches that limit, the binding truncates from the front — which in a
> Jinja template means the system prompt and the tool schemas are the first thing dropped.

---

## Building from source

The build requires the external `subpack.zip`, `jniLibs.zip` and `libs.zip` archives and an
initialised `terminal` submodule; the default local STT model is fetched and verified according to
`app/config/stt-model-assets.properties`. See the upstream
[Build Guide](https://github.com/AAswordman/Operit/blob/main/docs/doc-src/dev-core/BUILDING.md).

The llama.cpp sources are **not vendored**: they are fetched at build time by
`cmake/operit_git_source.cmake` and pinned by SHA in `llm/llama/CMakeLists.txt`:

```cmake
operit_prepare_git_source(
    OPERIT_LLAMA_CPP_SOURCE_DIR
    OPERIT_LLAMA_CPP_BINARY_DIR
    llama_cpp
    "https://github.com/ggml-org/llama.cpp.git"
    # v0.8.0 (b11960): first upstream release with LiquidAI LFM3 support
    "8642541b255bfb5dc39cd351a178be09013a55d6"
)
```

Override the pin with `-DOPERIT_LLAMA_CPP_GIT_REF=<sha|tag>`.

### Syntax-checking the native code without a full build

```bash
aarch64-linux-android26-clang++.cmd -fsyntax-only -std=c++17 \
  -DOPERIT_HAS_LLAMA_CPP=1 \
  -I <llama.cpp src> -I <llama.cpp src>/include -I <llama.cpp src>/common \
  -I <llama.cpp src>/ggml/include -I <llama.cpp src>/vendor \
  llama_jni_stub.cpp
```

---

## CI and releases

Two workflows run on GitHub Actions:

- **`Android Build`** — produces the APK artifact.
- **`Android JVM tests`** — the JVM unit-test suite (**1333 tests, 0 failures**). This workflow was
  broken since the upstream import; the fix ported the upstream test changes and the upstream
  production change to `ReleasedProviderModelKeyDecoder` (legacy pre-provider keys must not migrate
  to a non-existent provider).

### Release signing

Every release since `RealeseV5` is signed with a **stable key** stored as repository secrets
(`OPERIT_KEYSTORE_BASE64`, `OPERIT_KEYSTORE_PASSWORD`, `OPERIT_KEY_ALIAS`, `OPERIT_KEY_PASSWORD`).
The workflow decodes the keystore, injects the `RELEASE_*` properties into `local.properties` and
reports the signer digest of the produced APK. `debug` and `nightly` variants use the stable key
when it is available and fall back to the AGP debug key otherwise.

> Keep a backup of the keystore. It is the only copy of the signing key: losing it means the next
> release can no longer be installed on top of the current one.

### Publishing rules

A release is only published when **all** of these hold:

1. the JVM test workflow finished in `success`;
2. the build workflow finished in `success`;
3. the APK carries the expected signing digest;
4. the APK declares the expected `versionCode`.

> **`versionCode` must increase on every release.** Obtanium will not offer an update for an APK
> with the same `versionCode` as the installed one, even with an identical signature.

Upload the asset to **`uploads.github.com`** (not `api.github.com`, which answers `404` for asset
uploads):

```
POST https://uploads.github.com/repos/Cosio33/Operit_CosioFork/releases/{id}/assets?name=<asset>
```

---

## Known limitations

- **Tool calling with small models is unreliable.** LFM3-1.7B often produces a valid-looking but
  schema-broken tool call. The binding no longer crashes and no longer drops the tokens; the
  remaining gap is model capability.
- **`n_ctx` is fixed at 2048** and is not exposed in the UI. Long chats are truncated from the
  front, which drops the system prompt and the tool schemas first.
- **The Ubuntu/proot rootfs is regenerated** after a full reinstall (an in-place update preserves
  it).
- **`MANAGE_EXTERNAL_STORAGE` is required** for local models and is lost on uninstall, not on
  update.
- **The `dotprod` variants require ARMv8.2+**; on ARMv8.0 devices the build still works and simply
  falls back to the non-dot-product kernels.

---

## Upstream, credits and license

- **Upstream project:** [AAswordman/Operit](https://github.com/AAswordman/Operit) — the source of
  truth for the application code, the tool ecosystem and the documentation.
- **Cross-platform successor:** [Operit 2](https://github.com/AAswordman/Operit2).
- **Inference engine:** [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) v0.8.0, fetched
  at build time and not vendored in this repository.
- **LiquidAI LFM3** support in llama.cpp comes from upstream
  [ggml-org/llama.cpp#31252](https://github.com/ggml-org/llama.cpp/pull/31252).

Contributions to scripts, extensions, documentation and core features are welcome — see the
[Contribution Guide](https://github.com/AAswordman/Operit/blob/main/docs/doc-src/dev-core/CONTRIBUTING.md).

The main code in this repository is licensed under
**[GNU LGPL v3 (LGPL-3.0-only)](https://spdx.org/licenses/LGPL-3.0-only.html)**. Tools, examples,
templates and third-party dependencies may use other licenses; refer to [LICENSE](LICENSE), the
license files in their respective directories, and package metadata for their terms.

<div align="center">
  <sub>Operit AI by the Operit Team · Fork maintained by <a href="https://github.com/Cosio33">Cosio33</a></sub>
</div>
