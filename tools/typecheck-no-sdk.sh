#!/usr/bin/env bash
#
# 在**没有 Android SDK** 的机器上，对「模型 / 分析层 / 规则 / 渲染 / 滤镜 / AI」这一整块
# 做真正的 Kotlin 类型检查，并真的把 JVM 单元测试跑起来。
#
# ── 为什么要有这个脚本 ────────────────────────────────────────────────
#
# 本项目是纯端侧相机应用，但交付环境（CI 容器 / 开发服务器）常常没有 Android SDK。
# 于是 v1.0 一直处于"写完就推，从没编译过"的状态 —— 结果藏了两个真实的类型错误：
#
#   1. `MpImageBridge` 里写了 `mat.data` —— OpenCV Java 的 `Mat` **没有** `data` 成员
#      （只有 `dataAddr()` 原生指针）。取像素必须走 `mat.get(row, col, byte[])`。
#   2. `SharpnessAnalyzer` 里把 `mean`/`stddev` 声明成 `Mat`，但 `Core.meanStdDev`
#      的 Java 签名要求 `MatOfDouble`。
#
# 这两个都属于"编译器一秒就能发现、人眼永远发现不了"的问题。
#
# ── 做法 ──────────────────────────────────────────────────────────────
#
# 绕开 Gradle，直接用真依赖 jar + 命令行 kotlinc：
#   · MediaPipe tasks-vision / tasks-core  → 从 Google Maven 拉 AAR，取 classes.jar
#   · TensorFlow Lite（含 -api）           → 从 Maven Central 拉 AAR，取 classes.jar
#   · OpenCV 4.9                           → 从 Maven Central 拉 AAR，取 classes.jar
#   · android.jar                          → 从 Google 仓库拉 platform-34
#   · kotlinc                              → 从 GitHub 拉官方发行包
#
# 不覆盖 `ui/`（依赖 androidx.compose / CameraX，本地无法凑齐）与 `data/MediaStoreSaver`
# （依赖 androidx.exifinterface）。**所以本脚本通过 ≠ APK 能构建**，见文件末尾说明。
#
# 用法：
#   ./tools/typecheck-no-sdk.sh              # 用缓存（默认 /tmp/shotcoach-typecheck）
#   CACHE=/somewhere ./tools/typecheck-no-sdk.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${CACHE:-${TMPDIR:-/tmp}/shotcoach-typecheck}"
KOTLIN_VERSION="2.0.21"
JARS="$CACHE/jars"
OUT="$CACHE/out"
mkdir -p "$JARS" "$OUT"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

need_java() {
  if ! command -v java >/dev/null 2>&1; then
    echo "需要 JDK 17+（kotlinc 依赖 JVM）。" >&2
    exit 1
  fi
}

# ── 1. 依赖 jar ───────────────────────────────────────────────────────
fetch() { # fetch <目标文件> <url>
  [ -s "$1" ] || curl -fsSL --retry 3 -o "$1" "$2"
}

from_aar() { # from_aar <目标jar> <aar url>
  [ -s "$1" ] && return 0
  local tmp="$CACHE/tmp.aar"
  curl -fsSL --retry 3 -o "$tmp" "$2"
  ( cd "$CACHE" && unzip -oq "$tmp" classes.jar && mv -f classes.jar "$1" )
  rm -f "$tmp"
}

say "准备依赖 jar（缓存目录 $CACHE）"
need_java

MP_BASE="https://dl.google.com/dl/android/maven2/com/google/mediapipe"
from_aar "$JARS/mediapipe-tasks-vision.jar" "$MP_BASE/tasks-vision/0.10.35/tasks-vision-0.10.35.aar"
from_aar "$JARS/mediapipe-tasks-core.jar"   "$MP_BASE/tasks-core/0.10.35/tasks-core-0.10.35.aar"

MC="https://repo1.maven.org/maven2"
from_aar "$JARS/tensorflow-lite.jar"     "$MC/org/tensorflow/tensorflow-lite/2.16.1/tensorflow-lite-2.16.1.aar"
from_aar "$JARS/tensorflow-lite-api.jar" "$MC/org/tensorflow/tensorflow-lite-api/2.16.1/tensorflow-lite-api-2.16.1.aar"
from_aar "$JARS/opencv.jar"              "$MC/org/opencv/opencv/4.9.0/opencv-4.9.0.aar"
fetch    "$JARS/junit.jar"               "$MC/junit/junit/4.13.2/junit-4.13.2.jar"
fetch    "$JARS/hamcrest.jar"            "$MC/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"

