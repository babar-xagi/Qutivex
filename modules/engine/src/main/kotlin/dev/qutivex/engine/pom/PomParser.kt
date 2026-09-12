package dev.qutivex.engine.pom

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

class PomParser {

    private val documentBuilderFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        try {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        } catch (_: Exception) {}
    }

    fun parse(xmlContent: String): PomModel {
        return parse(ByteArrayInputStream(xmlContent.toByteArray(StandardCharsets.UTF_8)))
    }

    fun parse(inputStream: InputStream): PomModel {
        val builder = documentBuilderFactory.newDocumentBuilder()
        val doc = builder.parse(inputStream)
        val projectElement = doc.documentElement

        // 1. Parent
        val parent = parseParent(projectElement)

        // 2. Direct metadata
        val directGroup = getChildText(projectElement, "groupId")
        val group = directGroup ?: parent?.group ?: ""
        val artifact = getChildText(projectElement, "artifactId") ?: ""
        val directVersion = getChildText(projectElement, "version")
        val version = directVersion ?: parent?.version ?: ""
        val packaging = getChildText(projectElement, "packaging") ?: "jar"

        // 3. Properties
        val properties = parseProperties(projectElement)

        // 4. DependencyManagement
        val dependencyManagement = parseDependencyManagement(projectElement)

        // 5. Dependencies
        val dependencies = parseDependencies(projectElement)

        return PomModel(
            group = group,
            artifact = artifact,
            version = version,
            packaging = packaging,
            parent = parent,
            properties = properties,
            dependencyManagement = dependencyManagement,
            dependencies = dependencies,
        )
    }

    fun interpolate(pom: PomModel, externalProperties: Map<String, String> = emptyMap()): PomModel {
        val allProps = mutableMapOf<String, String>()

        // Built-in standard properties
        allProps["project.groupId"] = pom.group
        allProps["pom.groupId"] = pom.group
        allProps["groupId"] = pom.group

        allProps["project.artifactId"] = pom.artifact
        allProps["pom.artifactId"] = pom.artifact
        allProps["artifactId"] = pom.artifact

        allProps["project.version"] = pom.version
        allProps["pom.version"] = pom.version
        allProps["version"] = pom.version

        allProps["project.packaging"] = pom.packaging
        allProps["pom.packaging"] = pom.packaging
        allProps["packaging"] = pom.packaging

        if (pom.parent != null) {
            allProps["project.parent.groupId"] = pom.parent.group
            allProps["project.parent.artifactId"] = pom.parent.artifact
            allProps["project.parent.version"] = pom.parent.version
        }

        // Add external properties first, then pom properties so pom properties override external
        allProps.putAll(externalProperties)
        allProps.putAll(pom.properties)

        val pattern = Regex("""\$\{([^}]+)\}""")

        fun expand(raw: String?, seen: Set<String> = emptySet()): String? {
            if (raw == null || !raw.contains("\${")) return raw
            var current: String = raw
            var changed = true
            var iterations = 0
            while (changed && iterations < 15) {
                changed = false
                iterations++
                current = pattern.replace(current) { match ->
                    val key = match.groupValues[1].trim()
                    if (key in seen) {
                        match.value // cycle detected, preserve
                    } else {
                        val replacement = allProps[key]
                        if (replacement != null) {
                            changed = true
                            expand(replacement, seen + key) ?: match.value
                        } else {
                            match.value
                        }
                    }
                }
            }
            return current
        }

        val interpolatedGroup = expand(pom.group) ?: pom.group
        val interpolatedArtifact = expand(pom.artifact) ?: pom.artifact
        val interpolatedVersion = expand(pom.version) ?: pom.version
        val interpolatedPackaging = expand(pom.packaging) ?: pom.packaging

        val interpolatedProps = pom.properties.mapValues { (_, v) -> expand(v) ?: v }

        fun interpolateDep(dep: PomDependency): PomDependency {
            return dep.copy(
                group = expand(dep.group) ?: dep.group,
                artifact = expand(dep.artifact) ?: dep.artifact,
                version = expand(dep.version),
                scope = expand(dep.scope) ?: dep.scope,
                type = expand(dep.type) ?: dep.type,
                classifier = expand(dep.classifier),
                exclusions = dep.exclusions.map { ex ->
                    ex.copy(
                        group = expand(ex.group) ?: ex.group,
                        artifact = expand(ex.artifact) ?: ex.artifact,
                    )
                }
            )
        }

        return pom.copy(
            group = interpolatedGroup,
            artifact = interpolatedArtifact,
            version = interpolatedVersion,
            packaging = interpolatedPackaging,
            properties = interpolatedProps,
            dependencyManagement = pom.dependencyManagement.map(::interpolateDep),
            dependencies = pom.dependencies.map(::interpolateDep),
        )
    }

    private fun parseParent(project: Element): PomParent? {
        val parentElement = getChildElement(project, "parent") ?: return null
        val g = getChildText(parentElement, "groupId") ?: return null
        val a = getChildText(parentElement, "artifactId") ?: return null
        val v = getChildText(parentElement, "version") ?: return null
        val rel = getChildText(parentElement, "relativePath")
        return PomParent(group = g, artifact = a, version = v, relativePath = rel)
    }

    private fun parseProperties(project: Element): Map<String, String> {
        val propertiesElement = getChildElement(project, "properties") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        val children = propertiesElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                result[node.nodeName] = node.textContent.trim()
            }
        }
        return result
    }

    private fun parseDependencyManagement(project: Element): List<PomDependency> {
        val depMgmtElement = getChildElement(project, "dependencyManagement") ?: return emptyList()
        val depsElement = getChildElement(depMgmtElement, "dependencies") ?: return emptyList()
        return parseDependencyList(depsElement)
    }

    private fun parseDependencies(project: Element): List<PomDependency> {
        val depsElement = getChildElement(project, "dependencies") ?: return emptyList()
        return parseDependencyList(depsElement)
    }

    private fun parseDependencyList(dependenciesElement: Element): List<PomDependency> {
        val list = mutableListOf<PomDependency>()
        val children = dependenciesElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == "dependency") {
                val depElem = node as Element
                val g = getChildText(depElem, "groupId") ?: continue
                val a = getChildText(depElem, "artifactId") ?: continue
                val v = getChildText(depElem, "version")
                val scope = getChildText(depElem, "scope") ?: "compile"
                val optText = getChildText(depElem, "optional")
                val optional = optText.equals("true", ignoreCase = true)
                val type = getChildText(depElem, "type") ?: "jar"
                val classifier = getChildText(depElem, "classifier")
                val exclusions = parseExclusions(depElem)

                list.add(
                    PomDependency(
                        group = g,
                        artifact = a,
                        version = v,
                        scope = scope,
                        optional = optional,
                        type = type,
                        classifier = classifier,
                        exclusions = exclusions,
                    )
                )
            }
        }
        return list
    }

    private fun parseExclusions(depElement: Element): List<PomExclusion> {
        val exclusionsElement = getChildElement(depElement, "exclusions") ?: return emptyList()
        val list = mutableListOf<PomExclusion>()
        val children = exclusionsElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == "exclusion") {
                val exElem = node as Element
                val g = getChildText(exElem, "groupId") ?: continue
                val a = getChildText(exElem, "artifactId") ?: continue
                list.add(PomExclusion(group = g, artifact = a))
            }
        }
        return list
    }

    private fun getChildElement(parent: Element, tagName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tagName) {
                return node as Element
            }
        }
        return null
    }

    private fun getChildText(parent: Element, tagName: String): String? {
        val element = getChildElement(parent, tagName) ?: return null
        return element.textContent.trim().ifEmpty { null }
    }
}
