import org.yaml.snakeyaml.Yaml
import java.io.File
import java.security.MessageDigest

/**
 * The cross-module wiring contract, and the check that the committed registrations still match it.
 *
 * ## Why the descriptors are verified rather than generated
 *
 * The plan called for templates to *generate* the module descriptors. They are not, and the reason
 * is worth stating rather than leaving as a silent narrowing of the task.
 *
 * Those descriptors are mostly comments, and the comments carry more than the XML does: why
 * `runLineMarkerContributor` is backend-only, why `lang.fileViewProviderFactory` is deliberately
 * *not* re-registered, what `languageId` changes about `textDocument/didOpen`. Each was written
 * after a specific failure. A template reproducing them would be the same text somewhere less
 * discoverable; one dropping them would delete the reasoning and keep the syntax.
 *
 * The plan's own goal for this phase is "ownership consistency, not LOC". Checking delivers exactly
 * that without the loss: the manifest is authoritative, drift fails the build in *both* directions
 * — declared-but-unregistered and registered-but-undeclared — and the rationale stays where someone
 * editing a registration will actually read it.
 *
 * `docs/flix-integration-matrix.md` *is* generated, because it has no hand-written content to lose.
 *
 * ## Why a compiled class
 *
 * Gradle's configuration cache cannot serialize references to script objects, so a `doLast` calling
 * a script-level function captures the entire script and fails the build. Compiled into `buildSrc`,
 * this is an ordinary class the task can call with plain [File]s.
 */
object IntegrationGlue {

    private const val SUPPORTED_SCHEMA = 1
    private val SUPPORTED_BACKENDS = setOf("intellijJvm")

    private val KNOWN_TOP_LEVEL = setOf(
        "schema", "language", "registrations", "lsp4ij", "debugger", "actions", "excluded",
    )

    /** Every module that carries a content-module descriptor. */
    val MODULES = listOf("language", "backend", "debugger", "frontend", "shared")

    fun descriptorOf(root: File, module: String): File =
        File(root, "$module/src/main/resources/flix.jetbrains.plugin.$module.xml")

    fun manifestOf(root: File): File = File(root, "flix-integration.yaml")

    fun matrixOf(root: File): File = File(root, "docs/flix-integration-matrix.md")

    /**
     * Everything wrong with the committed wiring, or an empty list.
     *
     * Returns all problems rather than the first, so one run tells the whole story: these tend to
     * come in groups — a rename breaks the declaration, the registration and the matrix at once.
     */
    fun verify(root: File): List<String> {
        val problems = mutableListOf<String>()
        val manifest = load(manifestOf(root))

        val schema = manifest["schema"]
        if (schema != SUPPORTED_SCHEMA) {
            // Every check below assumes the shape this checker knows, so stop rather than report
            // a cascade of failures that are really one.
            return listOf("schema is $schema, this checker understands $SUPPORTED_SCHEMA")
        }
        (manifest.keys - KNOWN_TOP_LEVEL).forEach {
            problems += "unknown top-level key '$it' — a typo here would otherwise be ignored silently"
        }

        problems += verifyIdentity(root, manifest)
        problems += verifyRegistrations(root, manifest)
        problems += verifyDebugger(manifest)
        problems += verifyExclusions(root, manifest)
        return problems
    }

