# Built-in tool and skill catalog

| Metric | Count |
| --- | ---: |
| Unique declared local tool names | 150 |
| Catalog names excluding hidden legacy aliases | 148 |
| Bundled skills | 6 |

## Count scope

This source inventory follows the providers referenced by `CompositeToolManager.buildAllToolDefinitions()`. It counts unique `ToolDefinition` names, not tool actions, source files or parameter definitions. The catalog includes optional Android/integration providers and God Mode definitions. A running session can expose fewer tools because of build flavor, initialized components, chat capability settings and God Mode. Android permissions and service credentials can further limit execution.

Hidden registered legacy aliases: `direct_terminal`, `termux_bridge`.

MCP tools are discovered from connected servers at runtime and are excluded from these counts. Runtime helpers such as `discover_tools` and `request_execution_mode` are also excluded. The five default MCP services are server configurations, not five tool definitions. See [default MCP services](MCP_DEFAULT_SERVICES.md).

Reproduce from the checked-out source:

```sh
python3 scripts/tool_catalog.py
python3 scripts/tool_catalog.py --format markdown
```

## Bundled skills

Only shipped `app/src/main/assets/agent-skills/*/SKILL.md` packages count here; user-installed/imported skills are additional.

- `omnidev-android-engineering`
- `omnidev-browser-research`
- `omnidev-core-operator`
- `omnidev-orchestrating-agents`
- `omnidev-quality-gate`
- `omnidev-security-research`

## Local tool definitions

