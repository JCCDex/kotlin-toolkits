package com.jccdex.toolkits.did.storage.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DidRoomEntityTest {
    @Test
    fun defaults_useZeroIdAndCurrentTimestamp() {
        val before = System.currentTimeMillis()
        val entity = DidRoomEntity(did = "did:test:1", doc = """{"v":1}""")
        val after = System.currentTimeMillis()

        assertThat(entity.id).isZero()
        assertThat(entity.did).isEqualTo("did:test:1")
        assertThat(entity.doc).isEqualTo("""{"v":1}""")
        assertThat(entity.updatedAt).isBetween(before, after)
    }

    @Test
    fun copy_preservesFields() {
        val entity =
            DidRoomEntity(
                id = 7L,
                did = "did:test:7",
                doc = """{"v":7}""",
                updatedAt = 123L
            )

        val copied = entity.copy(doc = """{"v":8}""")

        assertThat(copied.id).isEqualTo(7L)
        assertThat(copied.did).isEqualTo("did:test:7")
        assertThat(copied.doc).isEqualTo("""{"v":8}""")
        assertThat(copied.updatedAt).isEqualTo(123L)
    }

    @Test
    fun equals_andHashCode_useDataClassSemantics() {
        val left = DidRoomEntity(id = 1L, did = "did:a", doc = "{}", updatedAt = 1L)
        val right = DidRoomEntity(id = 1L, did = "did:a", doc = "{}", updatedAt = 1L)
        val other = DidRoomEntity(id = 2L, did = "did:b", doc = "{}", updatedAt = 1L)

        assertThat(left).isEqualTo(right)
        assertThat(left.hashCode()).isEqualTo(right.hashCode())
        assertThat(left).isNotEqualTo(other)
    }
}
