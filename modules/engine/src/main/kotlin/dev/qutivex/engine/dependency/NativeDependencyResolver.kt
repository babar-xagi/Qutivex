package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.ComparableVersion
import dev.qutivex.core.dependency.DependencyGraph
import dev.qutivex.core.dependency.MavenCoordinate
import dev.qutivex.core.dependency.ResolvedDependency
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.pom.PomDependency
import dev.qutivex.engine.pom.PomExclusion
import dev.qutivex.engine.pom.PomModel
import dev.qutivex.engine.pom.PomParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Fully native, Gradle-free dependency graph resolver for Maven Central.
 * Resolves POMs, parent POMs, dependencyManagement, imported BOMs, properties,
 * scopes, exclusions, optional dependencies, version conflicts, and cycles.
 */
class NativeDependencyResolver(
    private val repositoryClient: RepositoryClient = HttpRepositoryClient(),
    private val artifactCache: ArtifactCache = LocalArtifactCache(),
    private val pomParser: PomParser = PomParser(),
    private val repositoryUrl: String = RepositoryClient.DEFAULT_REPOSITORY,
) : DependencyResolver {

    private val pomCache = ConcurrentHashMap<String, PomModel>()

    override fun resolve(
        projectDir: Path,
        manifest: ManifestSpec,
        offline: Boolean,
        verbose: Boolean,
    ): DependencyGraph {
        val runtimeRoots = manifest.dependencies.map { (key, ver) ->
            val parts = key.split(':', limit = 2)
            DependencyRoot(group = parts[0], artifact = parts[1], version = ver, scope = "runtime")
        }
        val testRoots = manifest.testDependencies.map { (key, ver) ->
            val parts = key.split(':', limit = 2)
            DependencyRoot(group = parts[0], artifact = parts[1], version = ver, scope = "test")
        }

        // 1. Resolve runtime graph
        val runtimeNodes = resolveSubGraph(
            roots = runtimeRoots,
            targetScope = "runtime",
            offline = offline,
            verbose = verbose,
        )

        // 2. Resolve test graph (including runtime dependencies as available)
        val allTestRoots = runtimeRoots.map { it.copy(scope = "runtime") } + testRoots
        val testNodes = resolveSubGraph(
            roots = allTestRoots,
            targetScope = "test",
            offline = offline,
            verbose = verbose,
        )

        // 3. Download/verify artifacts and compute checksums
        val allDistinctNodes = (runtimeNodes.values + testNodes.values).distinctBy { "${it.group}:${it.artifact}:${it.selectedVersion}" }

        for (node in allDistinctNodes) {
            ensureArtifactCached(node, offline)
        }

        // 4. Construct final ResolvedDependency list
        val packages = mutableListOf<ResolvedDependency>()
        val seenPackageKeys = mutableSetOf<String>()

        val directRuntimeKeys = manifest.dependencies.keys
        val directTestKeys = manifest.testDependencies.keys

        // Add runtime packages
        for (node in runtimeNodes.values) {
            val key = "${node.group}:${node.artifact}"
            val dedupe = "runtime:$key"
            if (seenPackageKeys.add(dedupe)) {
                val isDirect = key in directRuntimeKeys
                val checksum = node.checksum
                packages.add(
                    ResolvedDependency(
                        group = node.group,
                        artifact = node.artifact,
                        version = node.selectedVersion,
                        scope = "runtime",
                        direct = isDirect,
                        dependencies = node.childKeys.sorted(),
                        checksum = checksum,
                        repository = repositoryUrl,
                    )
                )
            }
        }

        // Add test packages (nodes in test graph not present in runtime graph, or direct test dependencies)
        for (node in testNodes.values) {
            val key = "${node.group}:${node.artifact}"
            val isDirectTest = key in directTestKeys
            val inRuntime = runtimeNodes.containsKey(key)

            if (!inRuntime || isDirectTest) {
                val dedupe = "test:$key"
                if (seenPackageKeys.add(dedupe)) {
                    val checksum = node.checksum
                    packages.add(
                        ResolvedDependency(
                            group = node.group,
                            artifact = node.artifact,
                            version = node.selectedVersion,
                            scope = "test",
                            direct = isDirectTest,
                            dependencies = node.childKeys.sorted(),
                            checksum = checksum,
                            repository = repositoryUrl,
                        )
                    )
                }
            }
        }

        // Deterministic sorting: runtime first, then test, then group, then artifact
        packages.sortWith(
            compareBy<ResolvedDependency> { if (it.scope == "runtime") 0 else 1 }
                .thenBy { it.group }
                .thenBy { it.artifact }
        )

        return DependencyGraph(
            rootProjectName = manifest.project.name,
            packages = packages,
        )
    }

    private fun resolveSubGraph(
        roots: List<DependencyRoot>,
        targetScope: String,
        offline: Boolean,
        verbose: Boolean,
    ): Map<String, GraphNode> {
        val resolvedNodes = mutableMapOf<String, GraphNode>()
        val queue = ArrayDeque<TraversalItem>()

        for (root in roots) {
            val key = "${root.group}:${root.artifact}"
            val node = GraphNode(
                group = root.group,
                artifact = root.artifact,
                selectedVersion = root.version,
                requestedVersions = mutableSetOf(root.version),
                scope = root.scope,
                isDirect = true,
                selectionReason = "direct declaration",
            )
            resolvedNodes[key] = node
            queue.add(
                TraversalItem(
                    group = root.group,
                    artifact = root.artifact,
                    version = root.version,
                    effectiveScope = root.scope,
                    activeExclusions = emptySet(),
                    path = listOf(key),
                )
            )
        }

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            val currentKey = "${current.group}:${current.artifact}"
            val currentNode = resolvedNodes[currentKey] ?: continue

            // Only expand if current version matches selectedVersion
            if (current.version != currentNode.selectedVersion) {
                continue
            }

            val pom = resolveEffectivePom(
                group = current.group,
                artifact = current.artifact,
                version = current.version,
                offline = offline,
            )

            currentNode.packaging = pom.packaging

            for (dep in pom.dependencies) {
                // Skip optional dependencies for transitives
                if (dep.optional) {
                    continue
                }

                // Map scopes
                val depScope = (dep.scope.ifBlank { "compile" }).lowercase()
                val propagatedScope = when (current.effectiveScope) {
                    "runtime" -> when (depScope) {
                        "compile", "runtime" -> "runtime"
                        else -> null // test, provided, system ignored
                    }
                    "test" -> when (depScope) {
                        "compile", "runtime" -> "test"
                        else -> null
                    }
                    else -> null
                } ?: continue

                // Check exclusions
                val childKey = "${dep.group}:${dep.artifact}"
                val combinedExclusions = current.activeExclusions + dep.exclusions
                val isExcluded = combinedExclusions.any { it.matches(dep.group, dep.artifact) }
                if (isExcluded) {
                    continue
                }

                val childVersion = dep.version
                if (childVersion.isNullOrBlank()) {
                    continue
                }

                currentNode.childKeys.add(childKey)

                // Cycle detection
                if (childKey in current.path) {
                    // Record edge but do not recurse into cycle
                    continue
                }

                val existing = resolvedNodes[childKey]
                if (existing == null) {
                    val newNode = GraphNode(
                        group = dep.group,
                        artifact = dep.artifact,
                        selectedVersion = childVersion,
                        requestedVersions = mutableSetOf(childVersion),
                        scope = propagatedScope,
                        isDirect = false,
                        selectionReason = "transitive dependency",
                    )
                    resolvedNodes[childKey] = newNode
                    queue.add(
                        TraversalItem(
                            group = dep.group,
                            artifact = dep.artifact,
                            version = childVersion,
                            effectiveScope = propagatedScope,
                            activeExclusions = combinedExclusions,
                            path = current.path + childKey,
                        )
                    )
                } else {
                    existing.requestedVersions.add(childVersion)
                    val cmp = ComparableVersion(childVersion).compareTo(ComparableVersion(existing.selectedVersion))
                    if (cmp > 0) {
                        // Conflict resolution: highest version selected!
                        val oldVersion = existing.selectedVersion
                        existing.selectedVersion = childVersion
                        existing.selectionReason = "selected highest version $childVersion over $oldVersion"
                        // Re-queue with upgraded version
                        queue.add(
                            TraversalItem(
                                group = dep.group,
                                artifact = dep.artifact,
                                version = childVersion,
                                effectiveScope = existing.scope,
                                activeExclusions = combinedExclusions,
                                path = current.path + childKey,
                            )
                        )
                    }
                }
            }
        }

        return resolvedNodes
    }

    private fun ensureArtifactCached(node: GraphNode, offline: Boolean) {
        if (node.packaging.equals("pom", ignoreCase = true)) {
            // POM packaging artifacts do not have JAR files
            return
        }

        val existing = artifactCache.findArtifact(node.group, node.artifact, node.selectedVersion)
        if (existing != null) {
            node.checksum = "sha256:" + LocalArtifactCache.computeSha256(existing)
            return
        }

        if (offline) {
            val expected = artifactCache.getExpectedLocation(node.group, node.artifact, node.selectedVersion)
            throw MissingOfflineArtifactException(node.group, node.artifact, node.selectedVersion, expected)
        }

        val destination = artifactCache.getExpectedLocation(node.group, node.artifact, node.selectedVersion)
        val lockKey = "${node.group}:${node.artifact}:${node.selectedVersion}:jar"

        val result = artifactCache.withLock(lockKey) {
            val already = artifactCache.findArtifact(node.group, node.artifact, node.selectedVersion)
            if (already != null) {
                DownloadResult(already, LocalArtifactCache.computeSha256(already), Files.size(already))
            } else {
                repositoryClient.fetchArtifact(
                    group = node.group,
                    artifact = node.artifact,
                    version = node.selectedVersion,
                    destination = destination,
                    repositoryUrl = repositoryUrl,
                )
            }
        }
        node.checksum = "sha256:" + result.sha256
    }

    internal fun resolveEffectivePom(
        group: String,
        artifact: String,
        version: String,
        offline: Boolean,
        parentVisited: Set<String> = emptySet(),
    ): PomModel {
        val cacheKey = "$group:$artifact:$version"
        pomCache[cacheKey]?.let { return it }

        if (cacheKey in parentVisited) {
            throw DependencyException("Parent POM cycle detected: ${parentVisited.joinToString(" -> ")} -> $cacheKey")
        }

        val pomFile = findOrFetchPom(group, artifact, version, offline)
        val rawPom = pomParser.parse(Files.readString(pomFile))

        // 1. Resolve parent recursively if present
        val effectiveParent = if (rawPom.parent != null) {
            val parentModel = resolveEffectivePom(
                group = rawPom.parent.group,
                artifact = rawPom.parent.artifact,
                version = rawPom.parent.version,
                offline = offline,
                parentVisited = parentVisited + cacheKey,
            )
            parentModel
        } else null

        // 2. Merge properties (child overrides parent)
        val mergedProperties = mutableMapOf<String, String>()
        if (effectiveParent != null) {
            mergedProperties.putAll(effectiveParent.properties)
        }
        mergedProperties.putAll(rawPom.properties)

        // 3. Merge dependencyManagement (child overrides parent)
        val mergedDepMgmt = mutableMapOf<String, PomDependency>()
        if (effectiveParent != null) {
            for (d in effectiveParent.dependencyManagement) {
                mergedDepMgmt[d.key] = d
            }
        }
        for (d in rawPom.dependencyManagement) {
            mergedDepMgmt[d.key] = d
        }

        // 4. Resolve BOM imports in dependencyManagement
        val resolvedBomDepMgmt = mutableMapOf<String, PomDependency>()
        for ((key, dep) in mergedDepMgmt) {
            if (dep.scope.equals("import", ignoreCase = true) && dep.type.equals("pom", ignoreCase = true)) {
                val bomVersion = dep.version
                if (!bomVersion.isNullOrBlank()) {
                    try {
                        val bomPom = resolveEffectivePom(
                            group = dep.group,
                            artifact = dep.artifact,
                            version = bomVersion,
                            offline = offline,
                            parentVisited = parentVisited + cacheKey,
                        )
                        for (bomDep in bomPom.dependencyManagement) {
                            if (!resolvedBomDepMgmt.containsKey(bomDep.key)) {
                                resolvedBomDepMgmt[bomDep.key] = bomDep
                            }
                        }
                    } catch (_: Exception) {}
                }
            } else {
                resolvedBomDepMgmt[key] = dep
            }
        }

        // 5. Interpolate properties
        val mergedPom = rawPom.copy(
            group = rawPom.group.ifBlank { effectiveParent?.group ?: group },
            version = rawPom.version.ifBlank { effectiveParent?.version ?: version },
            properties = mergedProperties,
            dependencyManagement = resolvedBomDepMgmt.values.toList(),
        )

        val interpolated = pomParser.interpolate(mergedPom, mergedProperties)

        // 6. Fill missing versions in dependencies from dependencyManagement
        val depMgmtMap = interpolated.dependencyManagement.associateBy { it.key }
        val finalDependencies = interpolated.dependencies.map { dep ->
            if (dep.version.isNullOrBlank()) {
                val managed = depMgmtMap[dep.key]
                if (managed?.version != null) {
                    dep.copy(
                        version = managed.version,
                        scope = if (dep.scope.isBlank() || dep.scope == "compile") managed.scope else dep.scope,
                        exclusions = if (dep.exclusions.isEmpty()) managed.exclusions else dep.exclusions,
                    )
                } else dep
            } else dep
        }

        val finalModel = interpolated.copy(dependencies = finalDependencies)
        pomCache[cacheKey] = finalModel
        return finalModel
    }

    private fun findOrFetchPom(group: String, artifact: String, version: String, offline: Boolean): Path {
        val existing = artifactCache.findPom(group, artifact, version)
        if (existing != null) return existing

        if (offline) {
            val expected = artifactCache.getExpectedPomLocation(group, artifact, version)
            throw MissingOfflineArtifactException(group, artifact, version, expected)
        }

        val destination = artifactCache.getExpectedPomLocation(group, artifact, version)
        val lockKey = "$group:$artifact:$version:pom"

        return artifactCache.withLock(lockKey) {
            val already = artifactCache.findPom(group, artifact, version)
            if (already != null) {
                already
            } else {
                repositoryClient.fetchPom(
                    group = group,
                    artifact = artifact,
                    version = version,
                    destination = destination,
                    repositoryUrl = repositoryUrl,
                )
                destination
            }
        }
    }

    private data class DependencyRoot(
        val group: String,
        val artifact: String,
        val version: String,
        val scope: String,
    )

    private data class TraversalItem(
        val group: String,
        val artifact: String,
        val version: String,
        val effectiveScope: String,
        val activeExclusions: Set<PomExclusion>,
        val path: List<String>,
    )

    private data class GraphNode(
        val group: String,
        val artifact: String,
        var selectedVersion: String,
        val requestedVersions: MutableSet<String>,
        val scope: String,
        val isDirect: Boolean,
        var selectionReason: String,
        var packaging: String = "jar",
        var checksum: String? = null,
        val childKeys: MutableSet<String> = mutableSetOf(),
    )
}
