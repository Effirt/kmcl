package cd.cc.effirt.kinastminecraftlauncher.util;

import cd.cc.effirt.auroralib.Log;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class GetTerminalCodePage {
    private static final String DLL = "kernel32.dll";
    private static final int CODEPAGE = getCodePage();

    private static int getCodePage() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup(DLL, arena);
            FunctionDescriptor fd = FunctionDescriptor.of(ValueLayout.JAVA_INT);
            MethodHandle mh = linker.downcallHandle(kernel32.findOrThrow("GetConsoleOutputCP"), fd);
            return (int) mh.invoke();
        } catch (Throwable e) {
            Log.debug("Failed to get code page", e);
            return 0;
        }
    }

    public static int get() {
        return CODEPAGE;
    }
}
