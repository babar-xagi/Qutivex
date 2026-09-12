package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.ResolvedDependency

object DependencyTreeRenderer {

    fun render(
        projectName: String,
        projectVersion: String,
        projectPath: String,
        packages: List<ResolvedDependency>,
        scope: String = "all",
        maxDepth: Int = Int.MAX_VALUE,
        verbose: Boolean = false,
    ): String {
        val sb = StringBuilder()
        sb.append("$projectName v$projectVersion ($projectPath)\n")

        val renderRuntime = scope.equals("all", ignoreCase = true) || scope.equals("runtime", ignoreCase = true)
        val renderTest = scope.equals("all", ignoreCase = true) || scope.equals("test", ignoreCase = true)

        val runtimePkgs = packages.filter { it.scope == "runtime" }
        val testPkgs = packages.filter { it.scope == "test" }

        val runtimeDirect = runtimePkgs.filter { it.direct }
        val testDirect = testPkgs.filter { it.direct }

        val bothScopes = renderRuntime && renderTest

        if (renderRuntime) {
            val runtimePkgMap = runtimePkgs.associateBy { "${it.group}:${it.artifact}" }
            if (bothScopes) {
                val hasTest = renderTest && testDirect.isNotEmpty()
                val sectionBranch = if (hasTest) "├── " else "└── "
                sb.append(sectionBranch).append("[dependencies]\n")
                val sectionIndent = if (hasTest) "│   " else "    "
                if (runtimeDirect.isEmpty()) {
                    sb.append(sectionIndent).append("└── (no dependencies)\n")
                } else {
                    val visited = mutableSetOf<String>()
                    runtimeDirect.forEachIndexed { index, root ->
                        val isLast = (index == runtimeDirect.size - 1)
                        renderNode(sb, root, runtimePkgMap, visited, sectionIndent, isLast, 1, maxDepth, verbose)
                    }
                }
            } else {
                if (runtimeDirect.isEmpty()) {
                    sb.append("└── (no dependencies)\n")
                } else {
                    val visited = mutableSetOf<String>()
                    runtimeDirect.forEachIndexed { index, root ->
                        val isLast = (index == runtimeDirect.size - 1)
                        renderNode(sb, root, runtimePkgMap, visited, "", isLast, 1, maxDepth, verbose)
                    }
                }
            }
        }

        if (renderTest) {
            val testPkgMap = testPkgs.associateBy { "${it.group}:${it.artifact}" }
            if (bothScopes) {
                sb.append("└── [test-dependencies]\n")
                val sectionIndent = "    "
                if (testDirect.isEmpty()) {
                    sb.append(sectionIndent).append("└── (no dependencies)\n")
                } else {
                    val visited = mutableSetOf<String>()
                    testDirect.forEachIndexed { index, root ->
                        val isLast = (index == testDirect.size - 1)
                        renderNode(sb, root, testPkgMap, visited, sectionIndent, isLast, 1, maxDepth, verbose)
                    }
                }
            } else {
                if (testDirect.isEmpty()) {
                    sb.append("└── (no dependencies)\n")
                } else {
                    val visited = mutableSetOf<String>()
                    testDirect.forEachIndexed { index, root ->
                        val isLast = (index == testDirect.size - 1)
                        renderNode(sb, root, testPkgMap, visited, "", isLast, 1, maxDepth, verbose)
                    }
                }
            }
        }

        return sb.toString().trimEnd()
    }

    private fun renderNode(
        sb: StringBuilder,
        pkg: ResolvedDependency,
        pkgMap: Map<String, ResolvedDependency>,
        visited: MutableSet<String>,
        prefix: String,
        isLast: Boolean,
        currentDepth: Int,
        maxDepth: Int,
        verbose: Boolean,
    ) {
        val branch = if (isLast) "└── " else "├── "
        val key = "${pkg.group}:${pkg.artifact}"
        var label = "$key:${pkg.version}"
        if (verbose) {
            val cs = pkg.checksum ?: "no-checksum"
            label += " [$cs] (${pkg.repository})"
        }

        val alreadyVisited = visited.contains(key)
        val line = if (alreadyVisited && pkg.dependencies.isNotEmpty()) {
            "$label (*)"
        } else {
            label
        }

        sb.append(prefix).append(branch).append(line).append("\n")

        if (alreadyVisited || currentDepth >= maxDepth) {
            return
        }

        visited.add(key)

        val nextPrefix = prefix + if (isLast) "    " else "│   "
        val children = pkg.dependencies.mapNotNull { pkgMap[it] }

        children.forEachIndexed { index, child ->
            val childIsLast = (index == children.size - 1)
            renderNode(sb, child, pkgMap, visited, nextPrefix, childIsLast, currentDepth + 1, maxDepth, verbose)
        }
    }
}
