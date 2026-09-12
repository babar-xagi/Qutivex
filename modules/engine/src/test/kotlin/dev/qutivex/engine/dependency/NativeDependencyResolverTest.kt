package dev.qutivex.engine.dependency

import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.pom.PomParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeDependencyResolverTest {

    @TempDir
    lateinit var tempDir: Path

    private val fakeClient = FakeRepositoryClient()

    @Test
    fun `resolves direct and transitive dependencies natively`() {
        val cacheDir = tempDir.resolve("cache")
        val cache = LocalArtifactCache(customDir = cacheDir)
        val resolver = NativeDependencyResolver(
            repositoryClient = fakeClient,
            artifactCache = cache,
            pomParser = PomParser(),
        )

        // Setup mock POMs
        fakeClient.registerPom(
            group = "org.example",
            artifact = "app-core",
            version = "1.0.0",
            xml = """
                <project>
                    <groupId>org.example</groupId>
                    <artifactId>app-core</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>util</artifactId>
                            <version>2.0.0</version>
                            <scope>compile</scope>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        fakeClient.registerPom(
            group = "org.example",
            artifact = "util",
            version = "2.0.0",
            xml = """
                <project>
                    <groupId>org.example</groupId>
                    <artifactId>util</artifactId>
                    <version>2.0.0</version>
                </project>
            """.trimIndent()
        )

        val manifest = ManifestSpec.fromProjectSpec(dev.qutivex.core.project.ProjectSpec("test-proj"))
            .withDependency("org.example:app-core", "1.0.0")

        val graph = resolver.resolve(tempDir, manifest, offline = false)
        assertEquals(2, graph.packages.size)

        val direct = graph.packages.first { it.key == "org.example:app-core" }
        assertTrue(direct.direct)
        assertEquals("1.0.0", direct.version)
        assertEquals("runtime", direct.scope)
        assertTrue(direct.dependencies.contains("org.example:util"))
        assertNotNull(direct.checksum)
        assertTrue(direct.checksum!!.startsWith("sha256:"))

        val transitive = graph.packages.first { it.key == "org.example:util" }
        assertFalse(transitive.direct)
        assertEquals("2.0.0", transitive.version)
        assertEquals("runtime", transitive.scope)
        assertNotNull(transitive.checksum)
    }

    @Test
    fun `resolves version conflicts using highest version policy`() {
        val cache = LocalArtifactCache(customDir = tempDir.resolve("cache"))
        val resolver = NativeDependencyResolver(
            repositoryClient = fakeClient,
            artifactCache = cache,
            pomParser = PomParser(),
        )

        // LibA -> Common:1.0.0
        fakeClient.registerPom(
            group = "org.example",
            artifact = "lib-a",
            version = "1.0.0",
            xml = """
                <project>
                    <groupId>org.example</groupId>
                    <artifactId>lib-a</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>common</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        // LibB -> Common:2.5.0
        fakeClient.registerPom(
            group = "org.example",
            artifact = "lib-b",
            version = "1.0.0",
            xml = """
                <project>
                    <groupId>org.example</groupId>
                    <artifactId>lib-b</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>common</artifactId>
                            <version>2.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        fakeClient.registerPom("org.example", "common", "1.0.0", "<project><groupId>org.example</groupId><artifactId>common</artifactId><version>1.0.0</version></project>")
        fakeClient.registerPom("org.example", "common", "2.5.0", "<project><groupId>org.example</groupId><artifactId>common</artifactId><version>2.5.0</version></project>")

        val manifest = ManifestSpec.fromProjectSpec(dev.qutivex.core.project.ProjectSpec("test-proj"))
            .withDependency("org.example:lib-a", "1.0.0")
            .withDependency("org.example:lib-b", "1.0.0")

        val graph = resolver.resolve(tempDir, manifest, offline = false)
        val common = graph.packages.first { it.key == "org.example:common" }
        assertEquals("2.5.0", common.version, "Highest version must be selected during conflict resolution")
    }

    @Test
    fun `excludes dependencies when exclusions are specified`() {
        val cache = LocalArtifactCache(customDir = tempDir.resolve("cache"))
        val resolver = NativeDependencyResolver(
            repositoryClient = fakeClient,
            artifactCache = cache,
            pomParser = PomParser(),
        )

        fakeClient.registerPom(
            group = "org.example",
            artifact = "lib-ex",
            version = "1.0.0",
            xml = """
                <project>
                    <groupId>org.example</groupId>
                    <artifactId>lib-ex</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>unwanted</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        fakeClient.registerPom(
            group = "org.example",
            artifact = "consumer",
            version = "1.0.0",
            xml = """
                <project>
                    <groupId>org.example</groupId>
                    <artifactId>consumer</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>lib-ex</artifactId>
                            <version>1.0.0</version>
                            <exclusions>
                                <exclusion>
                                    <groupId>org.example</groupId>
                                    <artifactId>unwanted</artifactId>
                                </exclusion>
                            </exclusions>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        fakeClient.registerPom("org.example", "unwanted", "1.0.0", "<project><groupId>org.example</groupId><artifactId>unwanted</artifactId><version>1.0.0</version></project>")

        val manifest = ManifestSpec.fromProjectSpec(dev.qutivex.core.project.ProjectSpec("test-proj"))
            .withDependency("org.example:consumer", "1.0.0")

        val graph = resolver.resolve(tempDir, manifest, offline = false)
        assertFalse(graph.packages.any { it.key == "org.example:unwanted" }, "Excluded dependency must not be present in graph")
    }

    @Test
    fun `skips optional dependencies during transitive resolution`() {
        val cache = LocalArtifactCache(customDir = tempDir.resolve("cache"))
        val resolver = NativeDependencyResolver(
            repositoryClient = fakeClient,
            artifactCache = cache,
            pomParser = PomParser(),
        )

        fakeClient.registerPom(
            group = "org.example",
            artifact = "opt-parent",
            version = "1.0.0",
            xml = """
                <project>
                    <groupId>org.example</groupId>
                    <artifactId>opt-parent</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>opt-child</artifactId>
                            <version>1.0.0</version>
                            <optional>true</optional>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        fakeClient.registerPom("org.example", "opt-child", "1.0.0", "<project><groupId>org.example</groupId><artifactId>opt-child</artifactId><version>1.0.0</version></project>")

        val manifest = ManifestSpec.fromProjectSpec(dev.qutivex.core.project.ProjectSpec("test-proj"))
            .withDependency("org.example:opt-parent", "1.0.0")

        val graph = resolver.resolve(tempDir, manifest, offline = false)
        assertEquals(1, graph.packages.size)
        assertFalse(graph.packages.any { it.key == "org.example:opt-child" })
    }

    @Test
    fun `detects dependency cycles and prevents infinite loops`() {
        val cache = LocalArtifactCache(customDir = tempDir.resolve("cache"))
        val resolver = NativeDependencyResolver(
            repositoryClient = fakeClient,
            artifactCache = cache,
            pomParser = PomParser(),
        )

        // A -> B -> C -> A
        fakeClient.registerPom(
            "org.example", "cycle-a", "1.0.0",
            """<project><groupId>org.example</groupId><artifactId>cycle-a</artifactId><version>1.0.0</version>
               <dependencies><dependency><groupId>org.example</groupId><artifactId>cycle-b</artifactId><version>1.0.0</version></dependency></dependencies></project>"""
        )
        fakeClient.registerPom(
            "org.example", "cycle-b", "1.0.0",
            """<project><groupId>org.example</groupId><artifactId>cycle-b</artifactId><version>1.0.0</version>
               <dependencies><dependency><groupId>org.example</groupId><artifactId>cycle-c</artifactId><version>1.0.0</version></dependency></dependencies></project>"""
        )
        fakeClient.registerPom(
            "org.example", "cycle-c", "1.0.0",
            """<project><groupId>org.example</groupId><artifactId>cycle-c</artifactId><version>1.0.0</version>
               <dependencies><dependency><groupId>org.example</groupId><artifactId>cycle-a</artifactId><version>1.0.0</version></dependency></dependencies></project>"""
        )

        val manifest = ManifestSpec.fromProjectSpec(dev.qutivex.core.project.ProjectSpec("test-proj"))
            .withDependency("org.example:cycle-a", "1.0.0")

        val graph = resolver.resolve(tempDir, manifest, offline = false)
        assertEquals(3, graph.packages.size, "Cycle resolution should terminate with all 3 nodes")
    }

    @Test
    fun `offline mode throws when artifacts are missing from cache`() {
        val cache = LocalArtifactCache(customDir = tempDir.resolve("empty-cache"))
        val resolver = NativeDependencyResolver(
            repositoryClient = fakeClient,
            artifactCache = cache,
            pomParser = PomParser(),
        )

        val manifest = ManifestSpec.fromProjectSpec(dev.qutivex.core.project.ProjectSpec("test-proj"))
            .withDependency("org.example:missing", "1.0.0")

        assertFailsWith<MissingOfflineArtifactException> {
            resolver.resolve(tempDir, manifest, offline = true)
        }
    }

    @Test
    fun `resolves dependency version from imported BOM in dependencyManagement`() {
        val cache = LocalArtifactCache(customDir = tempDir.resolve("cache"))
        val resolver = NativeDependencyResolver(
            repositoryClient = fakeClient,
            artifactCache = cache,
            pomParser = PomParser(),
        )

        // BOM defining library versions
        fakeClient.registerPom(
            group = "org.example",
            artifact = "my-bom",
            version = "1.0.0",
            xml = """
                <project>
                    <groupId>org.example</groupId>
                    <artifactId>my-bom</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.example</groupId>
                                <artifactId>managed-lib</artifactId>
                                <version>3.4.5</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        // Consumer importing BOM and declaring managed-lib without version
        fakeClient.registerPom(
            group = "org.example",
            artifact = "bom-consumer",
            version = "1.0.0",
            xml = """
                <project>
                    <groupId>org.example</groupId>
                    <artifactId>bom-consumer</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.example</groupId>
                                <artifactId>my-bom</artifactId>
                                <version>1.0.0</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>managed-lib</artifactId>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        fakeClient.registerPom(
            group = "org.example",
            artifact = "managed-lib",
            version = "3.4.5",
            xml = "<project><groupId>org.example</groupId><artifactId>managed-lib</artifactId><version>3.4.5</version></project>"
        )

        val manifest = ManifestSpec.fromProjectSpec(dev.qutivex.core.project.ProjectSpec("test-proj"))
            .withDependency("org.example:bom-consumer", "1.0.0")

        val graph = resolver.resolve(tempDir, manifest, offline = false)
        val managed = graph.packages.first { it.key == "org.example:managed-lib" }
        assertEquals("3.4.5", managed.version, "Version must be inherited from imported BOM")
    }
}

class FakeRepositoryClient : RepositoryClient {
    private val poms = mutableMapOf<String, String>()

    fun registerPom(group: String, artifact: String, version: String, xml: String) {
        poms["$group:$artifact:$version"] = xml
    }

    override fun isReachable(repositoryUrl: String): Boolean = true

    override fun fetchPom(
        group: String,
        artifact: String,
        version: String,
        destination: Path,
        repositoryUrl: String,
    ): DownloadResult {
        val key = "$group:$artifact:$version"
        val content = poms[key] ?: throw ArtifactNotFoundException(group, artifact, version, repositoryUrl)
        Files.createDirectories(destination.parent)
        Files.writeString(destination, content)
        val hash = LocalArtifactCache.computeSha256(destination)
        return DownloadResult(destination, hash, Files.size(destination))
    }

    override fun fetchArtifact(
        group: String,
        artifact: String,
        version: String,
        packaging: String,
        classifier: String?,
        destination: Path,
        repositoryUrl: String,
    ): DownloadResult {
        Files.createDirectories(destination.parent)
        Files.writeString(destination, "dummy-jar-content-for-$group:$artifact:$version")
        val hash = LocalArtifactCache.computeSha256(destination)
        return DownloadResult(destination, hash, Files.size(destination))
    }

    override fun fetchMetadata(
        group: String,
        artifact: String,
        destination: Path,
        repositoryUrl: String,
    ): DownloadResult {
        Files.createDirectories(destination.parent)
        Files.writeString(destination, "<metadata></metadata>")
        val hash = LocalArtifactCache.computeSha256(destination)
        return DownloadResult(destination, hash, Files.size(destination))
    }

    override fun fetchText(url: String): String = ""
}
