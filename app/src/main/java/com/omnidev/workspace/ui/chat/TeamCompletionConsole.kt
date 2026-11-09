package com.omnidev.workspace.ui.chat

import com.omnidev.workspace.domain.engine.teamcompletion.TeamCompletionReport

/** Uses the existing persisted console format; an ended flow is not necessarily completed work. */
fun teamCompletionEntries(report: TeamCompletionReport?): List<AgentConsoleEntry> = buildList {
    if (report != null) add(AgentConsoleEntry.PhaseEntry("Team verification", report.render()))
    if (report?.verified != true) add(AgentConsoleEntry.ErrorEntry(
        "Team verification: ${report?.status?.wire ?: "unverified"}. Review unfinished work before continuing."))
}
