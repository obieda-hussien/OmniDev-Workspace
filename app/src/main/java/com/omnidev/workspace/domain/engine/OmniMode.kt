package com.omnidev.workspace.domain.engine

/**
 * Defines the four execution modes available in OmniDev Workspace.
 *
 * Each mode routes the user's prompt to a different engine:
 * - [AUTO]  — Intelligent auto-routing: classifies task complexity and routes to Chat, Agent, or Swarm.
 * - [CHAT]  — Direct conversational completion, no tools, no ReAct loop.
 * - [AGENT] — Single autonomous agent with ReAct loop and full tool access.
 * - [SWARM] — Multi-agent orchestration: an Orchestrator plans, Workers execute.
 *
 * @property label The human-readable label shown on the segmented button.
 */
enum class OmniMode(val label: String) {
    /** Smart auto-routing — the system chooses Chat, Agent, or Swarm based on task complexity. */
    AUTO("Auto 🧠"),

    /** Conversational mode — bypass tools, send directly to CompletionService. */
    CHAT("Chat"),

    /** Single autonomous agent — full ReAct loop with tools. */
    AGENT("Agent"),

    /** Multi-agent swarm — Orchestrator plans, Workers implement. */
    SWARM("Team Agents")
}
