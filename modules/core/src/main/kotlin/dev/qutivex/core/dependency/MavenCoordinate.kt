package dev.qutivex.core.dependency

/**
 * Represents a fully specified Maven coordinate.
 */
data class MavenCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
    val packaging: String = "jar",
    val classifier: String? = null,
) {
    val key: String get() = "$group:$artifact"
    val standardNotation: String get() = "$group:$artifact:$version"
    val atNotation: String get() = "$group:$artifact@$version"

    val repoRelativePomPath: String
        get() = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom"

    val repoRelativeArtifactPath: String
        get() = if (classifier.isNullOrBlank()) {
            "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.$packaging"
        } else {
            "${group.replace('.', '/')}/$artifact/$version/$artifact-$version-$classifier.$packaging"
        }

    val repoRelativeMetadataPath: String
        get() = "${group.replace('.', '/')}/$artifact/maven-metadata.xml"

    fun toDependencyCoordinate(): DependencyCoordinate =
        DependencyCoordinate(group, artifact, version)

    companion object {
        fun from(coord: DependencyCoordinate, packaging: String = "jar", classifier: String? = null): MavenCoordinate =
            MavenCoordinate(coord.group, coord.artifact, coord.version, packaging, classifier)

        fun parse(notation: String, defaultPackaging: String = "jar"): MavenCoordinate {
            val dep = DependencyCoordinate.parse(notation)
            return MavenCoordinate(dep.group, dep.artifact, dep.version, defaultPackaging)
        }
    }
}
