package cd.cc.effirt.kinastminecraftlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.time.Year;
import java.util.Properties;

import cd.cc.effirt.auroralib.Log;
import picocli.CommandLine;

public class VersionProvider implements CommandLine.IVersionProvider {
    private static final String VERSION_RESOURCE = "/kmcl.properties";
    private static final String JAVA_RUNTIME_NAME = System.getProperty("java.runtime.name");
    private static final String JAVA_RUNTIME_VERSION = System.getProperty("java.runtime.version");
    private static final String OS_ARCH = System.getProperty("os.arch");
    private static final String OS_NAME = System.getProperty("os.name");

    private static String doLoadVersion() {
        try (InputStream is = VersionProvider.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (is == null) return "0.0.0";
            Properties p = new Properties();
            p.load(is);
            String s = p.getProperty("version");
            return (s == null || s.isBlank()) ? "0.0.0" : s;
        } catch (IOException e) {
            Log.error("Failed to read '{}'", VERSION_RESOURCE, e);
            return "0.0.0";
        }
    }

    @Override
    public String[] getVersion() {
        return new String[]{"Kinast Minecraft Launcher " + doLoadVersion(),
                JAVA_RUNTIME_NAME + " " + JAVA_RUNTIME_VERSION + ", OS " + OS_NAME + " " + OS_ARCH,
                "Copyright " + Year.now() + " Effirt - License MIT"};
    }

    public static String getVer() {
        return doLoadVersion();
    }
}
