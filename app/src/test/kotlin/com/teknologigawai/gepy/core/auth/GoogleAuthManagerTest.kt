package com.teknologigawai.gepy.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAuthManagerTest {

    @Test
    fun `GepyUser model correctly stores and updates sessionToken`() {
        val user = GepyUser(
            id = "1029384756",
            email = "pengguna@example.com",
            displayName = "W Maulana Aishq",
            avatarUrl = "https://lh3.googleusercontent.com/photo.jpg"
        )

        assertEquals("1029384756", user.id)
        assertEquals("pengguna@example.com", user.email)
        assertEquals("W Maulana Aishq", user.displayName)
        assertTrue(user.sessionToken.isEmpty())

        val loggedInUser = user.copy(sessionToken = "jwt_test_token_2026")
        assertEquals("jwt_test_token_2026", loggedInUser.sessionToken)
    }

    @Test
    fun `GoogleAuthResult variants represent login states correctly`() {
        val user = GepyUser(id = "sub_1", email = "test@gmail.com", displayName = "Test")
        val success = GoogleAuthResult.Success(user = user, idToken = "id_token_123")
        assertTrue(success is GoogleAuthResult)
        assertEquals("id_token_123", success.idToken)
        assertEquals("test@gmail.com", success.user.email)

        val cancelled = GoogleAuthResult.Cancelled()
        assertEquals("Pengguna membatalkan login.", cancelled.message)

        val failure = GoogleAuthResult.Failure("Network error", IllegalStateException("timeout"))
        assertEquals("Network error", failure.message)
        assertNotNull(failure.throwable)
    }
}
