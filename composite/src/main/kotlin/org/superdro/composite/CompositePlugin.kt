package org.superdro.composite

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The plugin `composite`. It applies nothing. It puts the helpers of this build logic on the
 * build script class path: [org.superdro.composite.util.configureOptIns],
 * [org.superdro.composite.util.AndroidSdk], the version helpers, and
 * [org.superdro.composite.task.LocalizationCheckTask].
 */
class CompositePlugin : Plugin<Project> {
    override fun apply(target: Project) {
    }
}
