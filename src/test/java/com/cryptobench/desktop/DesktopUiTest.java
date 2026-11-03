package com.cryptobench.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.cryptobench.benchmark.auth.service.DesktopAdminService;
import java.awt.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt in on an interactive desktop; only this JVM's own windows are controlled. */
@EnabledIfSystemProperty(named = "cryptobench.test.desktopUi", matches = "true")
class DesktopUiTest {
    @Test void setupCanBeCancelledRetriedAndCorrectedAndStartupFailureIsActionable() throws Exception {
        assertThat(DesktopUi.available()).isTrue();
        var admins = mock(DesktopAdminService.class);
        AtomicBoolean needed = new AtomicBoolean(true);
        when(admins.needsSetup()).thenAnswer(call -> needed.get());
        doAnswer(call -> {
            if (!call.getArgument(1).equals("ui-admin@example.test")) throw new IllegalArgumentException("邮箱格式不正确");
            needed.set(false);
            return null;
        }).when(admins).create(anyString(), anyString(), anyString());

        var worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try (var ui = new DesktopUi()) {
            Future<Boolean> cancelled = worker.submit(() -> DesktopUi.initializeAdmin(admins));
            JDialog setup = dialog("CryptoBench 首次设置");
            snapshot(setup, "desktop-ui-setup.png");
            choose(setup, JOptionPane.CANCEL_OPTION);
            assertThat(cancelled.get(10, TimeUnit.SECONDS)).isFalse();
            verify(admins, never()).create(anyString(), anyString(), anyString());

            Future<Boolean> corrected = worker.submit(() -> DesktopUi.initializeAdmin(admins));
            setup = dialog("CryptoBench 首次设置");
            List<JTextField> firstFields = fill(setup, "invalid");
            choose(setup, JOptionPane.OK_OPTION);
            JDialog validation = dialog("CryptoBench");
            choose(validation, JOptionPane.OK_OPTION);
            setup = dialog("CryptoBench 首次设置");
            fill(setup, "ui-admin@example.test");
            choose(setup, JOptionPane.OK_OPTION);
            assertThat(corrected.get(10, TimeUnit.SECONDS)).isTrue();
            for (JTextField field : firstFields) if (field instanceof JPasswordField password) {
                assertThat(DesktopUi.onUi(password::getPassword)).isEmpty();
            }

            Future<?> failure = worker.submit(() -> DesktopUi.startupError("启动失败。诊断文件：var/logs/startup-failure.log", Path.of("target")));
            JDialog error = dialog("CryptoBench 启动失败");
            assertThat(DesktopUi.onUi(() -> components(error, JButton.class).stream().map(JButton::getText).toList()))
                    .contains("打开日志目录", "关闭");
            snapshot(error, "desktop-ui-error.png");
            choose(error, 1);
            failure.get(10, TimeUnit.SECONDS);
        } finally {
            // Dispose an unexpected modal too, so failed assertions never leave this JVM stuck.
            worker.shutdownNow();
            new DesktopUi().close();
        }
    }

    static JDialog dialog(String title) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            JDialog found = DesktopUi.onUi(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog candidate && candidate.isShowing() && candidate.getTitle().equals(title)) return candidate;
                }
                return null;
            });
            if (found != null) return found;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Dialog did not open: " + title);
    }

    static List<JTextField> fill(JDialog dialog, String email) {
        return DesktopUi.onUi(() -> {
            List<JTextField> fields = components(dialog, JTextField.class);
            assertThat(fields).hasSize(4);
            fields.get(0).setText("UI fixture"); fields.get(1).setText(email);
            fields.get(2).setText("ui-fixture-password"); fields.get(3).setText("ui-fixture-password");
            return fields;
        });
    }

    static void choose(JDialog dialog, int option) {
        DesktopUi.onUi(() -> { components(dialog, JOptionPane.class).getFirst().setValue(option); return null; });
    }

    private static <T extends Component> List<T> components(Container parent, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container container) result.addAll(components(container, type));
        }
        return result;
    }

    private static void snapshot(JDialog dialog, String name) throws Exception {
        Robot robot = new Robot();
        robot.waitForIdle();
        Rectangle bounds = DesktopUi.onUi(dialog::getBounds);
        ImageIO.write(robot.createScreenCapture(bounds), "png", Path.of("target", name).toFile());
    }
}
