/*
 * portage — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.wizard

import com.ventouxlabs.portage.adbbridge.AdbBridge
import com.ventouxlabs.portage.adbbridge.PairingPortDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The privilege bootstrap (ADR-003): portage owns the full setup flow — enable Developer
 * options, enable Wireless Debugging, enter the 6-digit pairing code, probe capabilities —
 * with no third-party app and no PC. Pure state machine; the Compose screen in app-recv is a
 * thin renderer over [step].
 *
 * Two invariants the security gate cares about:
 *  - The connection is torn down IMMEDIATELY after the capability probe ([finishProbe]); shell
 *    uid is re-established only at transfer time, never held open in the background.
 *  - The pairing code String passes through to [AdbBridge.pair] and is never logged or
 *    persisted here.
 *
 * Reboot recovery (devils-advocate Q1): pairing keys persist; only the Wireless Debugging
 * toggle resets. Once the toggle is back on, [start]/[recheck] try a plain [AdbBridge.connect]
 * before asking the user to pair, so a rebooted device walks: enable toggle → reconnect →
 * probe, with no re-pair. That connect is gated behind the toggle on purpose: with it off
 * there is no endpoint and libadb's mDNS wait ignores the connect timeout, so it would hang
 * (found on-device, GOS A16). See [route].
 */
