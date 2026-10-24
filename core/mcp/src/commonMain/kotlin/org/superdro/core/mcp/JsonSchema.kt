package org.superdro.core.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the JSON Schema (draft 2020-12 subset) of an object:
 *
 * ```
 * objectSchema {
 *     string("machine", description = "The profile ID or the name")
 *     number("x", required = true, minimum = -1e9, maximum = 1e9)
 *     enumString("units", listOf("mm", "inch", "um"))
 *     array("tags", items = stringSchema())
 *     obj("limits") { integer("maxEntities", minimum = 1.0) }
 * }
 * ```
 *
 * [additionalProperties] false (the default) refuses an argument that the schema does not name.
 */
fun objectSchema(additionalProperties: Boolean = false, description: String? = null, build: ObjectSchemaBuilder.() -> Unit = {}): JsonObject =
    ObjectSchemaBuilder().apply(build).build(additionalProperties, description)

/** The schema `{"type": "string"}` with the options. */
fun stringSchema(description: String? = null, minLength: Int? = null, maxLength: Int? = null, pattern: String? = null): JsonObject =
    buildJsonObject {
        put("type", "string")
        if (description != null) put("description", description)
        if (minLength != null) put("minLength", minLength)
        if (maxLength != null) put("maxLength", maxLength)
        if (pattern != null) put("pattern", pattern)
    }

/** The schema `{"type": "number"}` with the limits. */
fun numberSchema(description: String? = null, minimum: Double? = null, maximum: Double? = null): JsonObject =
    buildJsonObject {
        put("type", "number")
        if (description != null) put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }

/** The schema `{"type": "integer"}` with the limits. */
fun integerSchema(description: String? = null, minimum: Long? = null, maximum: Long? = null): JsonObject =
    buildJsonObject {
        put("type", "integer")
        if (description != null) put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }

/** The schema `{"type": "boolean"}`. */
fun booleanSchema(description: String? = null, default: Boolean? = null): JsonObject =
    buildJsonObject {
        put("type", "boolean")
        if (description != null) put("description", description)
        if (default != null) put("default", default)
    }

/** The schema `{"type": "string", "enum": [...]}`. */
fun enumSchema(values: List<String>, description: String? = null): JsonObject =
    buildJsonObject {
        put("type", "string")
        if (description != null) put("description", description)
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

/** The schema `{"type": "array", "items": ...}` with the limits. */
fun arraySchema(items: JsonObject, description: String? = null, minItems: Int? = null, maxItems: Int? = null): JsonObject =
    buildJsonObject {
        put("type", "array")
        if (description != null) put("description", description)
        put("items", items)
        if (minItems != null) put("minItems", minItems)
        if (maxItems != null) put("maxItems", maxItems)
    }

/** The properties of one object schema. See [objectSchema]. */
class ObjectSchemaBuilder {
    private val properties = linkedMapOf<String, JsonElement>()
    private val required = mutableListOf<String>()

    /** Adds a property with a ready schema. */
    fun property(name: String, schema: JsonObject, required: Boolean = false) {
        require(name !in properties) { "The property \"$name\" is in the schema two times." }
        properties[name] = schema
        if (required) this.required += name
    }

    fun string(name: String, required: Boolean = false, description: String? = null, minLength: Int? = null, maxLength: Int? = null, pattern: String? = null) =
        property(name, stringSchema(description, minLength, maxLength, pattern), required)

    fun number(name: String, required: Boolean = false, description: String? = null, minimum: Double? = null, maximum: Double? = null) =
        property(name, numberSchema(description, minimum, maximum), required)

    fun integer(name: String, required: Boolean = false, description: String? = null, minimum: Long? = null, maximum: Long? = null) =
        property(name, integerSchema(description, minimum, maximum), required)

    fun boolean(name: String, required: Boolean = false, description: String? = null, default: Boolean? = null) =
        property(name, booleanSchema(description, default), required)

    fun enumString(name: String, values: List<String>, required: Boolean = false, description: String? = null) =
        property(name, enumSchema(values, description), required)

    fun array(name: String, items: JsonObject, required: Boolean = false, description: String? = null, minItems: Int? = null, maxItems: Int? = null) =
        property(name, arraySchema(items, description, minItems, maxItems), required)

    /** Adds an object property with its own properties. */
    fun obj(name: String, required: Boolean = false, description: String? = null, additionalProperties: Boolean = false, build: ObjectSchemaBuilder.() -> Unit) =
        property(name, objectSchema(additionalProperties, description, build), required)

    fun build(additionalProperties: Boolean, description: String?): JsonObject = buildJsonObject {
        put("type", "object")
        if (description != null) put("description", description)
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        if (!additionalProperties) put("additionalProperties", false)
    }
}
