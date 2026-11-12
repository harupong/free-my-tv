# Free My TV: guide for AI assistants

The full guide is [CLAUDE.md](CLAUDE.md): read it first and follow it (it is written for any assistant, not only
Claude Code). Most people ask in plain words, like "Install Free My TV on my TV at 192.168.1.50. I live in Lisbon.",
and don't know what a terminal is: explain each step simply, and ask before anything that changes their TV or computer.

`config.properties` is git-ignored, so each worktree has its own copy. When a worktree adds a setting there (such as
`jellyfin_server`), add the same line to the main checkout's `config.properties` too: otherwise a build from `main`
leaves the feature off, even after the branch is merged.