    @Suppress("UNCHECKED_CAST")
    private fun verifyRegistrations(root: File, manifest: Map<String, Any>): List<String> {
        val problems = mutableListOf<String>()
        val registrations = manifest["registrations"] as? Map<String, Map<String, String>> ?: emptyMap()
        val declaredIn = mutableMapOf<String, String>()

        registrations.forEach { (module, entries) ->
            val registered = registeredClasses(root, module)
            entries.forEach { (extensionPoint, fqcn) ->
                if (!sourceExists(root, fqcn)) {
                    problems += "$module: $extensionPoint names $fqcn, which has no source file"
                }
                if (fqcn !in registered) {
                    problems += "$module: $fqcn is declared for $extensionPoint but is not registered " +
                        "in ${descriptorOf(root, module).name}"
                }
                declaredIn.put(fqcn, module)?.let { previous ->
                    problems += "$fqcn is declared for both $previous and $module in the manifest"
                }
            }
        }

        // Ownership is per module, not global.
        //
        // Comparing against a flat set of every declared class was wrong in the one way that
        // mattered: a class declared under `backend` was subtracted out of `language`'s check too,
        // so registering it in *both* modules -- precisely the defect that once put two run arrows
        // in the gutter -- passed silently. Verified by fault injection: adding the gutter marker to
        // language.xml while it stayed declared under backend produced no complaint at all.
        val lsp4ij = manifest["lsp4ij"] as? Map<String, Any> ?: emptyMap()
        val reused = (lsp4ij["reusedFeatures"] as? Map<String, String>).orEmpty()
        val reusedOwner = (lsp4ij["reusedFeaturesModule"] as? String) ?: "backend"

        // Actions are registered with `class=`, which the widened scan now sees, so their classes
        // are owned like any other registration. The owning module is found rather than assumed:
        // the Tools > Flix task group is registered from `language`, because running a subcommand
        // needs neither LSP4IJ nor the Java plugin. Assuming `backend` here reported that correct
        // registration as a duplicate.
        (manifest["actions"] as? Map<String, String>).orEmpty().forEach { (id, fqcn) ->
            moduleRegisteringAction(root, id)?.let { declaredIn.putIfAbsent(fqcn, it) }
        }

        MODULES.forEach { module ->
            registeredClasses(root, module).forEach { fqcn ->
                val owner = declaredIn[fqcn]
                when {
                    owner == module -> Unit
                    owner != null ->
                        problems += "$module registers $fqcn, which the manifest assigns to $owner " +
                            "-- one class registered in two modules runs twice"
                    fqcn in reused.values && module == reusedOwner -> Unit
                    else ->
                        problems += "$module registers $fqcn, which the manifest does not declare " +
                            "-- declare it, or remove the registration"
                }
            }
        }

        // The other direction for reused features, which previously had none: they were only ever
        // added to the "known" set to suppress false positives, so deleting one of these
        // registrations while leaving it declared was invisible.
        val reusedRegistered = registeredClasses(root, reusedOwner)
        reused.forEach { (extensionPoint, fqcn) ->
            if (fqcn !in reusedRegistered) {
                problems += "$reusedOwner: $fqcn is declared as a reused LSP4IJ feature for " +
                    "$extensionPoint but is not registered"
            }
        }
        return problems
    }

