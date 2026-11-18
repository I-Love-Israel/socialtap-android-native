package com.socialtap.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OAuthFormTest {

    @Test
    fun findsCsrfTokenInHiddenInput() {
        val html = """
            <form action="/api/oauth/authorize" method="post">
              <input type="hidden" name="csrf_token" value="abc123.XYZ-_=" />
              <input type="hidden" name="client_id" value="stu_A49" />
            </form>
        """.trimIndent()
        assertEquals("abc123.XYZ-_=", OAuthForm.csrfToken(html))
    }

    @Test
    fun handlesValueBeforeNameAndSingleQuotes() {
        val html = "<input value='tok' type=hidden name='csrf_token'>"
        assertEquals("tok", OAuthForm.csrfToken(html))
    }

    @Test
    fun ignoresOtherInputs() {
        val html = """<input type="hidden" name="state" value="nope">"""
        assertNull(OAuthForm.csrfToken(html))
    }

    @Test
    fun readsCodeAndStateFromRedirect() {
        val location = "socialtap://oauth/callback?code=ab%2Fcd&state=xyz&iss=socialtap"
        assertEquals("ab/cd", OAuthForm.queryParam(location, "code"))
        assertEquals("xyz", OAuthForm.queryParam(location, "state"))
        assertNull(OAuthForm.queryParam(location, "error"))
    }

    @Test
    fun readsDeniedRedirect() {
        val location = "socialtap://auth/google?error=access_denied&state=s"
        assertEquals("access_denied", OAuthForm.queryParam(location, "error"))
        assertNull(OAuthForm.queryParam(location, "code"))
    }
}
