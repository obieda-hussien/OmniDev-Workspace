# Tool and skill mentions

Type `@` in the composer, search the enabled capabilities and tap a suggestion.
You can combine up to 12 tools and 4 installed skills in one message:

```text
@tool:github_manager @skill:omnidev-quality-gate Review this repository's failed build.
```

- Tool mentions prioritize tools for this turn. Every mentioned schema is loaded in the first model request and remains available as supporting tools are retrieved. The agent can discover and load additional permitted tools when a concrete task step needs them, including connected MCP tools and learned recipe dependencies. Mentions do not impose an exclusive allowlist; broad exploration and redundant retries are discouraged.
- Skill mentions preload the selected instructions. They specialize the model but do not select tools or override access settings. Large combined skill bodies are rejected before completion instead of silently truncating instructions.
- Without mentions, the existing automatic tool and skill selection continues.
- Disabled tools, flavor restrictions, skill availability, file scope and confirmation gates still apply. Unknown or unavailable selections produce an error before model completion.
- Chat suggests its research/media tools. Agent and Auto can suggest execution tools and connected MCP tools. Team workers inherit the same explicit focus. The floating assistant uses the shared composer.
- Selection stays in the original message text, so editing, history reload and regeneration preserve it. A subsequent message starts with its own selection. Ordinary handles, emails, URLs and fenced/inline code do not select capabilities.
- Tap a selected chip to remove it. Live text follow-ups remain available; changing capability selection requires stopping the active run and sending a new message.

## CI dependency recovery

The reported API 30 job failed while resolving four existing Maven runtime jars, before UI tests started. CI now retries recognized dependency download failures up to three attempts, refreshing Gradle dependency metadata on retry. Compiler and test failures retain their original exit status and are not retried. The wrapper does not delete caches, change dependency versions, add mirrors, or skip checks.

Mentioned skill bodies have their own bounded prompt section in Agent/Team workers. Profile context cannot consume that budget; Team planning and final synthesis also keep selected skill guidance separate from profile preferences.
