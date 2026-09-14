package com.trailmix.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationKeyTest {

    @Test
    fun `sorts ids regardless of selection order`() {
        assertEquals(ConversationKey.of(listOf(7L, 3L)), ConversationKey.of(listOf(3L, 7L)))
    }

    @Test
    fun `dedupes repeated ids`() {
        assertEquals(ConversationKey.of(listOf(3L, 7L)), ConversationKey.of(listOf(3L, 7L, 3L)))
    }

    @Test
    fun `renders as a comma-joined sorted list`() {
        assertEquals("3,7,12", ConversationKey.of(listOf(12L, 3L, 7L)))
    }

    @Test
    fun `a single id has no comma`() {
        assertEquals("5", ConversationKey.of(listOf(5L)))
    }

    @Test
    fun `an empty selection is an empty key`() {
        assertEquals("", ConversationKey.of(emptyList()))
    }
}
