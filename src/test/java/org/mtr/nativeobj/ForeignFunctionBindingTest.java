package org.mtr.nativeobj;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the real JDK FFM linker without requiring a Vulkan device. */
public final class ForeignFunctionBindingTest {

        @Test
        public void bindsIntegerAndPointerArgumentsThroughTheFinalFfmApi() throws Throwable {
                assumeTrue(Runtime.version().feature() >= 22);
                final Class<?> linkerClass = Class.forName("java.lang.foreign.Linker");
                final Class<?> lookupClass = Class.forName("java.lang.foreign.SymbolLookup");
                final Object linker = linkerClass.getMethod("nativeLinker").invoke(null);
                final Object lookup = linkerClass.getMethod("defaultLookup").invoke(linker);
                final Object absAddress = ((Optional<?>) lookupClass.getMethod("find", String.class).invoke(lookup, "abs")).orElseThrow();
                final MethodHandle abs = NativeRuntime.downcallAtAddress(absAddress, int.class, int.class);
                assertEquals(37, (int) abs.invokeExact(-37));

                final Object atoiAddress = ((Optional<?>) lookupClass.getMethod("find", String.class).invoke(lookup, "atoi")).orElseThrow();
                final Class<?> segmentClass = Class.forName("java.lang.foreign.MemorySegment");
                final MethodHandle atoi = NativeRuntime.downcallAtAddress(atoiAddress, int.class, segmentClass);
                final Class<?> arenaClass = Class.forName("java.lang.foreign.Arena");
                final Object arena = arenaClass.getMethod("ofConfined").invoke(null);
                try {
                        final Object text = Class.forName("java.lang.foreign.SegmentAllocator").getMethod("allocateFrom", String.class)
                                        .invoke(arena, "1234");
                        assertEquals(1234, (int) atoi.invoke(text));
                } finally {
                        arenaClass.getMethod("close").invoke(arena);
                }
        }
}
