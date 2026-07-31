package com.jccdex.toolkits.vault.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jccdex.toolkits.vault.Vault
import com.jccdex.toolkits.vault.serializer.VaultSerializer
import com.jccdex.toolkits.vault.util.wipe
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CryptoHelpersTest {
    @Test
    fun `wipe clears byte and char arrays`() {
        val bytes = byteArrayOf(1, 2, 3)
        val chars = charArrayOf('a', 'b')

        bytes.wipe()
        chars.wipe()

        assertThat(bytes.toList()).containsExactly(0, 0, 0)
        assertThat(chars.toList()).containsExactly('\u0000', '\u0000')
    }

    @Test
    fun `choose maps memory class to params`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val params = Argon2ParamChooser.choose(context)
        val largeHeapParams = Argon2ParamChooser.choose(context, preferLargeHeap = true)

        assertThat(params.iterations).isGreaterThan(0)
        assertThat(params.memoryKiB).isGreaterThan(0)
        assertThat(largeHeapParams.iterations).isGreaterThan(0)
        assertThat(largeHeapParams.memoryKiB).isGreaterThan(0)
        assertThat(largeHeapParams.parallelism).isEqualTo(1)
    }

    @Test
    fun `serializer default value is empty vault`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val serializer = VaultSerializer(context)

        assertThat(serializer.defaultValue).isEqualTo(Vault.getDefaultInstance())
    }

    @Test
    fun `serializer can write and read empty vault`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val serializer = VaultSerializer(context)
            val output = ByteArrayOutputStream()

            serializer.writeTo(Vault.getDefaultInstance(), output)

            val restored = serializer.readFrom(output.toByteArray().inputStream())

            assertThat(restored).isEqualTo(Vault.getDefaultInstance())
        }
}
