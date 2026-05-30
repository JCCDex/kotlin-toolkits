#!/bin/sh
# 安装 git hooks：将 core.hooksPath 指向 .githooks
set -e

HOOKS_DIR=".githooks"

if [ ! -d "$HOOKS_DIR" ]; then
  echo "$HOOKS_DIR not found. Create it or check repo."
  exit 1
fi

git config core.hooksPath "$HOOKS_DIR"
echo "Git hooks installed. core.hooksPath set to $HOOKS_DIR"