package com.omnidev.workspace.data.tools.orchestration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * ToolDependencyGraph — Advanced DAG-based tool dependency resolver
 * 
 * Features:
 * - Automatic dependency detection and execution ordering
 * - Cycle detection to prevent infinite loops
 * - Parallel execution of independent tools
 * - Smart caching based on dependency chain
 * - Pre/Post execution hooks
 * 
 * Example:
 * ```
 * graph.addDependency("git_commit", listOf("git_status", "file_read"))
 * graph.addDependency("git_push", listOf("git_commit"))
 * 
 * val executionOrder = graph.resolveExecutionOrder("git_push")
 * // Returns: ["git_status", "file_read", "git_commit", "git_push"]
 * ```
 */
class ToolDependencyGraph {
    
    private val dependencies = ConcurrentHashMap<String, MutableSet<String>>()
    private val reverseDeps = ConcurrentHashMap<String, MutableSet<String>>()
    private val executionHooks = ConcurrentHashMap<String, MutableList<ExecutionHook>>()
    private val mutex = Mutex()
    
    data class ExecutionHook(
        val phase: Phase,
        val action: suspend (String, Map<String, String>) -> Unit
    ) {
        enum class Phase { PRE_EXECUTION, POST_EXECUTION, ON_ERROR }
    }
    
    /**
     * Register tool dependencies
     */
    suspend fun addDependency(tool: String, requiredTools: List<String>) = mutex.withLock {
        dependencies.computeIfAbsent(tool) { ConcurrentHashMap.newKeySet() }.addAll(requiredTools)
        
        // Update reverse dependencies for fast lookup
        requiredTools.forEach { dep ->
            reverseDeps.computeIfAbsent(dep) { ConcurrentHashMap.newKeySet() }.add(tool)
        }
    }
    
    /**
     * Resolve execution order using topological sort
     * Returns null if cycle detected
     */
    fun resolveExecutionOrder(targetTool: String): List<String>? {
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val result = mutableListOf<String>()
        
        fun dfs(tool: String): Boolean {
            if (recursionStack.contains(tool)) return false // Cycle detected
            if (visited.contains(tool)) return true
            
            recursionStack.add(tool)
            visited.add(tool)
            
            dependencies[tool]?.forEach { dep ->
                if (!dfs(dep)) return false
            }
            
            recursionStack.remove(tool)
            result.add(tool)
            return true
        }
        
        return if (dfs(targetTool)) result else null
    }
    
    /**
     * Find all tools that can run in parallel (no dependencies between them)
     */
    fun getParallelExecutionBatches(tools: List<String>): List<List<String>> {
        val inDegree = mutableMapOf<String, Int>()
        val batches = mutableListOf<List<String>>()
        val remaining = tools.toMutableSet()
        
        // Calculate in-degree for each tool
        tools.forEach { tool ->
            inDegree[tool] = dependencies[tool]?.count { it in tools } ?: 0
        }
        
        while (remaining.isNotEmpty()) {
            // Find all tools with in-degree 0 (can run now)
            val batch = remaining.filter { inDegree[it] == 0 }
            if (batch.isEmpty()) break // Cycle detected or error
            
            batches.add(batch)
            remaining.removeAll(batch.toSet())
            
            // Update in-degrees
            batch.forEach { completed ->
                reverseDeps[completed]?.forEach { dependent ->
                    if (dependent in remaining) {
                        inDegree[dependent] = (inDegree[dependent] ?: 0) - 1
                    }
                }
            }
        }
        
        return batches
    }
    
    /**
     * Register execution hook for a specific tool
     */
    suspend fun addHook(tool: String, hook: ExecutionHook) = mutex.withLock {
        executionHooks.computeIfAbsent(tool) { mutableListOf() }.add(hook)
    }
    
    /**
     * Get all hooks for a tool at specific phase
     */
    fun getHooks(tool: String, phase: ExecutionHook.Phase): List<ExecutionHook> {
        return executionHooks[tool]?.filter { it.phase == phase } ?: emptyList()
    }
    
    /**
     * Analyze dependency depth (max distance from root)
     */
    fun getDependencyDepth(tool: String): Int {
        val deps = dependencies[tool] ?: return 0
        if (deps.isEmpty()) return 0
        return 1 + (deps.maxOfOrNull { getDependencyDepth(it) } ?: 0)
    }
    
    /**
     * Find all transitive dependencies
     */
    fun getAllDependencies(tool: String): Set<String> {
        val result = mutableSetOf<String>()
        fun collect(t: String) {
            dependencies[t]?.forEach { dep ->
                if (result.add(dep)) collect(dep)
            }
        }
        collect(tool)
        return result
    }
    
    /**
     * Check if tool A depends on tool B (directly or transitively)
     */
    fun dependsOn(toolA: String, toolB: String): Boolean {
        return getAllDependencies(toolA).contains(toolB)
    }
    
    /**
     * Get critical path (longest dependency chain)
     */
    fun getCriticalPath(tool: String): List<String> {
        val deps = dependencies[tool] ?: return listOf(tool)
        if (deps.isEmpty()) return listOf(tool)
        
        val longestPath = deps.map { getCriticalPath(it) }.maxByOrNull { it.size } ?: emptyList()
        return longestPath + tool
    }
    
    /**
     * Visualize dependency graph as DOT format (for debugging)
     */
    fun toDot(): String = buildString {
        appendLine("digraph ToolDependencies {")
        appendLine("  rankdir=LR;")
        appendLine("  node [shape=box, style=rounded];")
        
        dependencies.forEach { (tool, deps) ->
            deps.forEach { dep ->
                appendLine("  \"$dep\" -> \"$tool\";")
            }
        }
        
        appendLine("}")
    }
    
    /**
     * Clear all dependencies (useful for testing)
     */
    suspend fun clear() = mutex.withLock {
        dependencies.clear()
        reverseDeps.clear()
        executionHooks.clear()
    }
    
    companion object {
        /**
         * Build default dependency graph for common tool chains
         */
        suspend fun createDefault(): ToolDependencyGraph {
            return ToolDependencyGraph().apply {
                // Git workflow
                addDependency("git_commit", listOf("git_status"))
                addDependency("git_push", listOf("git_commit"))
                addDependency("git_pull", listOf("git_status"))
                
                // File operations
                addDependency("patch_file_content", listOf("read_file_lines"))
                addDependency("run_terminal", listOf("search_codebase"))
                
                // System operations
                addDependency("app_manager_tool", listOf("privileged_tool"))
                addDependency("package_installer_tool", listOf("privileged_tool"))
                
                // Network operations
                addDependency("web_scraper", listOf("network_request"))
                addDependency("github_manager", listOf("request_github_auth"))
                
                // Memory operations
                addDependency("vector_search", listOf("vector_store"))
                addDependency("search_knowledge", listOf("remember_fact"))
            }
        }
    }
}
