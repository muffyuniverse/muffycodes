package com.example.notes

import org.json.JSONObject

/**
 * What a project is for, **which is what decides what the app offers it.**
 *
 * ## Why this is a field rather than a pile of conditions
 *
 * A signing key, an environment, a release job and an installable artifact are all things an *Android
 * app* needs. A web app needs none of them and has a domain instead. A script needs none of them at
 * all. Without this field the app would either offer a keystore to a shell script — nonsense a person
 * has to work out for themselves — or hide the release path from an Android app, which is worse.
 *
 * So the type is asked once, when the project is made, and every feature that depends on it reads the
 * answer instead of guessing.
 *
 * ## Why it can change
 *
 * A project starts as a script and grows into an app; a web page grows a build step. If the type were
 * fixed at creation the app would be permanently wrong about a project it no longer understands, and
 * the person's only remedy would be to make a new project and move everything. So it is a setting, and
 * changing it re-runs the setup the new type needs.
 *
 * ## What it is not
 *
 * **It is not a claim about the code.** The app does not read a project and decide it is an Android
 * app; it asks, and it believes the answer. A type that disagrees with the folder is a person's
 * decision to change, not a thing to be detected and corrected.
 */
enum class AppType(
    /** The word stored in the project's metadata. **Never the display name** — see [parse]. */
    val id: String,
    /** What a person reads in the picker. */
    val label: String,
    /** One line under the label, saying what the choice changes. */
    val blurb: String,
    /** True when this kind of app is built into an installable package that must be signed. */
    val signed: Boolean,
) {
    ANDROID(
        id = "android",
        label = "Android app",
        blurb = "Builds an APK you can install. Needs a signing key, which the app keeps for you.",
        signed = true,
    ),
    WEB(
        id = "web",
        label = "Web app",
        blurb = "Builds a site and publishes it. Nothing to sign.",
        signed = false,
    ),
    PLAIN(
        id = "plain",
        label = "Program or script",
        blurb = "Builds and tests. Nothing to sign, and no release to publish.",
        signed = false,
    ),
    NONE(
        id = "none",
        label = "Just files",
        blurb = "No build and no workflow. For notes, documents, or a folder you keep by hand.",
        signed = false,
    ),
    ;

    companion object {
        /**
         * The type a project with no recorded answer gets.
         *
         * **`ANDROID`, because every project made before this field existed was one.** The app has
         * only ever built Android apps, so a project with no answer is an Android app that has not
         * been asked about yet — and defaulting to [NONE] would quietly take the release path away
         * from every existing project, which is the worse mistake.
         */
        val DEFAULT = ANDROID

        /**
         * The type [id] names, or null when it names none.
         *
         * **Unknown is null rather than a default.** A metadata file written by a newer version of the
         * app may name a type this build has never heard of, and answering with [DEFAULT] would tell
         * the person their project is an Android app when they said it was something else. Null lets
         * the caller say "this app does not know that type" instead.
         */
        fun parse(id: String?): AppType? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

        /** The type for [id], or [DEFAULT] when it is missing or unrecognised. */
        fun or(id: String?): AppType = parse(id) ?: DEFAULT

        /** True when [id] names a type this build understands — for a file written by a newer app. */
        fun known(id: String?): Boolean = parse(id) != null
    }
}

/**
 * Copying a type's preset into a project — **the act that makes a preset a preset rather than a lock-in.**
 *
 * ## What it copies
 *
 * The type's workflow (into `.github/workflows/`) and its prompt (into the project's own
 * `project_prompt.txt`). Both are ordinary files in the project afterwards: editable, lockable, and
 * theirs.
 *
 * ## What it will not do
 *
 * **It never overwrites, and it never deletes.** A person who has adapted a workflow to their project,
 * or a prompt to their codebase, would lose it if a type change tidied up after itself — and a project
 * that changes type is a project somebody has already worked in. So an existing file is left exactly
 * as it is, and the old type's files stay. The metadata records what was installed, which is how the
 * app can later say "this project still has an android.yml from when it was an Android app" rather
 * than pretending it never happened.
 *
 * That is the whole difference between a preset and a template: a preset is something you start from,
 * and starting from it does not entitle anybody to take it back.
 */
object Presets {

    /** What a copy did, in words a screen or a tool can show. */
    data class Copied(
        val type: AppType,
        /** Files that were written, relative to the project. */
        val written: List<String>,
        /** Files that were already there, and were left alone. */
        val kept: List<String>,
    ) {
        /** True when there was something to copy and none of it was already there. */
        val fresh: Boolean get() = written.isNotEmpty() && kept.isEmpty()

        fun said(): String = when {
            written.isEmpty() && kept.isEmpty() -> "There was nothing to copy for a ${type.label}."
            written.isEmpty() -> "Everything a ${type.label} starts with was already here."
            kept.isEmpty() -> "Added ${written.joinToString(", ")} from the ${type.label} preset."
            else -> "Added ${written.joinToString(", ")} from the ${type.label} preset, and left " +
                "${kept.joinToString(", ")} as it was."
        }
    }

