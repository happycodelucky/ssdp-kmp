/*
 * llms.txt (https://llmstxt.org) shipped INSIDE the published artifacts, so an
 * AI tool that has the dependency (in a Gradle cache, a sources jar, an AAR)
 * finds its documentation offline. `ssdp.publish` wires it:
 * each module generates its own pair from Dokka's Markdown output and packs it
 * into every jar/AAR it publishes, under META-INF/<groupId>/<artifactId>/.
 *
 *   llms.txt       the index: what the artifact is, its coordinates, and links
 *                  to llms-full.txt beside it and to the repository's README
 *                  (setup, platform notes) and CHANGELOG. ssdp-kmp has no docs
 *                  site, so the links point at GitHub.
 *   llms-full.txt  the module's full public API — every declaration with its
 *                  KDoc — as Markdown, for exactly the version it ships in.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.dokka.gradle.formats.DokkaFormatPlugin
import org.jetbrains.dokka.gradle.internal.InternalDokkaGradlePluginApi

/**
 * Dokka's Markdown (GFM) output format: adds the `dokkaGenerate*Markdown`
 * tasks. Dokka 2 ships the format as a plugin without a Gradle-side switch;
 * this is the registration its gfm-plugin README gives, including the
 * `@InternalDokkaGradlePluginApi` opt-in (re-check it on Dokka bumps).
 */
@OptIn(InternalDokkaGradlePluginApi::class)
abstract class DokkaMarkdownPlugin : DokkaFormatPlugin(formatName = "markdown") {
    override fun DokkaFormatPluginContext.configure() {
        project.dependencies {
            dokkaPlugin(dokka("gfm-plugin"))
            formatDependencies.dokkaPublicationPluginClasspathApiOnly.dependencies.addLater(
                dokka("gfm-template-processing-plugin"),
            )
        }
    }
}

/** Writes `llms.txt` and `llms-full.txt` for one module into [outputDirectory]. */
@CacheableTask
abstract class GenerateLlmsTxt : DefaultTask() {
    /** The module's Dokka Markdown publication (`dokkaGeneratePublicationMarkdown`). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiMarkdown: DirectoryProperty

    /** The artifact's POM name. */
    @get:Input
    abstract val title: Property<String>

    /** The artifact's POM description. */
    @get:Input
    abstract val summary: Property<String>

    /** `groupId:artifactId:version`. */
    @get:Input
    abstract val coordinates: Property<String>

    /** The GitHub repository, without a trailing slash. */
    @get:Input
    abstract val repoUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val repo = repoUrl.get()
        val header = "# ${title.get()}\n\n> ${summary.get()}\n\n" +
            "Kotlin Multiplatform artifact `${coordinates.get()}` (iOS, macOS, Android, JVM).\n"
        val out = outputDirectory.get().asFile
        out.mkdirs()
        out.resolve("llms.txt").writeText(
            header +
                "\n## API\n\n" +
                "- [Full public API](llms-full.txt): every public declaration of this artifact with its " +
                "documentation, for this version. It sits beside this file.\n" +
                "\n## Docs\n\n" +
                "- [README]($repo/blob/main/README.md): installation, usage, and per-platform setup " +
                "(iOS multicast entitlement, Android MulticastLock, macOS sandbox)\n" +
                "\n## Optional\n\n" +
                "- [Changelog]($repo/blob/main/CHANGELOG.md): every release, newest first\n" +
                "- [Source]($repo)\n",
        )
        out.resolve("llms-full.txt").writeText(header + "\n" + apiPages().joinToString("\n\n") + "\n")
    }

    /**
     * Every Markdown page Dokka wrote, depth-first with each directory's
     * index.md before its members. Links between pages become their text (in
     * one file the relative `.md` targets point nowhere), as do links into the
     * Kotlin API docs (`[String](https://kotlinlang.org/api/…)` on every type);
     * Dokka's HTML entities are decoded.
     */
    private fun apiPages(): List<String> {
        val root = apiMarkdown.get().asFile
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedWith(compareBy(PAGE_ORDER) { it.relativeTo(root).invariantSeparatorsPath.removeSuffix("index.md").split('/') })
            .map { clean(it.readText()) }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun clean(page: String): String =
        ENTITIES.entries.fold(page.replace(INTERNAL_LINK, "$1")) { text, (entity, char) -> text.replace(entity, char) }.trim()

    private companion object {
        /**
         * Path segments compared in turn. A directory's index.md (its path
         * minus "index.md") is a prefix of its members' paths, so sorts first.
         */
        val PAGE_ORDER = Comparator<List<String>> { a, b ->
            a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size)
        }

        /** `[text](target)` to another generated page (no scheme) or into the Kotlin API docs. */
        val INTERNAL_LINK = Regex("""\[([^\]]*)]\((?:https://kotlinlang\.org/api/|(?![a-zA-Z][a-zA-Z0-9+.-]*:))[^)]*\)""")

        /** `&amp;` last, so an escaped entity (`&amp;lt;`) decodes only once. */
        val ENTITIES = linkedMapOf("&quot;" to "\"", "&lt;" to "<", "&gt;" to ">", "&#39;" to "'", "&amp;" to "&")
    }
}
