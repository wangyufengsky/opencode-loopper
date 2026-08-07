package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryPickerServiceTest {
    @TempDir Path directory;

    @Test
    void returnsCanonicalDirectorySelectedByMacOs() throws Exception {
        AtomicReference<List<String>> invoked = new AtomicReference<>();
        DirectoryPickerService service = new DirectoryPickerService(
                (command, timeout) -> { invoked.set(command); return new DirectoryPickerService.PickerResult(0, directory + "/\n", false); }, "Mac OS X");

        assertThat(service.pickDirectory()).contains(directory.toRealPath().toString());
        assertThat(invoked.get()).startsWith("/usr/bin/osascript", "-e");
    }

    @Test
    void usesWindowsFolderBrowserDialogWithUtf8Output() throws Exception {
        AtomicReference<List<String>> invoked = new AtomicReference<>();
        DirectoryPickerService service = new DirectoryPickerService(
                (command, timeout) -> { invoked.set(command); return new DirectoryPickerService.PickerResult(0, directory + "\r\n", false); }, "Windows 11");

        assertThat(service.pickDirectory()).contains(directory.toRealPath().toString());
        assertThat(invoked.get()).startsWith("powershell.exe", "-NoProfile", "-STA", "-Command");
        assertThat(invoked.get().get(4)).contains("FolderBrowserDialog").contains("OutputEncoding");
    }

    @Test
    void fallsBackAcrossLinuxDesktopPickersWhenOneIsNotInstalled() throws Exception {
        List<String> attemptedExecutables = new ArrayList<>();
        DirectoryPickerService service = new DirectoryPickerService((command, timeout) -> {
            attemptedExecutables.add(command.getFirst());
            if ("zenity".equals(command.getFirst())) throw new IOException("zenity not installed");
            return new DirectoryPickerService.PickerResult(0, directory + "\n", false);
        }, "Linux");

        assertThat(service.pickDirectory()).contains(directory.toRealPath().toString());
        assertThat(attemptedExecutables).containsExactly("zenity", "kdialog");
    }

    @Test
    void fallsBackToBundledJavaChooserWhenLinuxUtilitiesAreNotInstalled() throws Exception {
        List<String> attemptedExecutables = new ArrayList<>();
        DirectoryPickerService service = new DirectoryPickerService((command, timeout) -> {
            attemptedExecutables.add(command.getFirst());
            throw new IOException(command.getFirst() + " not installed");
        }, () -> Optional.of(directory.toString()), "Linux");

        assertThat(service.pickDirectory()).contains(directory.toRealPath().toString());
        assertThat(attemptedExecutables).containsExactly("zenity", "kdialog", "yad");
    }

    @Test
    void treatsNativeCancelAsAValidEmptySelection() {
        DirectoryPickerService service = new DirectoryPickerService(
                (command, timeout) -> new DirectoryPickerService.PickerResult(1, "execution error: User canceled. (-128)\n", false), "Mac OS X");

        assertThat(service.pickDirectory()).isEmpty();
    }

    @Test
    void reportsNativePickerFailuresWithoutPretendingTheyWereCancelled() {
        DirectoryPickerService service = new DirectoryPickerService(
                (command, timeout) -> new DirectoryPickerService.PickerResult(2, "native dialog failed", false), "Mac OS X");

        assertThatThrownBy(service::pickDirectory)
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("native dialog failed");
    }
}
