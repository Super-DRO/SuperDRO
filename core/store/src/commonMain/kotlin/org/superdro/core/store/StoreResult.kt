package org.superdro.core.store

/**
 * A rule of the store that refuses a write. [message] is the English text.
 * [stringKey] is the key of the same text in `core:resources`
 * (`values/strings_store.xml`), so that the UI shows the text in the language of the user.
 */
enum class StoreRefusal(val stringKey: String, val message: String) {
    /** A profile name with no character other than spaces. */
    ProfileNameBlank("store_profile_name_blank", "Profile name can't be blank."),

    /** A profile name that a different profile has. */
    ProfileNameExists("store_profile_name_exists", "A profile with this name already exists."),

    /** A workspace name with no character other than spaces. */
    WorkspaceNameBlank("store_workspace_name_blank", "Workspace name can't be blank."),

    /** A workspace name that a different workspace of the profile has. */
    WorkspaceNameExists("store_workspace_name_exists", "A workspace with this name already exists."),

    /** The delete of the last workspace of a profile. */
    LastWorkspace("store_last_workspace", "The last workspace can't be deleted."),

    /** An adapter alias with no character other than spaces. */
    AdapterAliasBlank("store_adapter_alias_blank", "Adapter name can't be blank."),

    /** An adapter alias with more than 40 characters. */
    AdapterAliasTooLong("store_adapter_alias_too_long", "An adapter name can have a maximum of 40 characters."),

    /** An adapter alias that a different adapter profile has. */
    AdapterAliasExists("store_adapter_alias_exists", "An adapter with this name already exists."),

    /** A change of a standard tip. A delete of a standard tip is permitted. */
    StandardTipReadOnly("store_standard_tip_read_only", "A standard tip can't be changed."),

    /** An ID that the store does not have. */
    NotFound("store_not_found", "The item is not in the store."),
}

/** The result of a write that a rule of the store can refuse. */
sealed interface StoreResult<out T> {

    /** The store wrote the data. [value] is the stored item. */
    data class Ok<T>(val value: T) : StoreResult<T>

    /** The store refused the write and did not change the data. */
    data class Refused(val refusal: StoreRefusal) : StoreResult<Nothing>
}

/** The value of an [StoreResult.Ok], or null for a [StoreResult.Refused]. */
fun <T> StoreResult<T>.valueOrNull(): T? = (this as? StoreResult.Ok<T>)?.value

/** The refusal of a [StoreResult.Refused], or null for a [StoreResult.Ok]. */
fun StoreResult<*>.refusalOrNull(): StoreRefusal? = (this as? StoreResult.Refused)?.refusal

/** The text that the question of a profile delete shows. `MachineRepository.deleteProfile` takes the answer. */
const val DELETE_PROFILE_TOOLS_QUESTION: String = "Delete the tools of this profile too?"

/** The key of [DELETE_PROFILE_TOOLS_QUESTION] in `core:resources`. */
const val DELETE_PROFILE_TOOLS_QUESTION_KEY: String = "store_delete_profile_tools_question"
