package dev.qutivex.engine.pom

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PomParserTest {

    private val parser = PomParser()

    @Test
    fun `parses basic POM with dependencies and properties`() {
        val xml = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
                <properties>
                    <kotlin.version>2.1.0</kotlin.version>
                    <junit.version>5.12.2</junit.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-stdlib</artifactId>
                        <version>${'$'}{kotlin.version}</version>
                        <scope>compile</scope>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>${'$'}{junit.version}</version>
                        <scope>test</scope>
                        <exclusions>
                            <exclusion>
                                <groupId>org.apiguardian</groupId>
                                <artifactId>apiguardian-api</artifactId>
                            </exclusion>
                        </exclusions>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = parser.parse(xml)
        assertEquals("com.example", pom.group)
        assertEquals("my-app", pom.artifact)
        assertEquals("1.0.0", pom.version)
        assertEquals(2, pom.dependencies.size)

        val interpolated = parser.interpolate(pom)
        val stdlib = interpolated.dependencies.first { it.artifact == "kotlin-stdlib" }
        assertEquals("2.1.0", stdlib.version)
        assertEquals("compile", stdlib.scope)

        val junit = interpolated.dependencies.first { it.artifact == "junit-jupiter" }
        assertEquals("5.12.2", junit.version)
        assertEquals("test", junit.scope)
        assertEquals(1, junit.exclusions.size)
        assertEquals("org.apiguardian", junit.exclusions[0].group)
    }

    @Test
    fun `inherits group and version from parent POM`() {
        val xml = """
            <project>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.0</version>
                </parent>
                <artifactId>custom-starter</artifactId>
            </project>
        """.trimIndent()

        val pom = parser.parse(xml)
        assertEquals("org.springframework.boot", pom.group)
        assertEquals("custom-starter", pom.artifact)
        assertEquals("3.2.0", pom.version)
        assertNotNull(pom.parent)
        assertEquals("spring-boot-starter-parent", pom.parent.artifact)
    }

    @Test
    fun `handles cyclic properties without infinite loops`() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>cycle-test</artifactId>
                <version>1.0.0</version>
                <properties>
                    <a>${'$'}{b}</a>
                    <b>${'$'}{a}</b>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.test</groupId>
                        <artifactId>foo</artifactId>
                        <version>${'$'}{a}</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pom = parser.parse(xml)
        val interpolated = parser.interpolate(pom)
        // Should terminate cleanly
        assertNotNull(interpolated)
    }

    @Test
    fun `parses dependencyManagement and imported BOMs`() {
        val xml = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>bom-consumer</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit</groupId>
                            <artifactId>junit-bom</artifactId>
                            <version>5.12.2</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val pom = parser.parse(xml)
        assertEquals(1, pom.dependencyManagement.size)
        val bom = pom.dependencyManagement[0]
        assertEquals("org.junit", bom.group)
        assertEquals("junit-bom", bom.artifact)
        assertEquals("5.12.2", bom.version)
        assertEquals("pom", bom.type)
        assertEquals("import", bom.scope)
    }
}
