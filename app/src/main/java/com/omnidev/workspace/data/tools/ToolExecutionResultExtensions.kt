package com.omnidev.workspace.data.tools

/**
 * Positive-form companion to [ToolExecutionResult.isError].
 *
 * A number of runtime/bootstrap checks are naturally expressed as success
 * predicates. Keeping the inverse in one place avoids repeatedly spelling
 * `!result.isError` and provides a stable compatibility surface for existing
 * runtime code while [ToolExecutionResult] keeps its serialized schema minimal.
 */
internal val ToolExecutionResult.isSuccess: Boolean
    get() = !isError
