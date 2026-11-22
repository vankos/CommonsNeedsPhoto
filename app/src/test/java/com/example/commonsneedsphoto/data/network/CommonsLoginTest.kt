package com.example.commonsneedsphoto.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonsLoginTest {

    private val token = "login-token"

    @Test
    fun `first clientlogin request carries the normal account fields`() {
        val fields = clientLoginFields(
            loginName = "JaneDoe",
            password = "secret",
            loginToken = token,
        ).toMap()

        assertEquals("clientlogin", fields["action"])
        assertEquals("JaneDoe", fields["username"])
        assertEquals("secret", fields["password"])
        assertEquals(token, fields["logintoken"])
        assertEquals("https://commons.wikimedia.org/", fields["loginreturnurl"])
        assertFalse(fields.containsKey("OATHToken"))
        assertFalse(fields.containsKey("logincontinue"))
    }

    @Test
    fun `TOTP second step sends OATHToken and logincontinue`() {
        val fields = clientLoginFields(
            loginName = "JaneDoe",
            password = "secret",
            loginToken = token,
            code = "123456",
            method = TwoFactorMethod.TOTP,
        ).toMap()

        assertEquals("123456", fields["OATHToken"])
        assertEquals("1", fields["logincontinue"])
        assertEquals(token, fields["logintoken"])
        assertFalse(fields.containsKey("token"))
    }

    @Test
    fun `email second step sends token and logincontinue`() {
        val fields = clientLoginFields(
            loginName = "JaneDoe",
            password = "secret",
            loginToken = token,
            code = "ABCD-1234",
            method = TwoFactorMethod.EMAIL,
        ).toMap()

        assertEquals("ABCD-1234", fields["token"])
        assertEquals("1", fields["logincontinue"])
        assertFalse(fields.containsKey("OATHToken"))
    }

    @Test
    fun `TOTP UI response is detected`() {
        val result = parseVerificationRequest(clientloginWithRequest("TOTPAuthenticationRequest"))

        assertEquals(LoginResult.VerificationRequired(TwoFactorMethod.TOTP), result)
    }

    @Test
    fun `email UI response is detected`() {
        val result = parseVerificationRequest(
            clientloginWithRequest("EmailAuthAuthenticationRequest"),
        )

        assertEquals(LoginResult.VerificationRequired(TwoFactorMethod.EMAIL), result)
    }

    @Test
    fun `unsupported verification asks for a bot password`() {
        val exception = assertThrows(CommonsApiException::class.java) {
            parseVerificationRequest(clientloginWithRequest("CaptchaAuthenticationRequest"))
        }

        assertEquals("login-verification", exception.code)
        assertTrue(exception.message.orEmpty().contains("bot password"))
        assertTrue(exception.message.orEmpty().contains("name@bot"))
    }

    private fun clientloginWithRequest(id: String): JsonObject = buildJsonObject {
        put("status", "UI")
        put(
            "requests",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", id)
                    },
                )
            },
        )
    }
}
