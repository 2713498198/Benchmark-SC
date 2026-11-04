package com.cryptobench.desktop;

import com.cryptobench.benchmark.auth.service.DesktopAdminService;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import javax.swing.*;

final class DesktopUi implements AutoCloseable {
    private TrayIcon tray;
    private JFrame window;

    static boolean available() { return !GraphicsEnvironment.isHeadless(); }

    static <T> T onUi(java.util.concurrent.Callable<T> action) {
        try {
            if (EventQueue.isDispatchThread()) return action.call();
            FutureTask<T> task = new FutureTask<>(action);
            EventQueue.invokeAndWait(task);
            return task.get();
        } catch (Exception exception) { throw new IllegalStateException("桌面窗口不可用", exception); }
    }

    static void error(String message) {
        if (available()) onUi(() -> { JOptionPane.showMessageDialog(null, message, "CryptoBench", JOptionPane.ERROR_MESSAGE); return null; });
        else System.err.println(message);
    }

    static void startupError(String message, Path logDirectory) {
        if (!available() || logDirectory == null) { error(message); return; }
        onUi(() -> {
            String[] options = {"打开日志目录", "关闭"};
            if (JOptionPane.showOptionDialog(null, message, "CryptoBench 启动失败",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, options, options[0]) == 0) {
                openDirectory(logDirectory);
            }
            return null;
        });
    }

    static boolean confirmStop() {
        return available() && onUi(() -> JOptionPane.showConfirmDialog(null,
                "仍有未完成任务。退出会停止执行并保存已完成的结果，是否继续？", "退出 CryptoBench",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
    }

    static boolean initializeAdmin(DesktopAdminService admins) {
        if (!admins.needsSetup()) return true;
        if (!available()) throw new IllegalStateException("首次启动需要创建真实管理员。请在桌面启动，或配置 CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL、NAME、PASSWORD 后重试。");
        while (admins.needsSetup()) {
            String[] input = onUi(() -> {
                JTextField name = new JTextField(), email = new JTextField();
                JPasswordField password = new JPasswordField(), confirmation = new JPasswordField();
                JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
                panel.add(new JLabel("创建本机管理员（密码 8～24 位）"));
                panel.add(new JLabel("姓名")); panel.add(name);
                panel.add(new JLabel("邮箱")); panel.add(email);
                panel.add(new JLabel("密码")); panel.add(password);
                panel.add(new JLabel("确认密码")); panel.add(confirmation);
                int result = JOptionPane.showConfirmDialog(null, panel, "CryptoBench 首次设置", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                char[] first = password.getPassword(), second = confirmation.getPassword();
                try {
                    if (result != JOptionPane.OK_OPTION) return null;
                    if (!Arrays.equals(first, second)) return new String[]{"", "", "", "两次输入的密码不一致。"};
                    return new String[]{name.getText(), email.getText(), new String(first), ""};
                } finally {
                    Arrays.fill(first, '\0'); Arrays.fill(second, '\0');
                    password.setText(""); confirmation.setText("");
                }
            });
            if (input == null) return false;
            try {
                if (!input[3].isEmpty()) throw new IllegalArgumentException(input[3]);
                admins.create(input[0], input[1], input[2]);
            } catch (IllegalArgumentException exception) { error(exception.getMessage()); }
            finally { Arrays.fill(input, ""); }
        }
        return true;
    }

    void show(DesktopPaths paths, Runnable open, Runnable stop) {
        if (!available()) return;
        onUi(() -> {
            if (SystemTray.isSupported()) {
                BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(new Color(33, 68, 98)); graphics.fillRoundRect(0, 0, 32, 32, 8, 8);
                graphics.setColor(Color.WHITE); graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
                graphics.drawString("C", 7, 25); graphics.dispose();
                PopupMenu menu = new PopupMenu();
                add(menu, "打开主页", open);
                add(menu, "打开数据目录", () -> openDirectory(paths.data));
                add(menu, "打开日志目录", () -> openDirectory(paths.logs));
                menu.addSeparator(); add(menu, "退出", stop);
                tray = new TrayIcon(image, "CryptoBench 正在运行", menu);
                tray.setImageAutoSize(true);
                tray.addActionListener(event -> open.run());
                try { SystemTray.getSystemTray().add(tray); return null; }
                catch (AWTException unavailable) { tray = null; }
            }
            window = new JFrame("CryptoBench 正在运行");
            JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
            panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
            panel.add(new JLabel("关闭浏览器后服务继续运行，请使用“退出”。"));
            button(panel, "打开主页", open);
            button(panel, "打开数据目录", () -> openDirectory(paths.data));
            button(panel, "打开日志目录", () -> openDirectory(paths.logs));
            button(panel, "退出", stop);
            window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            window.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent event) { stop.run(); }
            });
            window.setContentPane(panel); window.pack(); window.setLocationRelativeTo(null); window.setVisible(true);
            return null;
        });
    }

    private static void add(PopupMenu menu, String text, Runnable action) {
        MenuItem item = new MenuItem(text); item.addActionListener(event -> action.run()); menu.add(item);
    }
    private static void button(JPanel panel, String text, Runnable action) {
        JButton button = new JButton(text); button.addActionListener(event -> action.run()); panel.add(button);
    }
    static void browse(URI uri) throws Exception {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) throw new UnsupportedOperationException("System browser integration is unavailable");
        Desktop.getDesktop().browse(uri);
    }
    private static void openDirectory(Path path) {
        try { Desktop.getDesktop().open(path.toFile()); }
        catch (Exception exception) { error("无法打开目录，请手动访问：\n" + path); }
    }
    @Override public void close() {
        if (!available()) return;
        onUi(() -> {
            if (tray != null) SystemTray.getSystemTray().remove(tray);
            if (window != null) window.dispose();
            for (Window dialog : Window.getWindows()) {
                if (dialog instanceof JDialog nativeDialog && nativeDialog.getTitle().startsWith("CryptoBench")) nativeDialog.dispose();
            }
            return null;
        });
    }
}
