package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码服务单元测试：无需启动 Spring，验证旧版兼容、新哈希随机盐与错误密码处理。
 */
class PasswordServiceTest {
    private final PasswordService passwords = new PasswordService();

    /**
     * 使用旧 Node 服务产生的固定样本，验证盐值编码和 scrypt 参数保持兼容。
     */
    @Test
    void verifiesExistingNodeScryptHashes() {
        // 样本由旧服务默认设置下的 Node crypto.scryptSync 生成，用于跨语言兼容校验。
        var user = new BenchmarkUser("legacy", "legacy@example.com", "Legacy", "researcher",
                "62fd440a53a6d02a85a9760eafe8eb476210009581221d589e528c85a802ff37cc"
                        + "9030dc9e4a29d3c7b26a0e4f03fd85101367fecda364309b1ed2d7298ba3d4",
                "0123456789abcdef0123456789abcdef", "2026-01-01T00:00:00Z");
        assertThat(passwords.matches("legacy-test-password", user)).isTrue();
        assertThat(passwords.matches("incorrect-password", user)).isFalse();
    }

    /**
     * 相同密码每次编码应不同，同时验证正确密码通过、错误密码被拒绝。
     */
    @Test
    void newHashesAreSaltedAndRejectWrongPasswords() {
        String hash = passwords.encode("test-password");
        assertThat(hash).isNotEqualTo(passwords.encode("test-password"));
        var user = new BenchmarkUser("new", "new@example.com", "New", "researcher", hash, null, "now");
        assertThat(passwords.matches("test-password", user)).isTrue();
        assertThat(passwords.matches("incorrect-password", user)).isFalse();
    }
}
