package ru.studyassistant.backend.material;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;
import ru.studyassistant.backend.BackendApplication;

/** Запускает ту же сборку в режиме worker без Spring и без окружения сервера. */
@Component
public class PdfWorkerLauncher {
    public Process start(Path directory, PdfProperties properties) throws IOException {
        var args = new ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        args.add("-Xmx" + properties.getWorkerHeapMb() + "m");
        args.add("-XX:MaxMetaspaceSize=96m");
        args.add("-Dfile.encoding=UTF-8");
        args.add("-Duser.home=" + directory);
        args.add("-Djava.io.tmpdir=" + directory);
        args.add("-XX:-UsePerfData");
        var source = new ApplicationHome(BackendApplication.class).getSource();
        if (source != null && source.isFile() && source.getName().endsWith(".jar")) {
            args.addAll(List.of("-jar", source.getAbsolutePath()));
        } else {
            args.addAll(List.of("-cp", System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path")), BackendApplication.class.getName()));
        }
        args.addAll(List.of("--internal-pdf-worker", directory.resolve("input.pdf").toString(),
                directory.resolve("result.bin").toString(), Integer.toString(properties.getMaxPages()), "10000"));
        var builder = new ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        return builder.start();
    }
}
