package dev.ryuya.galaxyreload

import java.lang.reflect.Modifier
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Command/helper entry points retain their Java calling convention after migration. */
class JvmEntryPointsTest {
    @Test
    fun rootHelpersRemainStaticWithoutExecutingCommands() {
        for (name in listOf("requireRoot", "run", "execute")) {
            val parameters =
                if (name == "requireRoot") emptyArray() else arrayOf(String::class.java)
            val method = RootShell::class.java.getDeclaredMethod(name, *parameters)
            assertTrue(name, Modifier.isStatic(method.modifiers))
        }
    }

    @Test
    fun ticketParserAndLifetimeRetainTheirJavaContract() {
        val parser = ReloadTicket::class.java.getDeclaredMethod("parse", Properties::class.java)
        assertTrue(Modifier.isStatic(parser.modifiers))
        val lifetime = ReloadTicket::class.java.getDeclaredField("REQUEST_LIFETIME_MS")
        assertEquals(Long::class.javaPrimitiveType, lifetime.type)
        assertEquals(12000L, lifetime.getLong(null))
    }
}
