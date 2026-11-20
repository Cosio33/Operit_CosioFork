<h1 align="center">
  <img src="app/src/main/assets/logo.svg" width="48" height="48" align="absmiddle" alt=""> Operit AI — Cosio Fork
</h1>

<div align="center">
  <a href="README.md">English</a> | <a href="README.es.md">Español</a> | <a href="README.zh-CN.md">中文</a>
  <br>
  <img src="https://img.shields.io/github/last-commit/Cosio33/Operit_CosioFork" alt="Último commit">
  <img src="https://img.shields.io/badge/Plataforma-Android_8.0%2B-brightgreen.svg" alt="Plataforma">
  <img src="https://img.shields.io/github/v/release/Cosio33/Operit_CosioFork" alt="Última release">
  <img src="https://img.shields.io/badge/licencia-LGPL--3.0-blue.svg" alt="Licencia">
</div>

<div align="center">
  <img src="docs/assets/readme/operit-ai-banner-en.webp" width="100%" alt="Operit AI">
</div>

## Sobre este repositorio

Este es el **fork de [AAswordman/Operit](https://github.com/AAswordman/Operit) de Cosio33**, una
plataforma de agente de IA open source para Android. El proyecto original es la base de todo lo que
hay aquí — la app, el ecosistema de herramientas, el marketplace, el sistema de memoria, los
workspaces y el terminal — y sigue siendo el upstream de referencia del código base.

El fork existe por un motivo: **que el motor llama.cpp integrado sea realmente usable en hardware
Android real.** Eso significa compilar el motor nativo optimizado en lugar de sin optimizar, hacer
que los prompts largos sobrevivan al prefill, desbloquear los kernels ARM de dot-product, arreglar
los crashes que mataban el proceso, y publicar releases que se puedan **instalar encima de la
anterior** sin perder tu configuración, tus chats ni tus modelos.

> **Este README está escrito para el fork, no es una copia del original.** Documenta qué cambió
> este fork, por qué, y cómo compilar, instalar y verificarlo. Para el catálogo completo de
> funciones de Operit AI, consulta el [README del upstream](https://github.com/AAswordman/Operit#readme)
> y la [guía de usuario](https://operit.app/).

### Qué cambia este fork, de un vistazo

| Área | Upstream | Este fork |
|------|----------|------------|
| Versión de llama.cpp | pin antiguo | **v0.8.0** (`b11960`) — primera release con soporte de **LiquidAI LFM3** |
| Tipo de build nativo | `Debug` en las variantes debug → kernels a **-O0** | forzado a **`Release`** → kernels a **-O2** |
| Prompts grandes | aborta el proceso al pasar de `n_batch` | prefill **troceado** en `min(n_batch, n_ubatch)` |
| Dot-product ARM | inalcanzable (una sola lib `arm64`) | **7 variantes de CPU** cargadas en runtime (`GGML_BACKEND_DL`) |
| Repack de pesos | `GGML_CPU_REPACK=ON` → **SIGSEGV** en arm64 | **desactivado** (`GGML_CPU_REPACK OFF`) |
| Tool calling | la gramática podía abortar el proceso / devolver **0 tokens** | try/catch, gramática primera en la chain, **un solo accept** |
| Firma de los releases | keystore debug efímero → cada actualización obligaba a reinstalar | **keystore estable** → las actualizaciones se instalan **encima** |
| Monitor de rendimiento | solo en chino | **traducido al español** (22 cadenas) |
| Workflow de tests JVM | roto desde la importación del upstream | **1333 tests, 0 fallos** |

---

## Instalación

### Requisitos

- **Android 8.0 (API 26) o superior**, solo **ARM64 (`arm64-v8a`)**
- ~450 MB para el APK, más el tamaño de los modelos que descargues
- Para **modelos locales**: permiso de «Acceso a todos los archivos» (`MANAGE_EXTERNAL_STORAGE`)

### Opción A — Obtanium (recomendado, admite actualizaciones encima)

1. Abre **Obtanium** → *Add app* → fuente **GitHub Releases**
2. Repositorio: `Cosio33/Operit_CosioFork`, tag: `RealeseV6` (o la regex `Realese.*` para seguir
   todas las releases)
3. Instala. Las siguientes releases aparecen como **actualizaciones** y se instalan **encima** de la
   versión actual, conservando configuración, chats y modelos.

### Opción B — APK directo

Descarga [`Operit-llamav0.8.0-v6.apk`](https://github.com/Cosio33/Operit_CosioFork/releases/download/RealeseV6/Operit-llamav0.8.0-v6.apk)
desde la [página de releases](https://github.com/Cosio33/Operit_CosioFork/releases) e instálalo.

> **Aviso de seguridad:** instala solo paquetes de la
> [página de releases oficial](https://github.com/Cosio33/Operit_CosioFork/releases) o del
> [sitio de Operit](https://operit.app/). Los paquetes de origen desconocido pueden estar
> modificados y poner en riesgo tus datos o tu dispositivo.

> **Actualizar encima:** todas las releases desde `RealeseV5` se firman con la misma clave, así que
> `adb install -r` y las actualizaciones de Obtanium funcionan sin desinstalar. Verificado en un
> CUBOT KINGKONG 9 EEA (Helio G99, Android 14): `firstInstallTime` sin cambios, los 12 ficheros
> `files/datastore/*.preferences_pb` byte a byte idénticos (sha256), `objectbox/data.mdb` sin
> cambios y `MANAGE_EXTERNAL_STORAGE` sigue concedido. La única versión que hubo que desinstalar
> antes fue `RealeseV5`, porque introduce la clave estable.

---

## Inferencia local: llama.cpp en Android

Este fork tiene como objetivo la familia **LiquidAI LFM3** (LFM3-1.7B, LFM3-Omni-2B, …) ejecutándose
completamente en el dispositivo mediante un binding JNI de llama.cpp. Para que eso funcionara en
un móvil ARM64 de gama media hubo que resolver cinco problemas poco evidentes.

### 1. El motor nativo se compilaba sin optimizaciones

El plugin de Gradle para Android fija `CMAKE_BUILD_TYPE=Debug` en las variantes `debug` y `clone`,
y los flags `Debug` de CMake son solo `-g`: **llama.cpp y ggml se compilaban a `-O0`** en todos los
APK publicados. El síntoma es invisible: la app funciona, simplemente es varias veces más lenta de
lo que debería. Medido con `llvm-objdump -d`, la multiplicación de matrices cuantizada
`ggml_vec_dot_q4_0_q8_0` ocupaba **2592 bytes a -O0** frente a **696 bytes a -O2**.

```cmake
# llm/llama/CMakeLists.txt
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()
```

> La ausencia de `.debug_info` en el APK **no** demuestra que el build esté optimizado: AGP
> elimina los símbolos nativos al empaquetar. Compara el tamaño de las funciones.

### 2. Los prompts mayores que `n_batch` mataban el proceso

El binding metía todo el prompt en un único `llama_batch_get_one()`. Con `n_batch = 512` y un
historial de conversación que no deja de crecer, el prompt se pasaba del límite y
`GGML_ASSERT(n_tokens_all <= cparams.n_batch)` abortaba el **proceso** — `GGML_ASSERT` es
incondicional, no se desactiva en builds de release. La app se reiniciaba, recargaba el mismo
historial y abortaba otra vez: un crash loop instantáneo.

La solución es alimentar el prefill en trozos de `min(n_batch, n_ubatch)`, tanto en el decoder
como en el encoder (el encoder tiene su propio `GGML_ASSERT(n_ubatch >= n_tokens)`):

```cpp
// llm/llama/src/main/cpp/llama_jni_stub.cpp
uint32_t limit = llama_n_batch(ctx);
const uint32_t nUbatch = llama_n_ubatch(ctx);
```

Se conserva la semántica de upstream: con `pos == nullptr` las posiciones se derivan del estado de
la caché KV, y con `logits == nullptr` solo el último token de cada lote es salida — trocear es
equivalente a un único lote en modelos causales y el sampling no cambia.

La línea de log que demuestra que funciona:

```
Prefill decode start: prompt_tokens=587 n_ctx=2048 n_batch=512 n_ubatch=512 chunk_limit=512 chunks=2
```

### 3. Los kernels ARM de dot-product eran inalcanzables — y activarlos a lo bruto es mortal

ggml compila los kernels de dot-product int8 detrás de la macro de **tiempo de compilación**
`__ARM_FEATURE_DOTPROD` (`ggml/src/ggml-cpu/arch/arm/quants.c`), sin fallback en runtime. Una única
biblioteca compilada con `+dotprod` ejecutaría la instrucción `sdot` en todos los dispositivos y
moriría con **SIGILL** en las CPUs que no la implementan (ARMv8.0 / A53).

El camino seguro es el que usa upstream: publicar una biblioteca compartida por conjunto de
características y elegir en runtime la mejor soportada, usando la función de puntuación de
`ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp`. Eso exige que el backend sea un módulo cargado
dinámicamente.

```cmake
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
```

llama.cpp v0.8.0 ya incluye las variantes de Android, así que no hace falta ningún parche sobre
upstream: `android_armv8.0_1`, `android_armv8.2_1` *(DOTPROD)*, `android_armv8.2_2`,
`android_armv8.6_1`, `android_armv9.0_1`, `android_armv9.2_1`, `android_armv9.2_2`.

**Trampa específica de Android:** `ggml_backend_load_all()` busca en `get_executable_path()`, que en
Android es `/proc/self/exe` → `/system/bin/app_process64`, no el directorio donde están las `.so`.
Por eso las variantes se cargan explícitamente con la ruta real, obtenida con `dladdr()`:

```cpp
ggml_backend_load_all_from_path(dir.c_str());
```

Como el motor deja de ser un único objeto, **cada** biblioteca compartida y **cada** módulo de
backend cargado dinámicamente necesita alineación de página de 16 KB (requisito de Android 15+):

```cmake
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
```

### 4. El repack de pesos provocaba un SIGSEGV en arm64

`GGML_CPU_REPACK` viene `ON` por defecto en ggml y reordena los pesos cuantizados al cargarlos en
el layout «8x4» que usan los kernels estilo llamafile (`ggml_gemv_q4_K_8x4_q8_K` y compañía).
Esa ruta **no es compatible con el backend multi-variante de CPU**
([ggml-org/llama.cpp#30583](https://github.com/ggml-org/llama.cpp/issues/30583)). En este
dispositivo provocaba un **SIGSEGV** dentro de la variante con dot-product:

```
#00 ggml_gemv_q4_K_8x4_q8_K
#01 ggml::cpu::repack::tensor_traits<block_q4_K, 4, 8, (ggml_type)15>::forward_mul_mat_one_chunk
...
#07 libomp.so (__kmp_invoke_microtask)
```

es decir, el kernel de repack lee más allá del final del buffer de pesos reordenado. Lo mismo ocurre
con el kernel Q6_K (`ggml_gemm_q6_K_8x4_q8_K`).

Desactivarlo no cuesta nada: la ruta normal de matrices cuantizadas está condicionada por
`__ARM_FEATURE_DOTPROD` por su cuenta, así que la variante `armv8.2` sigue usando `sdot`.

```cmake
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)
```

### 5. El tool calling podía abortar el proceso o devolver 0 tokens

Dos defectos distintos, ambos en la chain de samplers:

**Abort.** `llama_grammar_accept_token()` lanza `std::runtime_error` cuando la gramática no tiene
ninguna continuación válida (`src/llama-grammar.cpp`). El binding la llamaba sin `try`/`catch`, así
que la excepción cruzaba la frontera JNI → `std::terminate` → proceso muerto. Además, el prefill de
la gramática divergía de upstream: upstream acepta **solo** en el sampler de gramática, y solo si
la gramática no es lazy, mientras que el fork aceptaba en toda la chain. Con una gramática lazy
(la de tool-call siempre lleva trigger), el trigger se disparaba antes de que el modelo generara
nada y el propio token `<tool_call>` del modelo era rechazado → stack de gramática vacío →
throw.

**0 tokens.** `llama_sampler_sample()` ya acepta el token en la chain
(`src/llama-sampler.cpp`), y el fork lo aceptaba **una segunda vez**. La gramática lazy recibía dos
veces el mismo `<tool_call>`: la primera aceptaba y avanzaba la gramática, la segunda no encontraba
continuación → `grammar.stacks` vacío → throw. El tool call acababa devolviendo
`输出token数: 0`. Como efecto colateral, el sampler de penalizaciones recibía cada token **dos
veces** en toda generación normal, duplicando las penalizaciones de repetición, frecuencia y
presencia.

El fix replica el `common/sampling.cpp` de upstream:

- un único `llama_sampler_accept()` por token;
- el **sampler de gramática va primero en la chain**, para que un token válido no pueda haber sido
  descartado antes por el top-k;
- el sampler de gramática se mantiene **fuera** de la chain, con apply y accept separados;
- `try`/`catch` alrededor de sample+accept, degradando con elegancia al texto ya emitido.

> **Limitación restante (no es un bug del binding):** con LFM3-1.7B el *modelo* no es muy fiable
> generando un JSON de tool call válido. La gramática ya no rechaza tokens y el crash ha
> desaparecido, pero la calidad del resultado estructurado depende de la capacidad del modelo.

---

## Notas de rendimiento (medidas)

Mediciones en un **CUBOT KINGKONG 9 EEA** (Helio G99, Android 14), `n_ctx=4096`, `mmap=false`,
5 generaciones, `max_new=256`, mismo chat, con el historial creciendo ~270 tokens por turno:

| Hilos | Prefill | Decode |
|-------|---------|--------|
| 4 | **9.01 tok/s** (0.1109 s/token) | **5.60 tok/s** (0.1785 s/token) |
| 8 | 10.05 tok/s | 4.09 tok/s |

Modelo de coste ajustado: `total ≈ 40.5 + 0.1146 × prompt_tokens` (4 hilos) y
`≈ 42.5 + 0.1170 × prompt_tokens` (8 hilos). **Cuatro hilos gana en todos los tamaños de prompt**,
por eso el fork usa `threads=4`.

La conclusión clave: **el prefill es el 65–77 % del tiempo total** y es *compute-bound*, mientras
que el decode es *memory-bandwidth bound*. Por eso la palanca correcta es desbloquear los kernels
de dot-product: su aporte al decode es insignificante, pero aceleran el prefill dominante.

> `n_ctx` está fijado a **2048** por la factory de sesión
> (`AIServiceFactory.buildAndroidLlamaSessionConfig()`); la pantalla de configuración de modelo no
> tiene campo para él. Cuando el prompt se acerca a ese límite, el binding trunca por el frente, y
> en una plantilla Jinja lo primero que se cae son el system prompt y los schemas de las
> herramientas.

---

## Compilar desde el código

La compilación requiere los archivos externos `subpack.zip`, `jniLibs.zip` y `libs.zip` y el
submódulo `terminal` inicializado; el modelo STT local por defecto se descarga y verifica según
`app/config/stt-model-assets.properties`. Consulta la
[guía de build del upstream](https://github.com/AAswordman/Operit/blob/main/docs/doc-src/dev-core/BUILDING.md).

Las fuentes de llama.cpp **no están vendorizadas**: se descargan en tiempo de build mediante
`cmake/operit_git_source.cmake` y se fijan por SHA en `llm/llama/CMakeLists.txt`:

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

Se puede sobrescribir el pin con `-DOPERIT_LLAMA_CPP_GIT_REF=<sha|tag>`.

### Verificar la sintaxis del código nativo sin un build completo

```bash
aarch64-linux-android26-clang++.cmd -fsyntax-only -std=c++17 \
  -DOPERIT_HAS_LLAMA_CPP=1 \
  -I <llama.cpp src> -I <llama.cpp src>/include -I <llama.cpp src>/common \
  -I <llama.cpp src>/ggml/include -I <llama.cpp src>/vendor \
  llama_jni_stub.cpp
```

---

## CI y releases

En GitHub Actions corren dos workflows:

- **`Android Build`** — produce el artefacto APK.
- **`Android JVM tests`** — la suite de tests unitarios JVM (**1333 tests, 0 fallos**). Este workflow
  estaba roto desde la importación del upstream; el arreglo portó los cambios de test del upstream y
  el cambio de producción en `ReleasedProviderModelKeyDecoder` (las claves legacy anteriores a los
  proveedores no deben migrar a un proveedor inexistente).

### Firma de los releases

Todas las releases desde `RealeseV5` se firman con una **clave estable** guardada como secretos del
repositorio (`OPERIT_KEYSTORE_BASE64`, `OPERIT_KEYSTORE_PASSWORD`, `OPERIT_KEY_ALIAS`,
`OPERIT_KEY_PASSWORD`). El workflow decodifica el keystore, inyecta las propiedades `RELEASE_*` en
`local.properties` e imprime el digest de firma del APK producido. Las variantes `debug` y `nightly`
usan la clave estable cuando está disponible y recurren a la clave debug de AGP si no.

> Guarda una copia de seguridad del keystore. Es la única copia de la clave de firma: si se pierde,
> la siguiente release ya no se podrá instalar encima de la actual.

### Reglas de publicación

Una release solo se publica cuando se cumplen **todas** estas condiciones:

1. el workflow de tests JVM terminó en `success`;
2. el workflow de build terminó en `success`;
3. el APK lleva el digest de firma esperado;
4. el APK declara el `versionCode` esperado.

> **El `versionCode` tiene que subir en cada release.** Obtanium no ofrece actualización para un
> APK con el mismo `versionCode` que el ya instalado, aunque la firma sea idéntica.

Sube el asset a **`uploads.github.com`** (no a `api.github.com`, que responde `404` a las subidas
de assets):

```
POST https://uploads.github.com/repos/Cosio33/Operit_CosioFork/releases/{id}/assets?name=<asset>
```

---

## Limitaciones conocidas

- **El tool calling con modelos pequeños es poco fiable.** LFM3-1.7B a menudo genera un tool call
  con aspecto válido pero fuera de esquema. El binding ya no se cuelga ni pierde los tokens; el
  hueco que queda es capacidad del modelo.
- **`n_ctx` está fijo en 2048** y no se expone en la UI. En chats largos se trunca por el frente,
  lo que descarta primero el system prompt y los schemas de las herramientas.
- **El rootfs de Ubuntu/proot se regenera** tras una reinstalación completa (una actualización
  encima lo conserva).
- **`MANAGE_EXTERNAL_STORAGE` es necesario** para los modelos locales y se pierde al desinstalar,
  no al actualizar.
- **Las variantes con dotprod requieren ARMv8.2+**; en dispositivos ARMv8.0 el build sigue
  funcionando y simplemente usa los kernels sin dot-product.

---

## Upstream, créditos y licencia

- **Proyecto original:** [AAswordman/Operit](https://github.com/AAswordman/Operit) — fuente de la
  verdad para el código de la app, el ecosistema de herramientas y la documentación.
- **Sucesor multiplataforma:** [Operit 2](https://github.com/AAswordman/Operit2).
- **Motor de inferencia:** [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) v0.8.0,
  descargado en tiempo de build y no vendorizado en este repositorio.
- El soporte de **LiquidAI LFM3** en llama.cpp viene del upstream
  [ggml-org/llama.cpp#31252](https://github.com/ggml-org/llama.cpp/pull/31252).

Las contribuciones a scripts, extensiones, documentación y funcionalidades núcleo son bienvenidas —
consulta la
[guía de contribución](https://github.com/AAswordman/Operit/blob/main/docs/doc-src/dev-core/CONTRIBUTING.md).

El código principal de este repositorio está bajo
**[GNU LGPL v3 (LGPL-3.0-only)](https://spdx.org/licenses/LGPL-3.0-only.html)**. Las
herramientas, ejemplos, plantillas y dependencias de terceros pueden usar otras licencias;
consulta [LICENSE](LICENSE), las licencias de sus directorios y los metadatos de los paquetes.

<div align="center">
  <sub>Operit AI por el equipo de Operit · Fork mantenido por <a href="https://github.com/Cosio33">Cosio33</a></sub>
</div>
