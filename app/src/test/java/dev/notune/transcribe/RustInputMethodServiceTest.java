package dev.notune.transcribe;

import org.junit.Test;
import static org.junit.Assert.*;

public class RustInputMethodServiceTest {

    @Test
    public void testDeleteCountEmpty() {
        assertEquals(1, RustInputMethodService.calculateDeleteCount(null));
        assertEquals(1, RustInputMethodService.calculateDeleteCount(""));
    }

    @Test
    public void testDeleteCountSingleWord() {
        // "world" -> deletes "world" (5 chars)
        assertEquals(5, RustInputMethodService.calculateDeleteCount("world"));
    }

    @Test
    public void testDeleteCountWordWithLeadingSpace() {
        // "hello world" -> deletes " world" (6 chars)
        assertEquals(6, RustInputMethodService.calculateDeleteCount("hello world"));
    }

    @Test
    public void testDeleteCountWordWithTrailingSpaces() {
        // "hello world   " -> deletes " world   " (9 chars)
        assertEquals(9, RustInputMethodService.calculateDeleteCount("hello world   "));
    }

    @Test
    public void testDeleteCountWordWithTrailingPunctuation() {
        // "hello, world! " -> deletes " world! " (8 chars)
        assertEquals(8, RustInputMethodService.calculateDeleteCount("hello, world! "));
    }

    @Test
    public void testDeleteCountWordWithAdjacentPunctuationAndSpace() {
        // "hello , " -> deletes "hello , " (8 chars)
        assertEquals(8, RustInputMethodService.calculateDeleteCount("hello , "));
    }

    @Test
    public void testDeleteCountOnlyPunctuation() {
        // "..." -> deletes "..." (3 chars)
        assertEquals(3, RustInputMethodService.calculateDeleteCount("..."));
    }

    @Test
    public void testDeleteCountContraction() {
        // "they don't " -> deletes " don't " (7 chars)
        assertEquals(7, RustInputMethodService.calculateDeleteCount("they don't "));
    }

    @Test
    public void testDeleteCountCjk() {
        // CJK ideographs: deletes 1 CJK char
        // "你好世界" -> deletes "界" (1 char)
        assertEquals(1, RustInputMethodService.calculateDeleteCount("你好世界"));
    }

    @Test
    public void testDeleteCountCjkWithPunctuation() {
        // "你好世界。" -> deletes "界。" (2 chars)
        assertEquals(2, RustInputMethodService.calculateDeleteCount("你好世界。"));
    }

    @Test
    public void testCalculateCatchUpPercentStreaming() {
        // 0 audio processed -> 0%
        assertEquals(0, RustInputMethodService.calculateCatchUpPercent(0, 10000, 0, 0, 1.0f));

        // 5s of 10s audio processed -> 50%
        assertEquals(50, RustInputMethodService.calculateCatchUpPercent(5000, 10000, 0, 0, 1.0f));

        // 9.5s of 10s audio processed -> 95%
        assertEquals(95, RustInputMethodService.calculateCatchUpPercent(9500, 10000, 0, 0, 1.0f));

        // 10s of 10s audio processed -> 100%
        assertEquals(100, RustInputMethodService.calculateCatchUpPercent(10000, 10000, 0, 0, 1.0f));

        // Processed exceeds total slightly due to clock/sample rounding -> clamped at 100%
        assertEquals(100, RustInputMethodService.calculateCatchUpPercent(10500, 10000, 0, 0, 1.0f));

        // Zero total audio -> 0%
        assertEquals(0, RustInputMethodService.calculateCatchUpPercent(5000, 0, 0, 0, 1.0f));
    }

    @Test
    public void testCalculateCatchUpPercentEstimatedFallback() {
        // Non-streaming fallback: total 10s, speed 1.0x, 2s elapsed -> 20%
        assertEquals(20, RustInputMethodService.calculateCatchUpPercent(0, 10000, 1000, 3000, 1.0f));

        // Fallback caps at 95% until complete
        assertEquals(95, RustInputMethodService.calculateCatchUpPercent(0, 10000, 1000, 20000, 1.0f));
    }

    @Test
    public void testFormatDelay() {
        assertEquals("00:00", RustInputMethodService.formatDelay(0));
        assertEquals("00:00", RustInputMethodService.formatDelay(-500));
        assertEquals("00:01", RustInputMethodService.formatDelay(500));
        assertEquals("00:01", RustInputMethodService.formatDelay(1234));
        assertEquals("00:15", RustInputMethodService.formatDelay(15000));
        assertEquals("01:05", RustInputMethodService.formatDelay(65000));
    }

    @Test
    public void testNonStreamingModelsAreNotCapable() {
        assertFalse(StreamingModePrefs.isStreamingCapableModel(null, null));
        assertFalse(StreamingModePrefs.isStreamingCapableModel(null, ""));
        assertFalse(StreamingModePrefs.isStreamingCapableModel(null, "SenseVoiceSmall-Q8_0.gguf"));
        assertFalse(StreamingModePrefs.isStreamingCapableModel(null, "whisper-small-Q8_0.gguf"));
        assertFalse(StreamingModePrefs.isStreamingCapableModel(null, "whisper-base-Q8_0.gguf"));
        assertFalse(StreamingModePrefs.isStreamingCapableModel(null, "parakeet-tdt-0.6b-v3-Q4_K_M.gguf"));
        assertFalse(StreamingModePrefs.isStreamingCapableModel(null, "parakeet-tdt_ctc-110m-Q8_0.gguf"));
    }
}
