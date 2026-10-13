package app.tellev.ui.dsh

import org.junit.Assert.assertEquals
import org.junit.Test

class EffortSliderGeometryTest {
    @Test
    fun `knob stays completely inside either end of the track`() {
        assertEquals(14f, effortSliderCenter(248f, 14f, 0f), 0.001f)
        assertEquals(234f, effortSliderCenter(248f, 14f, 1f), 0.001f)
        assertEquals(124f, effortSliderCenter(248f, 14f, 0.5f), 0.001f)
    }

    @Test
    fun `narrow track clamps its knob without a negative range`() {
        assertEquals(5f, effortSliderCenter(10f, 14f, 1f), 0.001f)
        assertEquals(0f, effortSliderCenter(0f, 14f, 0.5f), 0.001f)
    }

    @Test
    fun `dragging outside the track and invalid values stay in range`() {
        assertEquals(0, effortSliderIndex(-3f, 8))
        assertEquals(7, effortSliderIndex(20f, 8))
        assertEquals(0, effortSliderIndex(Float.NaN, 8))
        assertEquals(0, effortSliderIndex(2f, 1))
    }
}
