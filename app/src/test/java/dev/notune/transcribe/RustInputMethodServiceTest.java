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
}
