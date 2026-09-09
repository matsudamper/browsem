package net.matsudamper.browser.feature.webauthncompat

import java.util.concurrent.TimeoutException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class WebAuthnCompatWebExtensionTest {
    @Test
    fun `インストール完了後は Installed を返す`() {
        val runtime = mockk<GeckoRuntime>()
        val controller = mockk<WebExtensionController>()
        val installed = mockk<GeckoResult<WebExtension>>()
        val extension = mockk<WebExtension>()
        every { runtime.webExtensionController } returns controller
        every { controller.ensureBuiltIn(any(), any()) } returns installed
        every { installed.poll(0) } returns extension
        val target = WebAuthnCompatWebExtension()

        target.install(runtime)

        assertEquals(WebAuthnCompatInstallState.Installed, target.installationState())
    }

    @Test
    fun `インストール完了前は Pending のままで同じ結果を返す`() {
        val runtime = mockk<GeckoRuntime>()
        val controller = mockk<WebExtensionController>()
        val pending = mockk<GeckoResult<WebExtension>>()
        every { runtime.webExtensionController } returns controller
        every { controller.ensureBuiltIn(any(), any()) } returns pending
        every { pending.poll(0) } throws TimeoutException()
        val target = WebAuthnCompatWebExtension()

        assertSame(pending, target.install(runtime))
        assertSame(pending, target.install(runtime))
        assertEquals(WebAuthnCompatInstallState.Pending, target.installationState())
        verify(exactly = 1) { controller.ensureBuiltIn(any(), any()) }
    }

    @Test
    fun `インストール失敗を保持して retry で新しい結果へ切り替える`() {
        val runtime = mockk<GeckoRuntime>()
        val controller = mockk<WebExtensionController>()
        val failedResult = mockk<GeckoResult<WebExtension>>()
        val retryResult = mockk<GeckoResult<WebExtension>>()
        val failure = IllegalStateException("install failed")
        every { runtime.webExtensionController } returns controller
        every { controller.ensureBuiltIn(any(), any()) } returnsMany listOf(failedResult, retryResult)
        every { failedResult.poll(0) } throws failure
        every { retryResult.poll(0) } throws TimeoutException()
        val target = WebAuthnCompatWebExtension()

        target.install(runtime)
        val failedState = target.installationState()
        assertTrue(failedState is WebAuthnCompatInstallState.Failed)
        assertSame(failure, (failedState as WebAuthnCompatInstallState.Failed).error)

        assertSame(retryResult, target.retryInstall(runtime))
        assertEquals(WebAuthnCompatInstallState.Pending, target.installationState())
        verify(exactly = 2) { controller.ensureBuiltIn(any(), any()) }
    }
}
