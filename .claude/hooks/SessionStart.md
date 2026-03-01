---
type: SessionStart
---

# Session Start Hook

Initializes the development environment for building the Android browser application.

## What it does

- Configures Gradle proxy settings from environment variables
- Sets up proxy authentication for HTTPS connections
- Downloads and configures the Android SDK
- Pre-downloads Gradle distribution to avoid network issues

## Script location

`./.claude/hooks/session-start.sh`

This hook runs automatically when starting a Claude Code session in a remote environment (`CLAUDE_CODE_REMOTE` is set).