| Name | Source |
| --- | --- |
| `agent_runtime` | `app/src/main/java/com/omnidev/workspace/data/tools/AgentRuntimeTool.kt` |
| `analyze_anr_trace` | `app/src/main/java/com/omnidev/workspace/data/tools/GodEyeProfilerTool.kt` |
| `analyze_logcat` | `app/src/main/java/com/omnidev/workspace/data/tools/LogcatAnalyzerTool.kt` |
| `android_security_research` | `app/src/main/java/com/omnidev/workspace/data/tools/AndroidSecurityResearchTool.kt` |
| `app_manager_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/SystemAssistantTools.kt` |
| `append_to_file` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `archive_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `autofill_assist` | `app/src/main/java/com/omnidev/workspace/data/tools/AutofillAssistTool.kt` |
| `brain_recall_episodes` | `app/src/main/java/com/omnidev/workspace/data/tools/AgentBrainTools.kt` |
| `brain_recall_lessons` | `app/src/main/java/com/omnidev/workspace/data/tools/AgentBrainTools.kt` |
| `brain_record_episode` | `app/src/main/java/com/omnidev/workspace/data/tools/AgentBrainTools.kt` |
| `build_diagnose` | `app/src/main/java/com/omnidev/workspace/data/tools/BuildDoctorTools.kt` |
| `build_record_fail` | `app/src/main/java/com/omnidev/workspace/data/tools/BuildDoctorTools.kt` |
| `build_record_fix` | `app/src/main/java/com/omnidev/workspace/data/tools/BuildDoctorTools.kt` |
| `build_top_solutions` | `app/src/main/java/com/omnidev/workspace/data/tools/BuildDoctorTools.kt` |
| `call_log_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedSystemTools.kt` |
| `causal_plan_analyze` | `app/src/main/java/com/omnidev/workspace/data/tools/CausalChainPlannerTool.kt` |
| `causal_plan_clear` | `app/src/main/java/com/omnidev/workspace/data/tools/CausalChainPlannerTool.kt` |
| `causal_plan_simulate` | `app/src/main/java/com/omnidev/workspace/data/tools/CausalChainPlannerTool.kt` |
| `causal_plan_what_if` | `app/src/main/java/com/omnidev/workspace/data/tools/CausalChainPlannerTool.kt` |
| `check_permission` | `app/src/main/java/com/omnidev/workspace/data/tools/PermissionManagerTool.kt` |
| `clear_file` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `clipboard` | `app/src/main/java/com/omnidev/workspace/data/tools/ClipboardTool.kt` |
| `communicate_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/SystemAssistantTools.kt` |
| `copy_move` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `create_file` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `create_notion_page` | `app/src/main/java/com/omnidev/workspace/data/tools/NotionPublisherTool.kt` |
| `delete_file` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `delete_lines` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `delete_memory` | `app/src/main/java/com/omnidev/workspace/data/tools/MemoryManager.kt` |
| `delete_text` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `device_admin` | `app/src/main/java/com/omnidev/workspace/data/tools/DeviceAdminTool.kt` |
| `diff_files` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `direct_terminal` | `app/src/main/java/com/omnidev/workspace/data/tools/DirectTerminalTool.kt` |
| `discord_bot` | `app/src/main/java/com/omnidev/workspace/data/tools/DiscordBotTool.kt` |
| `disk_usage` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `enhanced_attack_surface` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `enhanced_cached_analysis` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `enhanced_intent_resolver` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `enhanced_manifest_analyzer` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `enhanced_manifest_to_html` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `enhanced_network_security` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `eval_expression` | `app/src/main/java/com/omnidev/workspace/data/tools/ScriptRunnerTool.kt` |
| `execution_diagnostics` | `app/src/main/java/com/omnidev/workspace/data/tools/OmniExecutionDiagnostics.kt` |
| `fetch_page` | `app/src/main/java/com/omnidev/workspace/data/tools/research/PageFetchTool.kt` |
| `file_info` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `file_permissions` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `find_files` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `get_current_location` | `app/src/main/java/com/omnidev/workspace/data/tools/SystemAssistantTools.kt` |
| `get_device_info` | `app/src/main/java/com/omnidev/workspace/data/tools/SystemAssistantTools.kt` |
| `get_trust_profile` | `app/src/main/java/com/omnidev/workspace/data/tools/ProgressiveTrustTool.kt` |
| `git_manager` | `app/src/main/java/com/omnidev/workspace/data/tools/GitManagerTool.kt` |
| `github_manager` | `app/src/main/java/com/omnidev/workspace/data/tools/GitHubManagerTool.kt` |
| `god_copy_file` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `god_delete_file` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `god_list_directory` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `god_read_file` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `god_stat_file` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `god_write_file` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `grep_search` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `hardware_toggle_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/SystemAssistantTools.kt` |
| `headless_browser` | `app/src/main/java/com/omnidev/workspace/data/tools/HeadlessBrowserManager.kt` |
| `hex_dump` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `ime_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `insert_lines` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `install_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/ToolDownloaderEngine.kt` |
| `intelligent_automation` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `learned_routine` | `app/src/main/java/com/omnidev/workspace/data/routines/LearnedRoutineTool.kt` |
| `list_chat_sessions` | `app/src/main/java/com/omnidev/workspace/data/tools/research/MessageSearchTool.kt` |
| `list_earned_capabilities` | `app/src/main/java/com/omnidev/workspace/data/tools/ProgressiveTrustTool.kt` |
| `media_control` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `media_generation` | `app/src/main/java/com/omnidev/workspace/data/tools/MediaGenerationTool.kt` |
| `memory_snapshot` | `app/src/main/java/com/omnidev/workspace/data/tools/GodEyeProfilerTool.kt` |
| `multi_patch_file_content` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `multi_read` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `n8n_automation` | `app/src/main/java/com/omnidev/workspace/data/tools/N8nAutomationTool.kt` |
| `network_monitor` | `app/src/main/java/com/omnidev/workspace/data/tools/NetworkMonitorTool.kt` |
| `network_request` | `app/src/main/java/com/omnidev/workspace/data/tools/NetworkRequestTool.kt` |
| `omni_link` | `app/src/main/java/com/omnidev/workspace/data/tools/OmniLinkTool.kt` |
| `package_installer_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedSystemTools.kt` |
| `patch_file_content` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `planner_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/SystemAssistantTools.kt` |
| `predictive_analytics` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `privileged_tool` | `app/src/main/java/com/omnidev/workspace/data/ipc/OmniCoreAgentTool.kt` |
| `python_runner` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `python_runtime` | `app/src/main/java/com/omnidev/workspace/data/tools/PythonRuntimeManager.kt` |
| `quality_security_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/QualitySecurityTool.kt` |
| `read_chat_message` | `app/src/main/java/com/omnidev/workspace/data/tools/research/MessageSearchTool.kt` |
| `read_chat_session` | `app/src/main/java/com/omnidev/workspace/data/tools/research/MessageSearchTool.kt` |
| `read_db_schema` | `app/src/main/java/com/omnidev/workspace/data/tools/GodEyeProfilerTool.kt` |
| `read_file_lines` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `read_incoming_sms` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `read_network_log` | `app/src/main/java/com/omnidev/workspace/data/tools/GodEyeProfilerTool.kt` |
| `read_notifications` | `app/src/main/java/com/omnidev/workspace/data/tools/NotificationCaptureTool.kt` |
| `remember_fact` | `app/src/main/java/com/omnidev/workspace/data/tools/MemoryManager.kt` |
| `replace_lines` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `repo_file_symbols` | `app/src/main/java/com/omnidev/workspace/data/tools/RepoContextTools.kt` |
| `repo_find_context` | `app/src/main/java/com/omnidev/workspace/data/tools/RepoContextTools.kt` |
| `repo_index_scope` | `app/src/main/java/com/omnidev/workspace/data/tools/RepoContextTools.kt` |
| `repo_search_symbols` | `app/src/main/java/com/omnidev/workspace/data/tools/RepoContextTools.kt` |
| `repo_stats` | `app/src/main/java/com/omnidev/workspace/data/tools/RepoContextTools.kt` |
| `repo_symbols_by_kind` | `app/src/main/java/com/omnidev/workspace/data/tools/RepoContextTools.kt` |
| `request_github_auth` | `app/src/main/java/com/omnidev/workspace/data/tools/RequestGitHubAuthenticationTool.kt` |
| `request_permission` | `app/src/main/java/com/omnidev/workspace/data/tools/PermissionManagerTool.kt` |
| `reset_trust` | `app/src/main/java/com/omnidev/workspace/data/tools/ProgressiveTrustTool.kt` |
| `rollback_apply_group` | `app/src/main/java/com/omnidev/workspace/data/tools/RollbackTools.kt` |
| `rollback_apply_one` | `app/src/main/java/com/omnidev/workspace/data/tools/RollbackTools.kt` |
| `rollback_list_groups` | `app/src/main/java/com/omnidev/workspace/data/tools/RollbackTools.kt` |
| `rollback_list_recent` | `app/src/main/java/com/omnidev/workspace/data/tools/RollbackTools.kt` |
| `root_shell_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedSystemTools.kt` |
| `run_script` | `app/src/main/java/com/omnidev/workspace/data/tools/ScriptRunnerTool.kt` |
| `run_terminal` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `sandbox_execution_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/AgentSandboxTool.kt` |
| `scrape_multiple` | `app/src/main/java/com/omnidev/workspace/data/tools/WebScraperTool.kt` |
| `screenshot_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedSystemTools.kt` |
| `search_codebase` | `app/src/main/java/com/omnidev/workspace/data/tools/FileToolManager.kt` |
| `search_contacts` | `app/src/main/java/com/omnidev/workspace/data/tools/SystemContactsTool.kt` |
| `search_knowledge` | `app/src/main/java/com/omnidev/workspace/data/tools/MemoryManager.kt` |
| `search_messages` | `app/src/main/java/com/omnidev/workspace/data/tools/research/MessageSearchTool.kt` |
| `secure_delete` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `security_analyzer` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `semantic_ui` | `app/src/main/java/com/omnidev/workspace/data/accessibility/SemanticUITool.kt` |
| `sendgrid_email` | `app/src/main/java/com/omnidev/workspace/data/tools/SendGridEmailTool.kt` |
| `slack` | `app/src/main/java/com/omnidev/workspace/data/tools/SlackTool.kt` |
| `sms_reader_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedSystemTools.kt` |
| `social_media_video` | `app/src/main/java/com/omnidev/workspace/data/tools/SocialMediaTool.kt` |
| `symlink_manager` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedFileTools.kt` |
| `sync_service` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `system_launcher_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/LauncherControlTool.kt` |
| `system_power` | `app/src/main/java/com/omnidev/workspace/data/tools/SystemPowerTool.kt` |
| `system_settings_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/AdvancedSystemTools.kt` |
| `task_manager` | `app/src/main/java/com/omnidev/workspace/data/tools/TaskManagerTool.kt` |
| `task_scheduler` | `app/src/main/java/com/omnidev/workspace/data/tools/TaskSchedulerTool.kt` |
| `telegram_bot` | `app/src/main/java/com/omnidev/workspace/data/tools/TelegramBotTool.kt` |
| `termux_bridge` | `app/src/main/java/com/omnidev/workspace/data/tools/TermuxEnvironmentBridge.kt` |
| `tool_monitoring` | `app/src/main/java/com/omnidev/workspace/data/tools/CompositeToolManager.kt` |
| `tools_status` | `app/src/main/java/com/omnidev/workspace/data/tools/ToolDownloaderEngine.kt` |
| `ui_automation` | `app/src/main/java/com/omnidev/workspace/data/tools/UIAutomationTool.kt` |
| `ui_replica_pipeline` | `app/src/main/java/com/omnidev/workspace/data/tools/UIReplicaPipelineTool.kt` |
| `update_memory` | `app/src/main/java/com/omnidev/workspace/data/tools/MemoryManager.kt` |
| `vector_search` | `app/src/main/java/com/omnidev/workspace/data/tools/VectorMemoryManager.kt` |
| `vector_similar` | `app/src/main/java/com/omnidev/workspace/data/tools/VectorMemoryManager.kt` |
| `vector_store` | `app/src/main/java/com/omnidev/workspace/data/tools/VectorMemoryManager.kt` |
| `visual_inspector` | `app/src/main/java/com/omnidev/workspace/data/tools/VisualInspectorTool.kt` |
| `vpn_control` | `app/src/main/java/com/omnidev/workspace/data/tools/VPNControlTool.kt` |
| `web_scraper` | `app/src/main/java/com/omnidev/workspace/data/tools/WebScraperTool.kt` |
| `web_search` | `app/src/main/java/com/omnidev/workspace/data/tools/WebSearchTool.kt` |
| `web_search_deep` | `app/src/main/java/com/omnidev/workspace/data/tools/WebSearchTool.kt` |
| `whatsapp` | `app/src/main/java/com/omnidev/workspace/data/tools/WhatsAppTool.kt` |
| `widget_generator_tool` | `app/src/main/java/com/omnidev/workspace/data/tools/WidgetGeneratorTool.kt` |
