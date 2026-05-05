// Android Passkey driver — wraps androidx.credentials.CredentialManager
// with the WebAuthn PRF extension to produce the 32-byte KEK input
// AegisCore expects.
//
// Two operations:
//   - register(): create a new platform Passkey on this device, then
//                 immediately assert it once to fetch the PRF output
//                 (some authenticators don't return PRF on registration
//                 itself, so we always do register-then-assert in one
//                 user-visible biometric prompt sequence — same as
//                 aegis-web / aegis-apple do).
//   - assert():   drive an assertion against an existing Passkey,
//                 returning the PRF output + which credential matched.
//
// Both flows go through the WebAuthn JSON wire format (not the
// CredentialManager Bundle API) because that's what androidx.credentials
// uses for PublicKeyCredential. The JSON shape mirrors what
// `navigator.credentials.create({...})` and `.get({...})` take in the
// browser — same fields, same base64url encoding.
//
// The relying-party identifier (RP ID) is `Passkey.RP_ID`. Must match
// what the server-side digital-asset-links file at
// https://auth.mlaify.io/.well-known/assetlinks.json declares for this
// Android app's package + signing fingerprint, otherwise Google's
// Credential Manager rejects the ceremony with a no-asset-links error.

package io.aegis.android.passkey

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialCancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom
import java.util.Base64

object Passkey {
    /** Relying-party identifier these Passkeys attest against. Must
     *  match what the Android app's digital-asset-links file at
     *  https://auth.mlaify.io/.well-known/assetlinks.json declares for
     *  this package + signing fingerprint, AND what the iOS / macOS
     *  apps use, so credentials are interchangeable across platforms
     *  when the user has both on the same Apple ID / Google account. */
    const val RP_ID = "auth.mlaify.io"
}

data class RegistrationResult(
    val credentialId: ByteArray,
    val prfOutput: ByteArray,
    val prfSalt: ByteArray,
)

data class AssertionResult(
    val credentialId: ByteArray,
    val prfOutput: ByteArray,
)

class PasskeyService(private val context: Context) {

    private val credentialManager: CredentialManager = CredentialManager.create(context)
    private val random = SecureRandom()

    // ----- Registration -------------------------------------------------

    /** Register a new platform Passkey for the local identity, then
     *  immediately assert it once to fetch a guaranteed PRF output.
     *  Same two-step flow the web + iOS clients use. */
    suspend fun register(
        identityId: String,
        identityLabel: String,
    ): RegistrationResult {
        val salt = randomBytes(32)
        val challenge = randomBytes(32)

        // ---- Step 1: create the credential ----
        val createJson = buildCreateRequestJson(
            challenge = challenge,
            identityId = identityId,
            identityLabel = identityLabel,
            prfSalt = salt,
        )
        val createRequest = CreatePublicKeyCredentialRequest(requestJson = createJson)
        val createResponse = try {
            credentialManager.createCredential(context, createRequest)
        } catch (e: CreateCredentialCancellationException) {
            throw PasskeyError.UserCancelled
        }
        // CreateCredentialResponse is the umbrella type — narrow to the
        // PublicKeyCredential variant so we can read the WebAuthn JSON.
        if (createResponse !is CreatePublicKeyCredentialResponse) {
            throw PasskeyError.UnexpectedResponseShape(
                "expected CreatePublicKeyCredentialResponse, got ${createResponse.javaClass.name}"
            )
        }
        val createdCredentialId = extractCredentialIdFromCreateResponse(createResponse)

        // ---- Step 2: immediately assert it to harvest the PRF output ----
        val asserted = assert(
            allowedCredentialIds = listOf(createdCredentialId),
            saltsByCredentialId = mapOf(createdCredentialId.toBase64Url() to salt),
        )

        return RegistrationResult(
            credentialId = asserted.credentialId,
            prfOutput = asserted.prfOutput,
            prfSalt = salt,
        )
    }

    // ----- Assertion ----------------------------------------------------

    suspend fun assert(
        allowedCredentialIds: List<ByteArray>,
        saltsByCredentialId: Map<String, ByteArray>,
    ): AssertionResult {
        val challenge = randomBytes(32)
        val getJson = buildGetRequestJson(
            challenge = challenge,
            allowedCredentialIds = allowedCredentialIds,
            saltsByCredentialId = saltsByCredentialId,
        )
        val request = GetCredentialRequest(
            credentialOptions = listOf(GetPublicKeyCredentialOption(requestJson = getJson)),
        )
        val response = try {
            credentialManager.getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            throw PasskeyError.UserCancelled
        }
        return extractAssertionResult(response)
    }

    // ----- WebAuthn JSON helpers ---------------------------------------