# android.jar：用 platform-34（含 letterSpacing 等 API 21+ 成员）
if [ ! -s "$JARS/android.jar" ]; then
  for name in platform-34-ext7_r03.zip platform-34-ext8_r02.zip platform-34_r03.zip; do
    url="https://dl.google.com/android/repository/$name"
    if [ "$(curl -s -o /dev/null -w '%{http_code}' -I "$url")" = "200" ]; then
      curl -fsSL -o "$CACHE/p34.zip" "$url"
      ( cd "$CACHE" && unzip -oq p34.zip "android-34/android.jar" && mv -f android-34/android.jar "$JARS/android.jar" )
      rm -rf "$CACHE/p34.zip" "$CACHE/android-34"
      break
    fi
  done
fi
[ -s "$JARS/android.jar" ] || { echo "android.jar 拉取失败" >&2; exit 1; }

ls -1 "$JARS" | sed 's/^/  /'

# ── 2. kotlinc ────────────────────────────────────────────────────────
KOTLINC="$CACHE/kotlinc/bin/kotlinc"
if [ ! -x "$KOTLINC" ]; then
  say "下载 Kotlin 编译器 $KOTLIN_VERSION"
  curl -fsSL -o "$CACHE/kotlin.zip" \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  ( cd "$CACHE" && unzip -oq kotlin.zip )
  rm -f "$CACHE/kotlin.zip"
fi
"$KOTLINC" -version 2>&1 | head -1 | sed 's/^/  /'

STDLIB="$CACHE/kotlinc/lib/kotlin-stdlib.jar"
DEPS="$(ls "$JARS"/*.jar | tr '\n' ':')"

# ── 3. 主源码类型检查 ─────────────────────────────────────────────────
say "类型检查：主源码子集"
cd "$ROOT"
find app/src/main/java/com/lemon/shotcoach/model \
     app/src/main/java/com/lemon/shotcoach/analysis \
     app/src/main/java/com/lemon/shotcoach/rules \
     app/src/main/java/com/lemon/shotcoach/render \
     app/src/main/java/com/lemon/shotcoach/filter -name '*.kt' > "$CACHE/main.txt"
for extra in camera/DownScaler.kt camera/FrameThrottler.kt camera/CameraParams.kt \
             camera/FilterCatalog.kt data/CameraParamsCodec.kt; do
  echo "app/src/main/java/com/lemon/shotcoach/$extra" >> "$CACHE/main.txt"
done
echo "  文件数 $(wc -l < "$CACHE/main.txt")"

rm -rf "$OUT/main" && mkdir -p "$OUT/main"
"$KOTLINC" -nowarn -classpath "$DEPS:$STDLIB" -d "$OUT/main" @"$CACHE/main.txt" > "$CACHE/main.log" 2>&1 && rc=0 || rc=$?
if [ "$rc" -ne 0 ]; then
  echo "  ✗ 编译失败："; grep -E 'error:' "$CACHE/main.log" | head -30; exit 1
fi
echo "  ✓ 0 错误，产出 $(find "$OUT/main" -name '*.class' | wc -l) 个 class"

# ── 4. 测试编译 + 执行 ────────────────────────────────────────────────
say "编译并运行 JVM 单元测试"
find app/src/test/java -name '*.kt' > "$CACHE/test.txt"
rm -rf "$OUT/test" && mkdir -p "$OUT/test"
"$KOTLINC" -nowarn -classpath "$DEPS:$OUT/main:$STDLIB" -d "$OUT/test" @"$CACHE/test.txt" > "$CACHE/test.log" 2>&1 && rc=0 || rc=$?
if [ "$rc" -ne 0 ]; then
  echo "  ✗ 测试编译失败："; grep -E 'error:' "$CACHE/test.log" | head -30; exit 1
fi
echo "  ✓ 测试编译 0 错误"

RUN_CP="$OUT/test:$OUT/main:$DEPS:$STDLIB"
fail=0
# LutAndParamsTest 需要 OpenCV native 库（CpuLutApplier），纯 JVM 环境跑不了，只编译不运行
for cls in com.lemon.shotcoach.AiCompositionTest com.lemon.shotcoach.CompositionLogicTest; do
  printf '  · %s: ' "$cls"
  if out=$(java -cp "$RUN_CP" org.junit.runner.JUnitCore "$cls" 2>&1); then
    echo "$out" | grep -E '^OK' | head -1
  else
    echo "失败"; echo "$out" | tail -20; fail=1
  fi
done

say "结果"
if [ "$fail" -ne 0 ]; then echo "有测试失败"; exit 1; fi
cat <<'EOF'
  ✓ 类型检查与 JVM 测试全部通过。

  注意这个脚本**不能**替代真正的 APK 构建：ui/（androidx.compose + CameraX）与
  data/MediaStoreSaver（androidx.exifinterface）不在覆盖范围内。要出 APK 仍然需要
  Android SDK + Gradle，见 README「如何构建」。
EOF