class PrivilegeWizard(
    private val bridge: AdbBridge,
    private val environment: WizardEnvironment,
    private val portDetector: PairingPortDetector,
    private val scope: CoroutineScope,
    private val detectTimeoutMs: Long = DETECT_TIMEOUT_MS,
) {

    sealed interface Step {
        /** Wizard not yet started (or dismissed). */
        data object Idle : Step

        /** Step 1: checking for a live or reconnectable bridge. */
        data object Checking : Step

        /** Step 2: Developer options are off — guide the user to About phone. */
        data object EnableDevOptions : Step

        /** Step 3: Wireless Debugging is off — guide the user to Developer options. */
        data object EnableWirelessDebug : Step

        /**
         * Step 4: the pairing dialog should be open on screen. [detectedPort] is mDNS-found
         * (null while [detecting] or if discovery failed — manual port entry is the fallback).
         */
        data class EnterPairingCode(
            val detectedPort: Int? = null,
            val detecting: Boolean = false,
            val error: PairingError? = null,
        ) : Step

        /** Pair + connect in flight. */
        data object Pairing : Step

        /** Step 5: connected; running the ADR-001 V4–V7 capability probes. */
        data object Probing : Step

        /** Setup complete. [capabilities] drives the "advanced vs basic transfer" summary. */
        data class Ready(val capabilities: Set<AdbBridge.PrivilegedCapability>) : Step

        /** User declined setup — Tier 0 only, the bridge stays a no-op for this session. */
        data object Skipped : Step
    }

    enum class PairingError { WRONG_CODE, TIMEOUT, ENDPOINT_DOWN, CONNECT_FAILED, BAD_INPUT }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    private var detection: Job? = null

    /** The entry state at the moment of submit — restored (with the port) on a failed attempt. */
    private var lastEntry: Step.EnterPairingCode? = null

    /** Begin (or re-run) the wizard. Safe to call from Idle, Ready, or Skipped. */
    fun start() {
        if (_step.value !is Step.Idle && _step.value !is Step.Ready && _step.value !is Step.Skipped) return
        _step.value = Step.Checking
        scope.launch { route() }
    }

    /**
     * Re-evaluate after the user returns from Settings (onResume): Developer options or the
     * Wireless Debugging toggle may have just changed.
     */
    fun recheck() {
        val current = _step.value
        if (current is Step.EnableDevOptions || current is Step.EnableWirelessDebug) {
            _step.value = Step.Checking
            scope.launch { route() }
        }
    }

    /** Step 4 submit. [portOverride] backs the manual field when mDNS found nothing. */
    fun submitPairingCode(code: String, portOverride: Int? = null) {
        val current = _step.value as? Step.EnterPairingCode ?: return
        val port = portOverride ?: current.detectedPort
        val trimmed = code.trim()
        if (port == null || port !in 1..MAX_PORT || trimmed.length != PAIRING_CODE_LENGTH ||
            trimmed.any { !it.isDigit() }
        ) {
            _step.value = current.copy(error = PairingError.BAD_INPUT)
            return
        }
        cancelDetection()
        lastEntry = current.copy(detectedPort = port, detecting = false, error = null)
        _step.value = Step.Pairing
        scope.launch { pairAndConnect(port, trimmed) }
    }

    /** Available at every pre-completion step (spec: steps 2–4 plus the check). */
    fun skip() {
        cancelDetection()
        _step.value = Step.Skipped
    }

    /** Leave the wizard surface without recording a decision (back navigation). */
    fun dismiss() {
        cancelDetection()
        _step.value = Step.Idle
    }

    private suspend fun route() {
        // A live connection trumps everything (rare — we disconnect right after probing).
        if (bridge.isConnected()) {
            finishProbe()
            return
        }
        // Detect prerequisites BEFORE attempting a silent reconnect. A reconnect is only
        // possible once Wireless debugging is on: with the toggle off there is no
        // _adb-tls-connect endpoint, and attempting connect() would block on mDNS discovery
        // that the connect timeout CANNOT interrupt (libadb's NsdManager wait ignores thread
        // interruption) — the wizard would hang on "Checking" with no escape (found on-device,
        // GOS A16). Gating on the toggle loses nothing: post-reboot recovery re-attempts
        // connect() via recheck() once the toggle is turned back on, reconnecting with the
        // persisted pairing key (no re-pair).
        if (!environment.developerOptionsEnabled()) {
            _step.value = Step.EnableDevOptions
            return
        }
        if (!environment.wirelessDebuggingEnabled()) {
            _step.value = Step.EnableWirelessDebug
            return
        }
        // Wireless debugging is on, so a real endpoint may exist — the persisted pairing key
        // skips every user-facing step.
        if (bridge.connect() is AdbBridge.ConnectionResult.Connected) {
            finishProbe()
            return
        }
        enterPairing()
    }

    private fun enterPairing() {
        _step.value = Step.EnterPairingCode(detecting = true)
        detection?.cancel()
        detection = scope.launch {
            val port = portDetector.detectPairingPort(detectTimeoutMs)
            val current = _step.value as? Step.EnterPairingCode ?: return@launch
            _step.value = current.copy(detectedPort = port ?: current.detectedPort, detecting = false)
        }
    }

    private suspend fun pairAndConnect(port: Int, code: String) {
        when (bridge.pair(port, code)) {
            is AdbBridge.PairingResult.Paired -> when (bridge.connect()) {
                is AdbBridge.ConnectionResult.Connected -> finishProbe()
                else -> failPairing(PairingError.CONNECT_FAILED)
            }

            is AdbBridge.PairingResult.WrongCode -> failPairing(PairingError.WRONG_CODE)
            is AdbBridge.PairingResult.Timeout -> failPairing(PairingError.TIMEOUT)
            // Stale/closed pairing dialog: the old port is dead, so restart discovery too.
            is AdbBridge.PairingResult.Unavailable -> {
                enterPairing()
                val current = _step.value as? Step.EnterPairingCode ?: return
                _step.value = current.copy(error = PairingError.ENDPOINT_DOWN)
            }

            is AdbBridge.PairingResult.Unsupported -> _step.value = Step.Skipped
        }
    }

    private fun failPairing(error: PairingError) {
        val base = _step.value as? Step.EnterPairingCode
            ?: lastEntry
            ?: Step.EnterPairingCode()
        _step.value = base.copy(error = error)
    }

    private suspend fun finishProbe() {
        _step.value = Step.Probing
        val capabilities = try {
            bridge.probeCapabilities()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            emptySet() // a broken probe must not strand the wizard mid-state
        } finally {
            // Security invariant: shell uid is never held open in the background. The bridge
            // reconnects at transfer time with the persisted pairing key.
            bridge.disconnect()
        }
        _step.value = Step.Ready(capabilities)
    }

    private fun cancelDetection() {
        detection?.cancel()
        detection = null
    }

    companion object {
        private const val DETECT_TIMEOUT_MS = 120_000L // the user is busy reading the pairing dialog
        // Android Wireless Debugging pairing-code length; the app-recv renderer validates against this.
        const val PAIRING_CODE_LENGTH = 6
        // Highest valid TCP port; the app-recv renderer reuses this to gate Pair (no second literal).
        const val MAX_PORT = 65535
    }
}

/** Shared wizard copy. GOS wording is mandated by ADR-001 §2 / the refactor brief — keep exact. */
object WizardCopy {
    const val GOS_REBOOT_WARNING =
        "Wireless Debugging turns off on reboot. To re-enable: Settings → Developer options → " +
            "Wireless debugging → turn on, then re-enter a pairing code. You will not need " +
            "to re-pair from scratch."
}
