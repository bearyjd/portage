/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.providers

import com.ventouxlabs.portage.model.ItemKind
import com.ventouxlabs.portage.model.ItemStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

private class StubApply(override val kind: ItemKind, private val outcome: ApplyOutcome) : ApplyProvider {
    var calls = 0
    override suspend fun apply(source: InputStream): ApplyOutcome {
        calls++
        return outcome
    }
}

class ApplyProviderRegistryTest {

    private val empty: InputStream get() = ByteArrayInputStream(ByteArray(0))

    @Test
    fun `routes a payload to the provider registered for its kind`() = runTest {
        val contacts = StubApply(ItemKind.CONTACTS_VCF, ApplyOutcome(ItemStatus.OK, "contacts"))
        val calls = StubApply(ItemKind.CALL_LOG, ApplyOutcome(ItemStatus.OK, "calls"))
        val registry = ApplyProviderRegistry(listOf(contacts, calls))

        val outcome = registry.apply(ItemKind.CALL_LOG, 0, empty)

        assertThat(outcome.detail).isEqualTo("calls")
        assertThat(calls.calls).isEqualTo(1)
        assertThat(contacts.calls).isEqualTo(0)
    }

    @Test
    fun `a kind with no registered handler is reported UNKNOWN_KIND, never acted on`() = runTest {
        val registry = ApplyProviderRegistry(emptyList())

        val outcome = registry.apply(ItemKind.SETTINGS, 0, empty)

        assertThat(outcome.status).isEqualTo(ItemStatus.UNKNOWN_KIND)
    }

    @Test
    fun `forKind exposes the registered provider or null`() {
        val contacts = StubApply(ItemKind.CONTACTS_VCF, ApplyOutcome(ItemStatus.OK))
        val registry = ApplyProviderRegistry(listOf(contacts))

        assertThat(registry.forKind(ItemKind.CONTACTS_VCF)).isSameInstanceAs(contacts)
        assertThat(registry.forKind(ItemKind.SMS)).isNull()
    }

    @Test
    fun `routes a WALLPAPER payload to its registered provider`() = runTest {
        val wallpaper = StubApply(ItemKind.WALLPAPER, ApplyOutcome(ItemStatus.OK, "wallpaper"))
        val registry = ApplyProviderRegistry(listOf(wallpaper))

        val outcome = registry.apply(ItemKind.WALLPAPER, 0, empty)

        assertThat(outcome.detail).isEqualTo("wallpaper")
        assertThat(wallpaper.calls).isEqualTo(1)
    }

    @Test
    fun `routes an APP_BACKUP_RELAY payload to its registered provider`() = runTest {
        val relay = StubApply(ItemKind.APP_BACKUP_RELAY, ApplyOutcome(ItemStatus.OK, "relay"))
        val registry = ApplyProviderRegistry(listOf(relay))

        val outcome = registry.apply(ItemKind.APP_BACKUP_RELAY, 0, empty)

        assertThat(outcome.detail).isEqualTo("relay")
        assertThat(relay.calls).isEqualTo(1)
    }

    @Test
    fun `registry threads the item id to a provider that overrides the itemId apply`() = runTest {
        val seen = mutableListOf<Int>()
        val provider = object : ApplyProvider {
            override val kind = ItemKind.APP_BACKUP_RELAY
            override suspend fun apply(source: InputStream) = ApplyOutcome(ItemStatus.OK, "no-id")
            override suspend fun apply(itemId: Int, source: InputStream): ApplyOutcome {
                seen += itemId
                return ApplyOutcome(ItemStatus.OK, "id-$itemId")
            }
        }
        val registry = ApplyProviderRegistry(listOf(provider))

        val outcome = registry.apply(ItemKind.APP_BACKUP_RELAY, 7, empty)

        assertThat(outcome.detail).isEqualTo("id-7")
        assertThat(seen).containsExactly(7)
    }

    @Test
    fun `an id-unaware provider is reached through the default itemId apply`() = runTest {
        val stub = StubApply(ItemKind.SETTINGS, ApplyOutcome(ItemStatus.OK, "plain"))
        val registry = ApplyProviderRegistry(listOf(stub))

        assertThat(registry.apply(ItemKind.SETTINGS, 99, empty).detail).isEqualTo("plain")
        assertThat(stub.calls).isEqualTo(1)
    }
}