    /**
     * The language and server identities, which were previously declared but never checked.
     *
     * These are the values other tools key on -- the file extension, the `languageId` sent in
     * `textDocument/didOpen`, the server id a mapping points at. Renaming one in the descriptor
     * while the manifest kept the old value used to pass, which made the manifest a description of
     * intent rather than of fact.
     */
    @Suppress("UNCHECKED_CAST")
    private fun verifyIdentity(root: File, manifest: Map<String, Any>): List<String> {
        val problems = mutableListOf<String>()
        val language = manifest["language"] as? Map<String, Any> ?: emptyMap()
        val languageXml = descriptorOf(root, "language").takeIf { it.exists() }?.readText().orEmpty()
        val backendXml = descriptorOf(root, "backend").takeIf { it.exists() }?.readText().orEmpty()

        (language["implementation"] as? String)?.let { fqcn ->
            if (!sourceExists(root, fqcn)) problems += "language.implementation $fqcn has no source file"
        }
        (language["extension"] as? String)?.let { extension ->
            if (!Regex("""extensions="$extension"""").containsMatchIn(languageXml)) {
                problems += "language.extension is '$extension' but no fileType registers it"
            }
        }
        (language["id"] as? String)?.let { id ->
            if (!Regex("""language="$id"""").containsMatchIn(languageXml)) {
                problems += "language.id is '$id' but the language module registers nothing for it"
            }
        }

        val lsp4ij = manifest["lsp4ij"] as? Map<String, Any> ?: emptyMap()
        (lsp4ij["languageServerId"] as? String)?.let { id ->
            if (!Regex("""id="$id"""").containsMatchIn(backendXml)) {
                problems += "lsp4ij.languageServerId '$id' is not the id of any registered server"
            }
            if (!Regex("""serverId="$id"""").containsMatchIn(backendXml)) {
                problems += "lsp4ij.languageServerId '$id' has no mapping pointing at it"
            }
        }

        (manifest["actions"] as? Map<String, String>).orEmpty().forEach { (id, fqcn) ->
            if (moduleRegisteringAction(root, id) == null) {
                problems += "action '$id' is declared but not registered"
            }
            if (!sourceExists(root, fqcn)) problems += "action '$id' names $fqcn, which has no source file"
        }
        return problems
    }

    @Suppress("UNCHECKED_CAST")
    private fun verifyDebugger(manifest: Map<String, Any>): List<String> {
        val problems = mutableListOf<String>()
        val debugger = manifest["debugger"] as? Map<String, Any> ?: emptyMap()

        if (debugger["backend"] !in SUPPORTED_BACKENDS) {
            problems += "debugger.backend '${debugger["backend"]}' is not supported " +
                "(expected one of $SUPPORTED_BACKENDS)"
        }
        if (debugger["setDefaultStratum"] != false) {
            problems += "debugger.setDefaultStratum must be false: it is process-global and would " +
                "corrupt every other language's position manager in the session"
        }
        if (debugger["claimedFileExtensions"] != listOf("flix")) {
            problems += "debugger.claimedFileExtensions must be exactly [flix]: claiming a foreign " +
                "extension makes this plugin answer for a language whose own plugin should"
        }
        return problems
    }

    @Suppress("UNCHECKED_CAST")
    private fun verifyExclusions(root: File, manifest: Map<String, Any>): List<String> {
        val problems = mutableListOf<String>()
        val debugger = manifest["debugger"] as? Map<String, Any> ?: emptyMap()
        val excluded = (manifest["excluded"] as? List<String>).orEmpty()

        val descriptors = MODULES
            .map { descriptorOf(root, it) }
            .filter { it.exists() }
            .joinToString("\n") { stripXmlComments(it.readText()) }

        if (debugger["backend"] == "intellijJvm") {
            if ("debugAdapterServer" in descriptors) {
                problems += "debugger.backend is intellijJvm but a debugAdapterServer is still " +
                    "registered — two debuggers cannot share one debuggee (ADR 0002)"
            }
            if (Regex("""fileNamePatternMapping[^>]*serverId="[^"]*[Dd]ebug""").containsMatchIn(descriptors)) {
                problems += "debugger.backend is intellijJvm but a DAP fileNamePatternMapping remains"
            }
        }
        if ("textMate" in excluded && "textmate" in descriptors.lowercase()) {
            problems += "textMate is excluded but a TextMate registration is present"
        }
        if ("nativeLsp" in excluded && "com.intellij.platform.lsp" in descriptors) {
            problems += "nativeLsp is excluded but IntelliJ's native LSP is registered alongside LSP4IJ"
        }

        // Grepped rather than trusted. The manifest flag is a declaration; this is the behaviour,
        // and the behaviour is what breaks other languages' position managers.
        if (sourceFiles(root).any { "setDefaultStratum" in stripCodeComments(it.readText()) }) {
            problems += "a source file calls setDefaultStratum — see debugger.setDefaultStratum"
        }
        return problems
    }

    // --- the generated matrix ----------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun renderMatrix(root: File): String {
        val manifestFile = manifestOf(root)
        val manifest = load(manifestFile)
        val language = manifest["language"] as Map<String, Any>
        val debugger = manifest["debugger"] as Map<String, Any>
        val lsp4ij = manifest["lsp4ij"] as Map<String, Any>
        val registrations = manifest["registrations"] as Map<String, Map<String, String>>

        return buildString {
            appendLine("<!-- Generated by `./gradlew generateIntegrationGlue`. Do not edit. -->")
            appendLine("<!-- schema ${manifest["schema"]}, manifest ${hashOf(manifestFile)} -->")
            appendLine()
            appendLine("# Flix integration matrix")
            appendLine()
            appendLine("What this plugin registers, and which module registers it. Generated from")
            appendLine("[`flix-integration.yaml`](../flix-integration.yaml).")
            appendLine("`./gradlew checkIntegrationGlue`, which runs as part of `check`, fails the build if")
            appendLine("the committed descriptors and this contract disagree in either direction.")
            appendLine()
            appendLine("## Language")
            appendLine()
            appendLine("| | |")
            appendLine("| --- | --- |")
            appendLine("| ID | `${language["id"]}` |")
            appendLine("| LSP `languageId` | `${language["languageId"]}` |")
            appendLine("| Extension | `.${language["extension"]}` |")
            appendLine("| Implementation | `${language["implementation"]}` |")
            appendLine()
            appendLine("Exactly one, per [ADR 0001](adr/0001-single-language-owner.md).")
            appendLine()
            appendLine("## Registrations")
            appendLine()
            registrations.forEach { (module, entries) ->
                appendLine("### `$module` module")
                appendLine()
                appendLine("| Extension point | Implementation |")
                appendLine("| --- | --- |")
                entries.toSortedMap().forEach { (ep, fqcn) -> appendLine("| `$ep` | `$fqcn` |") }
                appendLine()
            }
            appendLine("No class may appear twice. One registration in two modules means the extension")
            appendLine("runs twice — which is how the gutter arrow once appeared in duplicate.")
            appendLine()
            appendLine("## Debugging")
            appendLine()
            appendLine("| | |")
            appendLine("| --- | --- |")
            appendLine("| Backend | `${debugger["backend"]}` — IntelliJ's own JVM debugger |")
            appendLine("| SMAP stratum | `${debugger["stratum"]}` |")
            appendLine("| Sets VM default stratum | `${debugger["setDefaultStratum"]}` |")
            appendLine(
                "| Claimed extensions | " +
                    (debugger["claimedFileExtensions"] as List<*>).joinToString { "`.$it`" } + " |",
            )
            appendLine()
            appendLine("Per [ADR 0002](adr/0002-native-jvm-debugger.md), exactly one debugger owns the")
            appendLine("debuggee. `setDefaultStratum` is process-global and would corrupt every other")
            appendLine("language's position manager, so it is both declared false and grepped for.")
            appendLine()
            appendLine("## Language server")
            appendLine()
            appendLine("| | |")
            appendLine("| --- | --- |")
            appendLine("| Server ID | `${lsp4ij["languageServerId"]}` |")
            appendLine("| Mapping | `${lsp4ij["mapping"]}` |")
            appendLine()
            val reused = (lsp4ij["reusedFeatures"] as? Map<String, String>).orEmpty()
            if (reused.isNotEmpty()) {
                appendLine("LSP4IJ registers these features only for `TEXT` and `textmate`. Owning a real")
                appendLine("language silently takes them away, so the ones still wanted are re-registered")
                appendLine("against Flix. A per-feature judgement, not a blanket copy:")
                appendLine()
                appendLine("| Extension point | Implementation |")
                appendLine("| --- | --- |")
                reused.toSortedMap().forEach { (ep, fqcn) -> appendLine("| `$ep` | `$fqcn` |") }
                appendLine()
            }
            appendLine("## Excluded")
            appendLine()
            appendLine("Registrations that must not come back. Each was removed for a stated reason, and")
            appendLine("`checkIntegrationGlue` fails if one reappears:")
            appendLine()
            (manifest["excluded"] as List<String>).forEach { appendLine("- `$it`") }
        }
    }

    // --- plumbing ----------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun load(manifest: File): Map<String, Any> {
        require(manifest.exists()) { "${manifest.name} is missing" }
        return manifest.inputStream().use { Yaml().load(it) as Map<String, Any> }
    }

    private fun hashOf(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /**
     * Every class a module's descriptor names, whatever attribute names it.
     *
     * The three obvious attributes are not the whole set: this repository's own `<action>` element
     * uses `class=`, and `instance=`/`serviceImplementation=` are equally standard. Matching only
     * `implementation`/`implementationClass`/`factoryClass` made those registrations invisible to
     * the checker in *both* directions, so one added with `class=` would never be reported as
     * undeclared.
     *
     * Values are required to look like a fully-qualified name, because the widened attribute set
     * would otherwise sweep up things like `language="Flix"`.
     */
    /**
     * The module whose descriptor registers the action or group `id`, or `null` if none does.
     *
     * Searched rather than assumed. An action's module is a real choice -- the LSP command handlers
     * must be where LSP4IJ is, the task menu must be where it loads without it -- and hard-coding
     * one module made the other read as a duplicate registration.
     */
    private fun moduleRegisteringAction(root: File, id: String): String? =
        MODULES.firstOrNull { module ->
            val xml = descriptorOf(root, module).takeIf { it.exists() }?.readText().orEmpty()
            Regex("""id="$id"""").containsMatchIn(xml)
        }

    private fun registeredClasses(root: File, module: String): Set<String> {
        val file = descriptorOf(root, module)
        if (!file.exists()) return emptySet()
        return Regex(
            """(?:implementation|implementationClass|factoryClass|class|instance|""" +
                """serviceImplementation|serviceInterface)\s*="([^"]+)"""",
        )
            .findAll(stripXmlComments(file.readText()))
            .map { it.groupValues[1] }
            .filter { it.contains('.') && it.first().isLowerCase() }
            .toSet()
    }

    /**
     * The plugin's own Kotlin and Java sources.
     *
     * Scoped to the modules rather than walking the repository, which would also reach `buildSrc`
     * -- and this checker names `setDefaultStratum` in a string literal, so a whole-repository walk
     * reports the checker itself as the violation it is looking for.
     */
    private fun sourceFiles(root: File): Sequence<File> =
        (MODULES + ".").asSequence()
            .map { File(root, if (it == ".") "src" else "$it/src") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filterNot { it.invariantSeparatorsPath.contains("/build/") }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }

    /**
     * Whether [fqcn] is actually declared somewhere in its package.
     *
     * Looks for the declaration rather than for a file named after it: Kotlin puts several classes
     * in one file, and `FlixPositionManagerFactory` lives in `FlixPositionManager.kt`. A
     * file-name rule reports that as missing, which is a false alarm — and a checker that cries
     * wolf is one somebody eventually switches off.
     */
    private fun sourceExists(root: File, fqcn: String): Boolean {
        val simpleName = fqcn.substringAfterLast('.')
        val packagePath = fqcn.substringBeforeLast('.').replace('.', '/')
        val declaration = Regex("""\b(class|object|interface|enum\s+class|record)\s+$simpleName\b""")
        return sourceFiles(root)
            .filter { it.invariantSeparatorsPath.contains("/$packagePath/") }
            .any { declaration.containsMatchIn(stripCodeComments(it.readText())) }
    }

    /**
     * Source with comments removed.
     *
     * Every "is this registered / is this called" check below has to read code, not prose. The
     * descriptors and sources explain at length why TextMate was removed and why
     * `setDefaultStratum` must never be called — and a plain text search finds those explanations
     * and reports the very thing they promise is absent.
     */
    private fun stripCodeComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** Markup with XML comments removed; see [stripCodeComments]. */
    private fun stripXmlComments(text: String): String =
        text.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
}
