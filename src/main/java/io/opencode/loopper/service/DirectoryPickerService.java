package io.opencode.loopper.service;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import org.springframework.stereotype.Service;

/** Opens an operating-system directory chooser for this localhost-only application. */
@Service
public class DirectoryPickerService {
    private static final Duration PICK_TIMEOUT = Duration.ofMinutes(10);
    private final PickerProcess process;
    private final DesktopPicker desktopPicker;
    private final String osName;

    public DirectoryPickerService() {
        this(new SystemPickerProcess(), new SwingDesktopPicker(), System.getProperty("os.name", ""));
    }

    DirectoryPickerService(PickerProcess process, String osName) {
        this(process, new SwingDesktopPicker(), osName);
    }

    DirectoryPickerService(PickerProcess process, DesktopPicker desktopPicker, String osName) {
        this.process = process;
        this.desktopPicker = desktopPicker;
        this.osName = osName.toLowerCase(Locale.ROOT);
    }

    public Optional<String> pickDirectory() {
        try {
            String lastFailure = null;
            for (List<String> command : commands()) {
                PickerResult result;
                try {
                    result = process.run(command, PICK_TIMEOUT);
                } catch (IOException e) {
                    lastFailure = e.getMessage();
                    continue;
                }
                if (result.timedOut()) {
                    throw new ServiceUnavailableException("DIRECTORY_PICKER_TIMEOUT", "Folder selection timed out");
                }
                if (result.exitCode() != 0) {
                    if (result.exitCode() == 1 && isCancellation(result.output())) return Optional.empty();
                    lastFailure = compact(result.output());
                    continue;
                }
                String selected = result.output().strip();
                if (selected.isEmpty()) return Optional.empty();
                Path canonical = Path.of(selected).toRealPath();
                if (!Files.isDirectory(canonical)) {
                    throw new ServiceUnavailableException("DIRECTORY_PICKER_INVALID_RESULT", "The selected path is not a directory");
                }
                return Optional.of(canonical.toString());
            }
            if (osName.contains("linux")) {
                try {
                    Optional<String> selected = desktopPicker.pickDirectory();
                    if (selected.isEmpty()) return Optional.empty();
                    Path canonical = Path.of(selected.get()).toRealPath();
                    if (!Files.isDirectory(canonical)) {
                        throw new ServiceUnavailableException("DIRECTORY_PICKER_INVALID_RESULT", "The selected path is not a directory");
                    }
                    return Optional.of(canonical.toString());
                } catch (ServiceUnavailableException failure) {
                    throw failure;
                } catch (Exception failure) {
                    lastFailure = failure.getMessage();
                }
            }
            throw new ServiceUnavailableException("DIRECTORY_PICKER_UNAVAILABLE",
                    "无法打开桌面文件夹选择器；请确认 Linux 图形桌面可用，或直接粘贴服务器绝对路径。原因：" + compact(lastFailure));
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("DIRECTORY_PICKER_INTERRUPTED", "Folder selection was interrupted");
        } catch (Exception e) {
            throw new ServiceUnavailableException("DIRECTORY_PICKER_UNAVAILABLE", "Folder selector is unavailable: " + e.getMessage());
        }
    }

    private List<List<String>> commands() {
        if (osName.contains("mac")) {
            return List.of(List.of("/usr/bin/osascript", "-e", "POSIX path of (choose folder with prompt \"选择 OpenCode Loopper 项目根目录\")"));
        }
        if (osName.contains("win")) {
            String script = "Add-Type -AssemblyName System.Windows.Forms; "
                    + "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                    + "$dialog=New-Object System.Windows.Forms.FolderBrowserDialog; "
                    + "$dialog.Description='选择 OpenCode Loopper 项目根目录'; "
                    + "if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $dialog.SelectedPath }";
            return List.of(
                    List.of("powershell.exe", "-NoProfile", "-STA", "-Command", script),
                    List.of("pwsh.exe", "-NoProfile", "-STA", "-Command", script));
        }
        if (osName.contains("linux")) {
            return List.of(
                    List.of("zenity", "--file-selection", "--directory", "--title=选择 OpenCode Loopper 项目根目录"),
                    List.of("kdialog", "--getexistingdirectory", ".", "--title", "选择 OpenCode Loopper 项目根目录"),
                    List.of("yad", "--file-selection", "--directory", "--title=选择 OpenCode Loopper 项目根目录"));
        }
        throw new ServiceUnavailableException("DIRECTORY_PICKER_UNSUPPORTED", "Folder selection is not supported on this operating system");
    }

    private boolean isCancellation(String output) {
        String normalized = output.toLowerCase(Locale.ROOT);
        return output.isBlank() || normalized.contains("user canceled") || normalized.contains("user cancelled") || normalized.contains("(-128)");
    }

    private String compact(String output) {
        String value = output == null ? "" : output.strip().replaceAll("\\s+", " ");
        return value.isEmpty() ? "unknown operating-system error" : value.substring(0, Math.min(value.length(), 240));
    }

    interface PickerProcess {
        PickerResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;
    }

    interface DesktopPicker {
        Optional<String> pickDirectory() throws Exception;
    }

    record PickerResult(int exitCode, String output, boolean timedOut) { }

    private static final class SystemPickerProcess implements PickerProcess {
        @Override
        public PickerResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
            Process picker = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!picker.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                picker.destroy();
                if (!picker.waitFor(1, TimeUnit.SECONDS)) picker.destroyForcibly();
                return new PickerResult(-1, "", true);
            }
            String output = new String(picker.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new PickerResult(picker.exitValue(), output, false);
        }
    }

    private static final class SwingDesktopPicker implements DesktopPicker {
        @Override
        public Optional<String> pickDirectory() throws Exception {
            if (GraphicsEnvironment.isHeadless()) {
                throw new IOException("Java 进程运行在无图形桌面的 headless 模式");
            }
            AtomicReference<Optional<String>> selected = new AtomicReference<>(Optional.empty());
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            Runnable showPicker = () -> {
                try {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setDialogTitle("选择 OpenCode Loopper 项目根目录");
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    chooser.setAcceptAllFileFilterUsed(false);
                    Path home = Path.of(System.getProperty("user.home", "."));
                    if (Files.isDirectory(home)) chooser.setCurrentDirectory(home.toFile());
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        selected.set(Optional.of(chooser.getSelectedFile().getAbsolutePath()));
                    }
                } catch (RuntimeException exception) {
                    failure.set(exception);
                }
            };
            if (SwingUtilities.isEventDispatchThread()) showPicker.run();
            else SwingUtilities.invokeAndWait(showPicker);
            if (failure.get() != null) throw failure.get();
            return selected.get();
        }
    }
}
