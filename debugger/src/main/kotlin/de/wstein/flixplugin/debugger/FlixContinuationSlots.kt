package de.wstein.flixplugin.debugger

import com.sun.jdi.IntegerValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Reads the compiler-owned description of values saved in a suspended continuation. */
internal object FlixContinuationSlots {
    private const val FormatVersion = 1
    private const val PcField = "pc"
    private const val MetadataField = "frameSlots"
    private val GeneratedField = Regex("(?:clo|arg|l)\\d+")
    private val Kinds = setOf("capture", "parameter", "local")

    internal data class Slot(
        val field: String,
        val name: String,
        val type: String,
        val kind: String,
    )

    internal data class SavedValue(val slot: Slot, val value: Value?)

    internal fun parse(text: String, pc: Int): List<Slot> {
        if (pc < 1) return emptyList()
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        if ((root["formatVersion"] as? JsonPrimitive)?.intOrNull != FormatVersion) return emptyList()
        val pcs = root["pcs"] as? JsonObject ?: return emptyList()
        val slots = pcs[pc.toString()] as? JsonArray ?: return emptyList()
        return slots.mapNotNull(::parseSlot)
    }

    internal fun valuesOf(continuation: ObjectReference): List<SavedValue> {
        val type = continuation.referenceType()
        val pcField = type.fieldByName(PcField) ?: return emptyList()
        val pc = (continuation.getValue(pcField) as? IntegerValue)?.value() ?: return emptyList()
        val metadataField = type.fieldByName(MetadataField) ?: return emptyList()
        val metadata = (type.getValue(metadataField) as? StringReference)?.value() ?: return emptyList()
        return parse(metadata, pc).mapNotNull { slot ->
            val field = type.fieldByName(slot.field) ?: return@mapNotNull null
            SavedValue(slot, continuation.getValue(field))
        }
    }

    private fun parseSlot(element: kotlinx.serialization.json.JsonElement): Slot? {
        val obj = element as? JsonObject ?: return null
        fun string(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val field = string("field")?.takeIf(GeneratedField::matches) ?: return null
        val name = string("name")?.takeIf(String::isNotBlank) ?: return null
        val type = string("type")?.takeIf(String::isNotBlank) ?: return null
        val kind = string("kind")?.takeIf(Kinds::contains) ?: return null
        return Slot(field, name, type, kind)
    }
}
