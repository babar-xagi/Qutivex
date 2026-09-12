package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.ResolvedDependency
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyTreeRendererTest {

    @Test
    fun `render formats direct and transitive dependencies`() {
        val packages = listOf(
            ResolvedDependency(
                group = "org.jetbrains.kotlinx",
                artifact = "kotlinx-coroutines-core",
                version = "1.10.2",
                scope = "runtime",
                direct = true,
                dependencies = listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm"),
            ),
            ResolvedDependency(
                group = "org.jetbrains.kotlinx",
                artifact = "kotlinx-coroutines-core-jvm",
                version = "1.10.2",
                scope = "runtime",
                direct = false,
                dependencies = listOf("org.jetbrains.kotlin:kotlin-stdlib"),
            ),
            ResolvedDependency(
                group = "org.jetbrains.kotlin",
                artifact = "kotlin-stdlib",
                version = "2.4.10",
                scope = "runtime",
                direct = false,
                dependencies = emptyList(),
            ),
            ResolvedDependency(
                group = "org.junit.jupiter",
                artifact = "junit-jupiter-api",
                version = "5.10.1",
                scope = "test",
                direct = true,
                dependencies = emptyList(),
            ),
        )

        val output = DependencyTreeRenderer.render(
            projectName = "my-app",
            projectVersion = "1.0.0",
            projectPath = "/app/my-app",
            packages = packages,
            scope = "all",
        )

        assertContains(output, "my-app v1.0.0 (/app/my-app)")
        assertContains(output, "[dependencies]")
        assertContains(output, "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        assertContains(output, "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
        assertContains(output, "org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
        assertContains(output, "[test-dependencies]")
        assertContains(output, "org.junit.jupiter:junit-jupiter-api:5.10.1")
    }

    @Test
    fun `render handles duplicates and circular dependencies with asterisk`() {
        val packages = listOf(
            ResolvedDependency(
                group = "com.example",
                artifact = "root-a",
                version = "1.0.0",
                scope = "runtime",
                direct = true,
                dependencies = listOf("com.example:shared-lib"),
            ),
            ResolvedDependency(
                group = "com.example",
                artifact = "root-b",
                version = "1.0.0",
                scope = "runtime",
                direct = true,
                dependencies = listOf("com.example:shared-lib"),
            ),
            ResolvedDependency(
                group = "com.example",
                artifact = "shared-lib",
                version = "2.0.0",
                scope = "runtime",
                direct = false,
                dependencies = listOf("com.example:leaf"),
            ),
            ResolvedDependency(
                group = "com.example",
                artifact = "leaf",
                version = "1.0.0",
                scope = "runtime",
                direct = false,
                dependencies = emptyList(),
            ),
        )

        val output = DependencyTreeRenderer.render(
            projectName = "test-proj",
            projectVersion = "0.1.0",
            projectPath = "/test",
            packages = packages,
            scope = "runtime",
        )

        assertContains(output, "com.example:shared-lib:2.0.0 (*)")
    }

    @Test
    fun `render filters by scope`() {
        val packages = listOf(
            ResolvedDependency(
                group = "org.jetbrains.kotlinx",
                artifact = "kotlinx-coroutines-core",
                version = "1.10.2",
                scope = "runtime",
                direct = true,
                dependencies = emptyList(),
            ),
            ResolvedDependency(
                group = "org.junit.jupiter",
                artifact = "junit-jupiter-api",
                version = "5.10.1",
                scope = "test",
                direct = true,
                dependencies = emptyList(),
            ),
        )

        val runtimeOutput = DependencyTreeRenderer.render(
            projectName = "test-proj",
            projectVersion = "0.1.0",
            projectPath = "/test",
            packages = packages,
            scope = "runtime",
        )

        assertContains(runtimeOutput, "kotlinx-coroutines-core:1.10.2")
        assertFalse(runtimeOutput.contains("junit-jupiter-api"))

        val testOutput = DependencyTreeRenderer.render(
            projectName = "test-proj",
            projectVersion = "0.1.0",
            projectPath = "/test",
            packages = packages,
            scope = "test",
        )

        assertContains(testOutput, "junit-jupiter-api:5.10.1")
        assertFalse(testOutput.contains("kotlinx-coroutines-core"))
    }

    @Test
    fun `render respects maxDepth`() {
        val packages = listOf(
            ResolvedDependency(
                group = "com.example",
                artifact = "direct-dep",
                version = "1.0.0",
                scope = "runtime",
                direct = true,
                dependencies = listOf("com.example:transitive-child"),
            ),
            ResolvedDependency(
                group = "com.example",
                artifact = "transitive-child",
                version = "1.0.0",
                scope = "runtime",
                direct = false,
                dependencies = listOf("com.example:transitive-grandchild"),
            ),
            ResolvedDependency(
                group = "com.example",
                artifact = "transitive-grandchild",
                version = "1.0.0",
                scope = "runtime",
                direct = false,
                dependencies = emptyList(),
            ),
        )

        val depth1Output = DependencyTreeRenderer.render(
            projectName = "test-proj",
            projectVersion = "0.1.0",
            projectPath = "/test",
            packages = packages,
            scope = "runtime",
            maxDepth = 1,
        )

        assertContains(depth1Output, "com.example:direct-dep:1.0.0")
        assertFalse(depth1Output.contains("com.example:transitive-child"))
    }
}
