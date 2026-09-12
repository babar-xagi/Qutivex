package dev.qutivex.engine.pom

data class PomParent(
    val group: String,
    val artifact: String,
    val version: String,
    val relativePath: String? = null,
) {
    val key: String get() = "$group:$artifact"
    val standardNotation: String get() = "$group:$artifact:$version"
}

data class PomExclusion(
    val group: String,
    val artifact: String,
) {
    fun matches(targetGroup: String, targetArtifact: String): Boolean {
        val groupMatches = group == "*" || group.equals(targetGroup, ignoreCase = true)
        val artifactMatches = artifact == "*" || artifact.equals(targetArtifact, ignoreCase = true)
        return groupMatches && artifactMatches
    }
}

data class PomDependency(
    val group: String,
    val artifact: String,
    val version: String? = null,
    val scope: String = "compile",
    val optional: Boolean = false,
    val type: String = "jar",
    val classifier: String? = null,
    val exclusions: List<PomExclusion> = emptyList(),
) {
    val key: String get() = "$group:$artifact"
    val standardNotation: String get() = if (version != null) "$group:$artifact:$version" else "$group:$artifact"
}

data class PomModel(
    val group: String,
    val artifact: String,
    val version: String,
    val packaging: String = "jar",
    val parent: PomParent? = null,
    val properties: Map<String, String> = emptyMap(),
    val dependencyManagement: List<PomDependency> = emptyList(),
    val dependencies: List<PomDependency> = emptyList(),
) {
    val key: String get() = "$group:$artifact"
    val standardNotation: String get() = "$group:$artifact:$version"
}
