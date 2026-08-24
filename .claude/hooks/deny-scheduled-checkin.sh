#!/bin/bash
set -euo pipefail

# PR 監視のための定期セルフチェックイン（スケジュール実行）をブロックする。
#
# Claude Code Web（リモート実行環境）でのみ拒否する。ローカル CLI や IDE 拡張では
# ユーザーが対話的に使うことがあるため制限しない。
# 判定条件は .claude/hooks/session-start.sh と揃えている。
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cat <<'JSON'
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"このリポジトリではリモート実行環境での定期セルフチェックインを禁止しています。CI の完了やレビューコメントは PR のイベント通知で届くため、通知を受け取ったときにだけ対応してください。"}}
JSON
