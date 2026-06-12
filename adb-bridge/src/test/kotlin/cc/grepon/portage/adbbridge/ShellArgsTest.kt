/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package cc.grepon.portage.adbbridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellArgsTest {

    @Test
    fun `safe args pass through verbatim`() {
        assertThat(ShellArgs.quote("pm")).isEqualTo("pm")
        assertThat(ShellArgs.quote("android.permission.WRITE_SECURE_SETTINGS"))
            .isEqualTo("android.permission.WRITE_SECURE_SETTINGS")
        assertThat(ShellArgs.quote("--user")).isEqualTo("--user")
        assertThat(ShellArgs.quote("/data/local/tmp/base.apk")).isEqualTo("/data/local/tmp/base.apk")
    }

    @Test
    fun `args with spaces are single-quoted`() {
        assertThat(ShellArgs.quote("hello world")).isEqualTo("'hello world'")
    }

    @Test
    fun `shell metacharacters are neutralized by quoting`() {
        assertThat(ShellArgs.quote("a;rm -rf x")).isEqualTo("'a;rm -rf x'")
        assertThat(ShellArgs.quote("\$(reboot)")).isEqualTo("'\$(reboot)'")
        assertThat(ShellArgs.quote("a|b&c>d")).isEqualTo("'a|b&c>d'")
        assertThat(ShellArgs.quote("`id`")).isEqualTo("'`id`'")
    }

    @Test
    fun `embedded single quotes use the standard escape`() {
        assertThat(ShellArgs.quote("it's")).isEqualTo("'it'\\''s'")
    }

    @Test
    fun `empty arg becomes empty quotes`() {
        assertThat(ShellArgs.quote("")).isEqualTo("''")
    }

    @Test
    fun `control characters are rejected outright`() {
        assertThat(ShellArgs.quote("a\nb")).isNull()
        assertThat(ShellArgs.quote("a\rb")).isNull()
        assertThat(ShellArgs.quote("a\u0000b")).isNull()
        assertThat(ShellArgs.quote("a\u001bb")).isNull()
        assertThat(ShellArgs.quote("tab\there")).isNull()
        assertThat(ShellArgs.quote("del\u007fchar")).isNull()
    }

    @Test
    fun `command joins quoted args with spaces`() {
        assertThat(ShellArgs.command("settings", "put", "secure", "ui_night_mode", "2"))
            .isEqualTo("settings put secure ui_night_mode 2")
        assertThat(ShellArgs.command("settings", "put", "global", "k", "two words"))
            .isEqualTo("settings put global k 'two words'")
    }

    @Test
    fun `command rejects the whole line if any arg is rejected`() {
        assertThat(ShellArgs.command("pm", "grant", "pkg\nname", "perm")).isNull()
    }

    @Test
    fun `command with no args is rejected`() {
        assertThat(ShellArgs.command()).isNull()
    }
}
