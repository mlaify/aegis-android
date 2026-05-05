package io.aegis.android.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tagged-union serializer for [UnlockMethod] — selects the concrete data
 * class by the JSON `"type"` field, matching the schema aegis-web and
 * aegis-apple emit.
 *
 * We can't use `@JsonClassDiscriminator` here because the underlying
 * Json instance isn't always under our control (kotlinx.serialization
 * defaults are stricter than what we want). This explicit serializer
 * makes the discriminator handling fully local to this module.
 */
internal object UnlockMethodSerializer : KSerializer<UnlockMethod> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("UnlockMethod")

    override fun serialize(encoder: Encoder, value: UnlockMethod) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException(
                "UnlockMethodSerializer requires a JSON encoder",
            )
        val element: JsonElement = when (value) {
            is PassphraseMethod ->
                jsonEncoder.json.encodeToJsonElement(PassphraseMethod.serializer(), value)
            is PasskeyMethod ->
                jsonEncoder.json.encodeToJsonElement(PasskeyMethod.serializer(), value)
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): UnlockMethod {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException(
                "UnlockMethodSerializer requires a JSON decoder",
            )
        val element = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject
            ?: throw SerializationException("expected a JSON object for UnlockMethod")
        val typeElement = obj["type"] as? JsonPrimitive
            ?: throw SerializationException("UnlockMethod missing `type` discriminator")
        val type = typeElement.jsonPrimitive.content
        return when (type) {
            "passphrase" ->
                jsonDecoder.json.decodeFromJsonElement(PassphraseMethod.serializer(), obj)
            "passkey" ->
                jsonDecoder.json.decodeFromJsonElement(PasskeyMethod.serializer(), obj)
            else -> throw VaultError.UnknownMethodType(type)
        }
    }
}
