#!/bin/bash
# 发布新版本到 JitPack
# 用法: ./scripts/release.sh v0.3.1
#
# 流程: 校验 → 推送 main → 打 tag → 推送 tag → 触发并轮询 JitPack 构建
# 注意:
#   - 版本号必须是全新 tag（JitPack 缓存同名 tag 的首次 commit，复用/移动会导致构建旧版本）
#   - 前置校验确保 main 上保留了 jitpack.yml 和发布配置，避免 JitPack 构建失败
set -e

JITPACK_URL="https://jitpack.io/com/github/JCCDex/kotlin-toolkits"
VERSION="$1"

usage() {
  echo "用法: $0 <version>  例如: $0 v0.3.1"
  exit 1
}

# --- 参数校验 ---
if [ -z "$VERSION" ]; then
  usage
fi
if ! echo "$VERSION" | grep -qE '^v[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "错误: 版本号格式应为 vX.Y.Z，例如 v0.3.1"
  exit 1
fi

# --- 前置校验（今天的三个教训） ---
BRANCH=$(git branch --show-current)
if [ "$BRANCH" != "main" ]; then
  echo "错误: 必须在 main 分支（当前: $BRANCH）"
  exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
  echo "错误: 工作区有未提交的改动，请先 commit 再发布"
  exit 1
fi
if [ ! -f "jitpack.yml" ]; then
  echo "错误: 缺少 jitpack.yml —— JitPack 默认 JDK 11 跑不了 Gradle 9，需要它指定 openjdk17"
  exit 1
fi
if ! grep -q "singleVariant" build.gradle.kts; then
  echo "错误: build.gradle.kts 缺少发布配置（singleVariant）—— AGP 8+ 不会自动发布模块，JitPack 会报 No build artifacts found"
  exit 1
fi
if git rev-parse "$VERSION" >/dev/null 2>&1; then
  echo "错误: tag $VERSION 已存在。JitPack 缓存同名 tag 的首次 commit，请换一个全新版本号"
  exit 1
fi

# --- 推送 main（确保基于最新代码） ---
echo ">>> 推送 main ..."
git push origin main

# --- 打 tag 并推送 ---
echo ">>> 打 tag $VERSION 并推送 ..."
git tag "$VERSION"
git push origin "$VERSION"
echo ">>> 已推送 tag $VERSION"

# --- 触发 JitPack 构建（首次访问会自动触发） ---
echo ">>> 触发 JitPack 构建 ..."
curl -s -o /dev/null --max-time 30 "$JITPACK_URL/$VERSION/" || true

# --- 轮询构建结果 ---
echo ">>> 等待构建完成（首次构建约 2-5 分钟，含 JitPack 同步 tag 的时间）..."
BUILD_RESULT=""
for i in $(seq 1 60); do
  LOG=$(curl -s --max-time 30 "$JITPACK_URL/$VERSION/build.log" 2>/dev/null || true)
  if [ -z "$LOG" ]; then
    echo "    网络请求失败，重试..."
  elif echo "$LOG" | grep -q "Tag or commit '$VERSION' not found"; then
    echo "    等待 JitPack 同步 tag ..."
  elif echo "$LOG" | grep -q "No build artifacts found"; then
    BUILD_RESULT="FAIL_NO_ARTIFACTS"
    break
  elif echo "$LOG" | grep -q "Build tool exit code: 0"; then
    BUILD_RESULT="SUCCESS"
    break
  elif echo "$LOG" | grep -qE "Build tool exit code: [1-9]|BUILD FAILED"; then
    BUILD_RESULT="FAIL"
    break
  fi
  sleep 15
done

echo ""
case "$BUILD_RESULT" in
  SUCCESS)
    echo "✅ 构建成功！$VERSION 已可被其他项目引用:"
    echo "    整个库:  implementation(\"com.github.JCCDex:kotlin-toolkits:$VERSION\")"
    echo "    单模块:  implementation(\"com.github.JCCDex.kotlin-toolkits:did:$VERSION\")"
    ;;
  FAIL_NO_ARTIFACTS)
    echo "❌ 构建成功但未发布 artifacts（No build artifacts found）"
    echo "   检查 build.gradle.kts 是否包含 singleVariant 发布配置"
    echo "   日志: $JITPACK_URL/$VERSION/build.log"
    exit 1
    ;;
  FAIL)
    echo "❌ 构建失败！"
    echo "   日志: $JITPACK_URL/$VERSION/build.log"
    exit 1
    ;;
  *)
    echo "⏳ 超过 15 分钟仍未出结果，请手动查看:"
    echo "   $JITPACK_URL/$VERSION/build.log"
    exit 1
    ;;
esac