    /**
     * Copies [type]'s preset into [projectDir], leaving anything that already exists.
     *
     * [accountDir] is where the system project lives. A preset that is not there is not an error: a
     * person may have deleted it, and a project that keeps working without one is the right outcome.
     */
    fun copy(accountDir: File, projectDir: File, type: AppType): Copied {
        val root = File(File(File(accountDir, Workspace.SYSTEM_PROJECT), Workspace.TYPES), type.id)
        val written = mutableListOf<String>()
        val kept = mutableListOf<String>()

        val name = WorkflowTemplates.forType(type)
        if (name != null) {
            val from = File(File(root, Workspace.TEMPLATES), name)
            val to = File(File(File(projectDir, ".github"), "workflows"), name)
            if (to.isFile) {
                kept.add(relative(projectDir, to))
            } else if (from.isFile) {
                try {
                    to.parentFile?.mkdirs()
                    to.writeBytes(from.readBytes())
                    written.add(relative(projectDir, to))
                } catch (e: Throwable) {
                    // A file that cannot be copied is reported by what did not appear, which is the
                    // honest answer: the list of what was written does not contain it.
                }
            }
        }

        val promptFrom = File(root, AgentPrompt.PRESET_FILE)
        val promptTo = File(projectDir, AgentPrompt.PROJECT_FILE)
        if (promptTo.isFile) {
            kept.add(AgentPrompt.PROJECT_FILE)
        } else if (promptFrom.isFile) {
            try {
                promptTo.writeBytes(promptFrom.readBytes())
                written.add(AgentPrompt.PROJECT_FILE)
            } catch (e: Throwable) {
                // As above.
            }
        }

        // **The record, written even when nothing was copied** — so the app can say which type a
        // project was set up as, and a later version can tell an old preset from a fresh one.
        ProjectMeta.write(
            projectDir,
            ProjectMeta(type, template = name),
        )
        return Copied(type, written, kept)
    }

    private fun relative(projectDir: File, file: File): String = try {
        file.relativeTo(projectDir).invariantSeparatorsPath
    } catch (e: Throwable) {
        file.name
    }
}

/**
 * A project's own metadata, in `.muffy/project.json`.
 *
 * **Inside the project's folder, not the account's**, because it describes the project and should
 * travel with it: a folder copied to another phone, or pulled from GitHub, arrives with its own answer
 * about what it is. That is also why it is a dot-folder the app already excludes from commits.
 */
data class ProjectMeta(
    val type: AppType,
    /** The template this project's workflow came from, or null when it has none. */
    val template: String? = null,
) {
    /** The file's contents. */
    fun toJson(): String = JSONObject().apply {
        put("type", type.id)
        if (template != null) put("template", template)
    }.toString()

    companion object {
        /** The file, inside the project's `.muffy/`, which is already excluded from commits. */
        const val FILE = "project.json"

        /**
         * Reads a project's metadata, or answers [AppType.DEFAULT] when there is none.
         *
         * **A file that will not parse is not a reason to refuse the project.** A damaged metadata
         * file would then make the folder unopenable, and the folder is the person's work — the
         * metadata is the app's note about it. Unreadable reads as the default, and the next write
         * repairs it.
         */
        fun read(projectDir: java.io.File): ProjectMeta {
            val file = java.io.File(java.io.File(projectDir, Workspace.STATE_DIR), FILE)
            if (!file.isFile) return ProjectMeta(AppType.DEFAULT)
            return try {
                val json = JSONObject(file.readText())
                ProjectMeta(
                    // **A type this build does not know reads as the default for features**, while
                    // the raw id is kept so a newer app can still understand the file it wrote.
                    type = AppType.or(json.optString("type")),
                    template = json.optString("template").takeIf { it.isNotBlank() },
                )
            } catch (e: Throwable) {
                ProjectMeta(AppType.DEFAULT)
            }
        }

        /** Writes the metadata, making `.muffy/` if it is not there. */
        fun write(projectDir: java.io.File, meta: ProjectMeta): Boolean = try {
            val dir = java.io.File(projectDir, Workspace.STATE_DIR)
            dir.mkdirs()
            java.io.File(dir, FILE).writeText(meta.toJson())
            true
        } catch (e: Throwable) {
            false
        }
    }
}
