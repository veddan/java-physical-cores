package veddan.physicalcores;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PhysicalCores {

    private static final Logger log = LoggerFactory.getLogger(PhysicalCores.class);

    private static final String OS_NAME = getOSName();

    /**
     * <p>
     * Returns the number of "physical" hardware threads available.
     * On a machien with hyper threading, this value may be less than the number
     * reported by {@link Runtime#availableProcessors()}.
     * If you are running on a virtual machine, the value returned will be the
     * number of cores assigned to the VM, not the actual number of physical
     * cores on the machine.
     * Likewise, if the number of cores available to this process is less than the
     * installed number, the available number will be returned.
     * </p>
     * <p>
     * This method can be rather expensive to call, especially on Windows, so store
     * its result instead of calling it repeatedly.
     * </p>
     * @return number of physical cores, or {@code null} if it could not be determined
     */
    public static Integer physicalCoreCount() {
        if (OS_NAME == null) {
            return null;
        }
        if (isLinux()) {
            return readFromProc();
        }
        if (isWindows()) {
            return readFromWMIC();
        }
        if (isMacOsX()) {
            return readFromSysctlOsX();
        }
        if (isFreeBsd()) {
            return readFromSysctlFreeBSD();
        }

        log.warn("Unknown OS \"{}\". Please report this so a case can be added.", OS_NAME);
        return null;
    }

    private static Integer readFromProc() {
        final String path = "/proc/cpuinfo";
        File cpuinfo = new File(path);
        if (!cpuinfo.exists()) {
            log.info("Old Linux without {}. Will not be able to provide core count.", path);
            return null;
        }
        Reader reader = null;
        try (InputStream in = new FileInputStream(cpuinfo)) {
            String s = readToString(in, Charset.forName("UTF-8"));
            Set<String> coreIdRows = new HashSet<>();
            for (String row : s.split("\n")) {
                if (row.startsWith("core id")) {
                    coreIdRows.add(row);
                }
            }
            return coreIdRows.isEmpty() ? null : coreIdRows.size();
        } catch (SecurityException | IOException e) {
            String msg = String.format("Error while reading %s", path);
            log.error(msg, e);
        }
        return null;
    }

    private static Integer readFromWMIC() {
        // For some reason, reading WMIC's output from a pipe just blocks forever
        // Instead, have it written to a temp file and read it from there.
        ProcessBuilder pb = new ProcessBuilder("WMIC", "/OUTPUT:STDOUT", "CPU", "Get", "/Format:List");
        pb.redirectErrorStream(true);
        Process wmicProc;
        try {
            wmicProc = pb.start();
            wmicProc.getOutputStream().close();
        } catch (IOException | SecurityException e) {
            log.error("Failed to spawn WMIC process. " +
                    "Will not be able to provide physical core count.", e);
            return null;
        }
        waitFor(wmicProc);
        //try (FileInputStream in = new FileInputStream(tmp)) {
        try (InputStream in = wmicProc.getInputStream()) {
            String wmicOutput = readToString(in, Charset.forName("US-ASCII"));
            return parseWmicOutput(wmicOutput);
        } catch (UnsupportedEncodingException e) {
            // Java implementations are required to support US-ASCII, so this can't happen
            throw new RuntimeException(e);
        } catch (SecurityException | IOException e) {
            log.error("Error while reading WMIC output file", e);
            return null;
        }
    }

    private static Integer parseWmicOutput(String wmicOutput) {
        String[] rows = wmicOutput.split("\n");
        int coreCount = 0;
        for (String row : rows) {
            if (row.startsWith("NumberOfCores")) {
                String num = row.split("=")[1].trim();
                try {
                    coreCount += Integer.parseInt(num);
                } catch (NumberFormatException e) {
                    log.error("Unexpected output from WMIC: \"{}\". " +
                              "Will not be able to provide physical core count.", wmicOutput);
                    return null;
                }
            }
        }
        return coreCount > 0 ? coreCount : null;
    }

    private static Integer readFromSysctlOsX() {
        String result = readSysctl("hw.physicalcpu", "-n");
        if (result == null) {
            return null;
        }
        try {
            return Integer.parseInt(result);
        } catch (NumberFormatException e) {
            log.error("sysctl returned something that was not a number: \"{}\"", result);
            return null;
        }
    }

    private static Integer readFromSysctlFreeBSD() {
        String result = readSysctl("dev.cpu");
        if (result == null) {
            return null;
        }
        Set<String> cpuLocations = new HashSet<>();
        for (String row : result.split("\n")) {
            if (row.contains("location")) {
                cpuLocations.add(row.split("\\\\")[1]);
            }
        }
        return cpuLocations.isEmpty() ? null : cpuLocations.size();
    }

    private static String readSysctl(String variable, String... options) {
        List<String> command = new ArrayList<>();
        command.add("sysctl");
        command.addAll(Arrays.asList(options));
        command.add(variable);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process sysctlProc;
        try {
            sysctlProc = pb.start();
        } catch (IOException | SecurityException e) {
            log.error("Failed to spawn sysctl process. " +
                      "Will not be able to provide physical core count.", e);
            return null;
        }
        String result;
        try {
            result = readToString(sysctlProc.getInputStream(), Charset.forName("UTF-8")).trim();
        } catch (UnsupportedEncodingException e) {
            // Java implementations are required to support UTF-8, so this can't happen
            throw new RuntimeException(e);
        } catch (IOException e) {
            log.error("Error while reading from sysctl process", e);
            return null;
        }
        int exitStatus = waitFor(sysctlProc);
        if (exitStatus != 0) {
            log.error("Could not read sysctl variable {}. Exit status was {}", variable, exitStatus);
            return null;
        }
        return result;
    }

    private static boolean isLinux() {
        return OS_NAME.startsWith("Linux") || OS_NAME.startsWith("LINUX");
    }

    private static boolean isWindows() {
        return OS_NAME.startsWith("Windows");
    }

    private static boolean isMacOsX() {
        return OS_NAME.startsWith("Max OS X");
    }

    private static boolean isFreeBsd() {
        return OS_NAME.startsWith("FreeBSD");
    }

    private static String getOSName() {
        String name = getSystemProperty("os.name");
        if (name == null) {
            log.error("Failed to read OS name. " +
                      "Will not be able to provide physical core count.");
        }
        return name;
    }

    private static String getSystemProperty(String property) {
        try {
            return System.getProperty(property);
        } catch (SecurityException e) {
            String msg = String.format("Could not read system property \"%s\"", property);
            log.error(msg, e);
            return null;
        }
    }

    private static String readToString(InputStream in, Charset charset) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(in , charset)) {
            StringWriter sw = new StringWriter();
            char[] buf = new char[10000];
            while (reader.read(buf) != -1) {
                sw.write(buf);
            }
            return sw.toString();
        }
    }

    private static int waitFor(Process proc) {
        try {
            return proc.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException("Can't happen", e);
        }
    }
}
