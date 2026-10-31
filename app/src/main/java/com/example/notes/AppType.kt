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
