#!/usr/bin/env bash
#
# 拉取端侧 AI 模型权重到 app/src/main/assets/
#
# 为什么用脚本而不是 Gradle 任务：**构建不应该依赖网络**。模型是一次性的第三方权重，
# 下好之后跟着 APK 走，之后完全离线。也正是因为如此，仓库里不带这些二进制
# （见 .gitignore），clone 下来先跑一次这个脚本。
#
# 用法：
#   ./tools/fetch-models.sh            # 三件核心模型（主体 / 人脸 / 美感）
#   ./tools/fetch-models.sh --all      # 再加技术质量分（多 6.4MB，可选）
#   ./tools/fetch-models.sh --check    # 只校验本地文件大小，不下载
#
# 注意：**模型缺失不会导致 App 崩溃**。AiModels.has() 探测不到就静默退回传统 CV，
# 行为与 v1.0 逐位一致。所以这个脚本是可选的（只是没有它就享受不到 AI 能力）。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets"

# 格式：文件名|下载地址|字节数|来源与许可
# 字节数写死是有意的：下载完立刻校验，避免半截文件/CDN 错误页混进 assets 还以为成功了。
CORE=(
  "efficientdet_lite0.tflite|https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite|4602795|MediaPipe EfficientDet-Lite0 int8 (COCO 80 类) · Apache-2.0"
  "blaze_face_short_range.tflite|https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite|229746|MediaPipe BlazeFace short-range float16 · Apache-2.0"
  "nima_aesthetic_fp16.tflite|https://huggingface.co/litert-community/NIMA-LiteRT/resolve/main/nima_aesthetic_fp16.tflite|6448632|NIMA MobileNet (AVA 美学) · Apache-2.0"
)

EXTRA=(
  "nima_technical_fp16.tflite|https://huggingface.co/litert-community/NIMA-LiteRT/resolve/main/nima_technical_fp16.tflite|6449632|NIMA MobileNet (TID2013 技术质量) · Apache-2.0"
)

mode="core"
for arg in "$@"; do
  case "$arg" in
    --all)   mode="all" ;;
    --check) mode="check" ;;
    -h|--help)
      sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "未知参数：$arg（用 --help 看用法）" >&2
      exit 2
      ;;
  esac
done

size_of() { stat -c%s "$1" 2>/dev/null || echo 0; }

items=("${CORE[@]}")
if [ "$mode" = "all" ]; then
  items+=("${EXTRA[@]}")
fi

mkdir -p "$DEST"
fail=0

echo "目标目录：$DEST"
echo

for item in "${items[@]}"; do
  IFS='|' read -r name url bytes note <<< "$item"
  dest="$DEST/$name"

  if [ "$mode" = "check" ]; then
    have="$(size_of "$dest")"
    if [ "$have" = "$bytes" ]; then
      printf '  OK   %-32s %s bytes\n' "$name" "$have"
    elif [ "$have" = "0" ]; then
      printf '  MISS %-32s 未下载\n' "$name"
      fail=1
    else
      printf '  BAD  %-32s 期望 %s 实际 %s\n' "$name" "$bytes" "$have"
      fail=1
    fi
    continue
  fi

  if [ -f "$dest" ] && [ "$(size_of "$dest")" = "$bytes" ]; then
    printf '  ==   %-32s 已存在且大小正确，跳过\n' "$name"
    continue
  fi

  printf '  ->   %-32s %s\n       %s\n' "$name" "$note" "$url"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$dest.part" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$dest.part" "$url"
  else
    echo "需要 curl 或 wget" >&2
    exit 1
  fi
  mv -f "$dest.part" "$dest"

  have="$(size_of "$dest")"
  if [ "$have" = "$bytes" ]; then
    printf '  OK   %-32s %s bytes\n' "$name" "$have"
  else
    printf '  FAIL %-32s 大小不符：期望 %s 实际 %s\n' "$name" "$bytes" "$have" >&2
    fail=1
  fi
done

echo
if [ "$fail" -ne 0 ]; then
  echo "有模型未就位（见上面 MISS/BAD/FAIL）。App 仍可构建运行，但对应 AI 通道会退回传统 CV。" >&2
  exit 1
fi

total=0
for f in "$DEST"/*.tflite; do
  if [ -e "$f" ]; then
    total=$(( total + $(size_of "$f") ))
  fi
done
echo "assets 内模型合计：$(( total / 1024 / 1024 )) MB"
echo
echo "先自测一遍逻辑（不需要 Android SDK）：./gradlew :app:testDebugUnitTest"
echo "再构建：                          ./gradlew :app:assembleDebug"
