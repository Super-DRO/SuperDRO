package org.superdro.core.ui.settings.help

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_help
import org.superdro.core.resources.settings_help_add_machine_text
import org.superdro.core.resources.settings_help_add_machine_title
import org.superdro.core.resources.settings_help_adapter_link_text
import org.superdro.core.resources.settings_help_adapter_link_title
import org.superdro.core.resources.settings_help_app_settings_text
import org.superdro.core.resources.settings_help_app_settings_title
import org.superdro.core.resources.settings_help_inputs_text
import org.superdro.core.resources.settings_help_inputs_title
import org.superdro.core.resources.settings_help_key_mapping_text
import org.superdro.core.resources.settings_help_key_mapping_title
import org.superdro.core.resources.settings_help_logs_text
import org.superdro.core.resources.settings_help_logs_title
import org.superdro.core.resources.settings_help_machine_profiles_text
import org.superdro.core.resources.settings_help_machine_profiles_title
import org.superdro.core.resources.settings_help_machine_settings_text
import org.superdro.core.resources.settings_help_machine_settings_title
import org.superdro.core.resources.settings_help_machine_type_text
import org.superdro.core.resources.settings_help_machine_type_title
import org.superdro.core.resources.settings_help_of
import org.superdro.core.resources.settings_help_readout_text
import org.superdro.core.resources.settings_help_readout_title
import org.superdro.core.resources.settings_help_tachometer_text
import org.superdro.core.resources.settings_help_tachometer_title
import org.superdro.core.ui.theme.LocalTouchTargets

/**
 * The in-app help texts of the main screens. SuperDRO shows a short text of its own in the
 * app, with no external link. The about screen lists all topics.
 */
enum class HelpTopic(val title: StringResource, val text: StringResource) {
    Readout(Res.string.settings_help_readout_title, Res.string.settings_help_readout_text),
    AddMachine(Res.string.settings_help_add_machine_title, Res.string.settings_help_add_machine_text),
    MachineSettings(Res.string.settings_help_machine_settings_title, Res.string.settings_help_machine_settings_text),
    Inputs(Res.string.settings_help_inputs_title, Res.string.settings_help_inputs_text),
    Tachometer(Res.string.settings_help_tachometer_title, Res.string.settings_help_tachometer_text),
    AdapterLink(Res.string.settings_help_adapter_link_title, Res.string.settings_help_adapter_link_text),
    AppSettings(Res.string.settings_help_app_settings_title, Res.string.settings_help_app_settings_text),
    MachineProfiles(Res.string.settings_help_machine_profiles_title, Res.string.settings_help_machine_profiles_text),
    MachineType(Res.string.settings_help_machine_type_title, Res.string.settings_help_machine_type_text),
    KeyMapping(Res.string.settings_help_key_mapping_title, Res.string.settings_help_key_mapping_text),
    Logs(Res.string.settings_help_logs_title, Res.string.settings_help_logs_text),
}

/** The test tag of the help button of [topic]. */
fun helpButtonTag(topic: HelpTopic): String = "help:${topic.name}"

/**
 * Opens the help text of a topic in the help group of the about screen (the dialog rule: general
 * information shows in the screen, not in a dialog). The navigation host gives it.
 */
val LocalHelpOpener = staticCompositionLocalOf<((HelpTopic) -> Unit)?> { null }

/**
 * The help button of a screen: an icon button with the content description "Help: <topic>".
 * A tap opens the help text of [topic] in the about screen (the help group, with the text
 * in the pane at the right of the list). The button does not show when no [LocalHelpOpener] is
 * given.
 */
@Composable
fun HelpButton(topic: HelpTopic, modifier: Modifier = Modifier) {
    val opener = LocalHelpOpener.current ?: return
    val title = stringResource(topic.title)
    IconButton(onClick = { opener(topic) }, modifier = modifier.size(LocalTouchTargets.current.button).testTag(helpButtonTag(topic))) {
        Icon(painterResource(Res.drawable.ic_help), contentDescription = stringResource(Res.string.settings_help_of, title))
    }
}
