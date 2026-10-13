import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule the whole scaling mechanism rests on: only ever add the density the
 * platform failed to apply, never more, and never less than an explicit request.
 */
class UiScaleTest {

    @Test
    fun `tops up the fractional Xft dpi that X11 dropped`() {
        // Xft.dpi 168 = 1.75, but the JDK left AWT at 1.0
        assertEquals(1.75f, uiScaleFactor(platformDensity = 1f, requestedScale = null, xftScale = 1.75f), 1e-4f)
    }

    @Test
    fun `does not double scale when the platform already applied it`() {
        assertEquals(1f, uiScaleFactor(platformDensity = 1.75f, requestedScale = null, xftScale = 1.75f), 1e-4f)
        // integer X11 scaling: uiScale=2 was honoured, Xft.dpi says 2 as well
        assertEquals(1f, uiScaleFactor(platformDensity = 2f, requestedScale = null, xftScale = 2f), 1e-4f)
    }

    @Test
    fun `leaves a platform that knows its own scale alone`() {
        // Windows at 150%, no X resource DB at all
        assertEquals(1f, uiScaleFactor(platformDensity = 1.5f, requestedScale = null, xftScale = null), 1e-4f)
        assertEquals(1f, uiScaleFactor(platformDensity = 1f, requestedScale = null, xftScale = null), 1e-4f)
    }

    @Test
    fun `an explicit --ui-scale is an absolute target, not a multiplier`() {
        assertEquals(2f, uiScaleFactor(platformDensity = 1f, requestedScale = 2f, xftScale = 1.75f), 1e-4f)
        assertEquals(1.3333f, uiScaleFactor(platformDensity = 1.5f, requestedScale = 2f, xftScale = null), 1e-3f)
        // shrinking below 1x is allowed, which auto-detection never is
        assertEquals(0.5f, uiScaleFactor(platformDensity = 1f, requestedScale = 0.5f, xftScale = 1.75f), 1e-4f)
    }

    @Test
    fun `stays inside sane bounds whatever the inputs are`() {
        assertEquals(10f, uiScaleFactor(platformDensity = 0.5f, requestedScale = 100f, xftScale = null), 1e-4f)
        assertEquals(0.1f, uiScaleFactor(platformDensity = 1f, requestedScale = 0.001f, xftScale = null), 1e-4f)
        // a nonsensical platform density must not blow the factor up
        assertEquals(1.75f, uiScaleFactor(platformDensity = 0f, requestedScale = null, xftScale = 1.75f), 1e-4f)
    }
}
