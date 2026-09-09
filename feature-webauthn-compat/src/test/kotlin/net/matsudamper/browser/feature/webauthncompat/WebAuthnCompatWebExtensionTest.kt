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

    @Test
    fun `設定を無効にすると APP ソースで拡張機能を無効化する`() {
        val runtime = mockk<GeckoRuntime>()
        val controller = mockk<WebExtensionController>()
        val extension = mockk<WebExtension>()
        val installed: GeckoResult<WebExtension> = GeckoResult.fromValue(extension)
        val disabled: GeckoResult<WebExtension> = GeckoResult.fromValue(extension)
        every { runtime.webExtensionController } returns controller
        every { controller.ensureBuiltIn(any(), any()) } returns installed
        every {
            controller.disable(extension, WebExtensionController.EnableSource.APP)
        } returns disabled
        val target = WebAuthnCompatWebExtension()

        target.setEnabled(runtime, enabled = false)

        verify(exactly = 1) {
            controller.disable(extension, WebExtensionController.EnableSource.APP)
        }
    }

    @Test
    fun `再インストール時も保存した無効設定を再適用する`() {
        val runtime = mockk<GeckoRuntime>()
        val controller = mockk<WebExtensionController>()
        val firstExtension = mockk<WebExtension>()
        val retryExtension = mockk<WebExtension>()
        val firstInstall: GeckoResult<WebExtension> = GeckoResult.fromValue(firstExtension)
        val retryInstall: GeckoResult<WebExtension> = GeckoResult.fromValue(retryExtension)
        every { runtime.webExtensionController } returns controller
        every { controller.ensureBuiltIn(any(), any()) } returnsMany listOf(firstInstall, retryInstall)
        every {
            controller.disable(firstExtension, WebExtensionController.EnableSource.APP)
        } returns GeckoResult.fromValue(firstExtension)
        every {
            controller.disable(retryExtension, WebExtensionController.EnableSource.APP)
        } returns GeckoResult.fromValue(retryExtension)
        val target = WebAuthnCompatWebExtension()

        target.setEnabled(runtime, enabled = false)
        target.retryInstall(runtime)

        verify(exactly = 1) {
            controller.disable(firstExtension, WebExtensionController.EnableSource.APP)
        }
        verify(exactly = 1) {
            controller.disable(retryExtension, WebExtensionController.EnableSource.APP)
        }
    }
}