    /** Build the `PublicKeyCredentialCreationOptions` JSON the system
     *  CredentialManager expects. Shape mirrors `navigator.credentials
     *  .create({publicKey: ...})` in the browser. */
    private fun buildCreateRequestJson(
        challenge: ByteArray,
        identityId: String,
        identityLabel: String,
        prfSalt: ByteArray,
    ): String {
        val obj = buildJsonObject {
            put("challenge", challenge.toBase64Url())
            putJsonObject("rp") {
                put("name", "Aegis")
                put("id", Passkey.RP_ID)
            }
            putJsonObject("user") {
                put("id", identityId.toByteArray(Charsets.UTF_8).toBase64Url())
                put("name", identityLabel)
                put("displayName", identityLabel)
            }
            put("pubKeyCredParams", buildJsonArray {
                addJsonObject {
                    put("type", "public-key")
                    put("alg", -7) // ES256
                }
                addJsonObject {
                    put("type", "public-key")
                    put("alg", -257) // RS256
                }
            })
            putJsonObject("authenticatorSelection") {
                put("userVerification", "required")
                put("residentKey", "preferred")
            }
            put("timeout", 60_000)
            putJsonObject("extensions") {
                putJsonObject("prf") {
                    putJsonObject("eval") {
                        put("first", prfSalt.toBase64Url())
                    }
                }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    /** Build the `PublicKeyCredentialRequestOptions` JSON for assertion. */
    private fun buildGetRequestJson(
        challenge: ByteArray,
        allowedCredentialIds: List<ByteArray>,
        saltsByCredentialId: Map<String, ByteArray>,
    ): String {
        val obj = buildJsonObject {
            put("challenge", challenge.toBase64Url())
            put("rpId", Passkey.RP_ID)
            put("userVerification", "required")
            put("timeout", 60_000)
            put("allowCredentials", buildJsonArray {
                allowedCredentialIds.forEach { id ->
                    addJsonObject {
                        put("type", "public-key")
                        put("id", id.toBase64Url())
                    }
                }
            })
            putJsonObject("extensions") {
                putJsonObject("prf") {
                    putJsonObject("evalByCredential") {
                        saltsByCredentialId.forEach { (credIdB64Url, salt) ->
                            putJsonObject(credIdB64Url) {
                                put("first", salt.toBase64Url())
                            }
                        }
                    }
                }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun extractCredentialIdFromCreateResponse(
        response: CreatePublicKeyCredentialResponse
    ): ByteArray {
        val root = Json.parseToJsonElement(response.registrationResponseJson).jsonObject
        val rawId = root["rawId"]?.jsonPrimitive?.content
            ?: root["id"]?.jsonPrimitive?.content
            ?: throw PasskeyError.UnexpectedResponseShape(
                "no rawId / id in registration response"
            )
        return rawId.fromBase64Url()
    }

    private fun extractAssertionResult(response: GetCredentialResponse): AssertionResult {
        val cred = response.credential
        if (cred !is PublicKeyCredential) {
            throw PasskeyError.UnexpectedResponseShape(
                "expected PublicKeyCredential, got ${cred.javaClass.name}"
            )
        }
        val root = Json.parseToJsonElement(cred.authenticationResponseJson).jsonObject
        val rawId = root["rawId"]?.jsonPrimitive?.content
            ?: root["id"]?.jsonPrimitive?.content
            ?: throw PasskeyError.UnexpectedResponseShape(
                "no rawId / id in assertion response"
            )
        val extResults = root["clientExtensionResults"]?.jsonObject
            ?: throw PasskeyError.PrfNotReturned
        val prfBlock = extResults["prf"]?.jsonObject
            ?: throw PasskeyError.PrfNotReturned
        val results = prfBlock["results"]?.jsonObject
            ?: throw PasskeyError.PrfNotReturned
        val first = results["first"]?.jsonPrimitive?.content
            ?: throw PasskeyError.PrfNotReturned
        val prfOutput = first.fromBase64Url()
        if (prfOutput.size != 32) {
            throw PasskeyError.UnexpectedResponseShape(
                "PRF output is ${prfOutput.size} bytes, expected 32"
            )
        }
        return AssertionResult(
            credentialId = rawId.fromBase64Url(),
            prfOutput = prfOutput,
        )
    }

    // ----- Helpers ------------------------------------------------------

    private fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        random.nextBytes(bytes)
        return bytes
    }
}

// MARK: - Errors

sealed class PasskeyError(message: String) : Exception(message) {
    object UserCancelled : PasskeyError("Passkey prompt was cancelled.") {
        private fun readResolve(): Any = UserCancelled
    }
    object PrfNotReturned : PasskeyError(
        "This passkey didn't return a PRF output. The vault can't be unlocked with it. " +
            "Make sure the device's credential provider supports the WebAuthn PRF extension " +
            "(Google Password Manager on a recent Android device does)."
    ) {
        private fun readResolve(): Any = PrfNotReturned
    }
    class UnexpectedResponseShape(detail: String) :
        PasskeyError("Unexpected Passkey response: $detail")
}

// MARK: - base64url helpers
//
// WebAuthn JSON wire format uses base64url (no padding). The vault
// schema uses standard base64. Convert at the boundary.

private fun ByteArray.toBase64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.fromBase64Url(): ByteArray =
    Base64.getUrlDecoder().decode(this.trimEnd('='))
