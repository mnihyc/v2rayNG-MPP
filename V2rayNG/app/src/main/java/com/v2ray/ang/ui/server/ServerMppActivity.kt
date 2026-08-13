package com.v2ray.ang.ui.server

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.MppAdvancedConfig
import com.v2ray.ang.dto.entities.MppPathConfig
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.mpp.MppConfigRenderer
import com.v2ray.ang.mpp.MppMaterialCodec
import com.v2ray.ang.mpp.MppPathParser
import com.v2ray.ang.mpp.MppPathUnderlay
import com.v2ray.ang.mpp.MppProfileValidator
import com.v2ray.ang.mpp.MppValidationError
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.ManagedContentField
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.util.Utils
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ServerMppActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.MPP

    private var pendingContentImport: ((ByteArray) -> Unit)? = null
    private val contentImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val consumer = pendingContentImport
        pendingContentImport = null
        if (uri == null || consumer == null) return@registerForActivityResult

        try {
            val content = contentResolver.openInputStream(uri)?.use(::readLimited)
                ?: error("selected content could not be opened")
            consumer(content)
        } catch (_: ContentTooLargeException) {
            toast(R.string.server_mpp_import_too_large)
        } catch (_: Exception) {
            // Never log a URI or the first-class material content.
            toast(R.string.server_mpp_import_failed)
        }
    }

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig)
        }.apply {
            configType = EConfigType.MPP
        }

        // A null path list belongs to the original fixed TCP/UDP schema. Normalize it only when
        // the structured editor is actually shown so raw-TOML profiles remain byte-for-byte raw.
        LaunchedEffect(uiState.mppConfig.useRawToml) {
            val config = uiState.mppConfig
            if (!config.useRawToml && config.paths == null) {
                // The legacy schema kept the endpoint host in ProfileItem.server. A freshly
                // created MPP profile has no server yet, and the structured editor no longer has
                // a second top-level Address authority. Seed a valid, immediately editable host
                // so a new profile opens with guided path controls instead of an invalid raw URI.
                val seedHost = uiState.address.ifBlank { DEFAULT_NEW_PROFILE_HOST }
                val paths = config.effectivePaths(seedHost)
                uiState.mppConfig = config.copy(paths = paths)
                uiState.syncFromPaths(paths)
            }
        }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) },
        ) {
            FormTextField(
                label = stringResource(R.string.server_lab_remarks),
                value = uiState.remarks,
                onValueChange = { uiState.remarks = it },
            )
            MppProtocolFields(uiState)
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        val mpp = config.mpp ?: run {
            toast(R.string.server_mpp_error_path_required)
            return false
        }
        val error = MppProfileValidator.validate(mpp) ?: return true
        toast(
            when (error) {
                MppValidationError.PATH_REQUIRED -> R.string.server_mpp_error_path_required
                MppValidationError.PATH_COUNT -> R.string.server_mpp_error_path_count
                MppValidationError.PATH_NAME -> R.string.server_mpp_error_path_name
                MppValidationError.PATH_ENDPOINT -> R.string.server_mpp_error_path_endpoint
                MppValidationError.PATH_CARRIER_LIMIT ->
                    R.string.server_mpp_error_path_carrier_limit
                MppValidationError.ADVANCED_TUNING ->
                    R.string.server_mpp_error_advanced_tuning
                MppValidationError.TCP_PORT -> R.string.server_mpp_error_tcp_port
                MppValidationError.UDP_PORT -> R.string.server_mpp_error_udp_port
                MppValidationError.TCP_CARRIER_COUNT -> R.string.server_mpp_error_tcp_carriers
                MppValidationError.CREDENTIAL_ID -> R.string.server_mpp_error_credential_id
                MppValidationError.PRINCIPAL_ID -> R.string.server_mpp_error_principal_id
                MppValidationError.CREDENTIAL_SECRET -> R.string.server_mpp_error_credential_secret
                MppValidationError.CERTIFICATE -> R.string.server_mpp_error_certificate
                MppValidationError.TRANSPORT_SECRET -> R.string.server_mpp_error_transport_secret
                MppValidationError.RAW_TOML -> R.string.server_mpp_error_raw_toml
                MppValidationError.RAW_CREDENTIAL_TOKEN ->
                    R.string.server_mpp_error_raw_credential_token
                MppValidationError.RAW_CERTIFICATE_TOKEN ->
                    R.string.server_mpp_error_raw_certificate_token
                MppValidationError.RAW_TRANSPORT_TOKEN ->
                    R.string.server_mpp_error_raw_transport_token
                MppValidationError.RAW_SOCKS_PORT_TOKEN ->
                    R.string.server_mpp_error_raw_socks_token
                MppValidationError.RAW_LOCAL_AUTH_TOKENS ->
                    R.string.server_mpp_error_raw_local_auth_tokens
            }
        )
        return false
    }

    @Composable
    private fun MppProtocolFields(state: ServerUiState) {
        val config = state.mppConfig
        SettingsSwitchItem(
            title = stringResource(R.string.server_mpp_use_raw_toml),
            checked = config.useRawToml,
            onCheckedChange = { enabled ->
                val rawToml = if (enabled && config.rawToml.isBlank()) {
                    runCatching {
                        MppConfigRenderer.renderEditableTemplate(state.address, config)
                    }.getOrDefault("")
                } else {
                    config.rawToml
                }
                val paths = if (!enabled && config.paths == null) {
                    config.effectivePaths(state.address)
                } else {
                    config.paths
                }
                state.mppConfig = config.copy(
                    paths = paths,
                    useRawToml = enabled,
                    rawToml = rawToml,
                )
                if (!enabled) state.syncFromPaths(paths.orEmpty())
            },
        )

        if (config.useRawToml) {
            ManagedContentField(
                label = stringResource(R.string.server_mpp_raw_toml),
                value = config.rawToml,
                onValueChange = { state.updateMpp { copy(rawToml = it) } },
                onCopyClick = { copyContent(config.rawToml) },
                onImportClick = {
                    importTextContent { state.updateMpp { copy(rawToml = it) } }
                },
                sensitive = false,
                minLines = 16,
                maxLines = 24,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            )
            HelpText(stringResource(R.string.server_mpp_raw_toml_hint))
        } else {
            StructuredFields(state, config)
        }

        HelpText(stringResource(R.string.server_mpp_material_hint))
        ManagedContentField(
            label = stringResource(R.string.server_mpp_credential_secret),
            value = config.credentialSecret,
            onValueChange = { state.updateMpp { copy(credentialSecret = it) } },
            onCopyClick = { copyContent(config.credentialSecret) },
            onImportClick = {
                importTextContent { state.updateMpp { copy(credentialSecret = it) } }
            },
        )
        ManagedContentField(
            label = stringResource(R.string.server_mpp_pinned_certificate),
            value = config.pinnedCertificatePem,
            onValueChange = { state.updateMpp { copy(pinnedCertificatePem = it) } },
            onCopyClick = { copyContent(config.pinnedCertificatePem) },
            onImportClick = {
                importTextContent { state.updateMpp { copy(pinnedCertificatePem = it) } }
            },
            minLines = 6,
            maxLines = 12,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        )
        ManagedContentField(
            label = stringResource(R.string.server_mpp_transport_secret),
            value = config.transportSecret,
            onValueChange = { state.updateMpp { copy(transportSecret = it) } },
            onCopyClick = { copyContent(config.transportSecret) },
            onImportClick = {
                importBinaryContent {
                    state.updateMpp { copy(transportSecret = MppMaterialCodec.encodeImportedBinary(it)) }
                }
            },
        )
        HelpText(stringResource(R.string.server_mpp_transport_secret_hint))
    }

    @Composable
    private fun StructuredFields(state: ServerUiState, config: MppProfileConfig) {
        val paths = config.effectivePaths(state.address)

        Text(
            text = stringResource(R.string.server_mpp_paths),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        HelpText(stringResource(R.string.server_mpp_paths_hint))

        paths.forEachIndexed { index, path ->
            MppPathCard(
                index = index,
                path = path,
                canMoveUp = index > 0,
                canMoveDown = index < paths.lastIndex,
                onNameChange = { name ->
                    state.setStructuredPaths(paths.updated(index, path.copy(name = name)))
                },
                onEndpointChange = { endpoint ->
                    state.setStructuredPaths(paths.updated(index, path.copy(endpoint = endpoint)))
                },
                onMoveUp = {
                    state.setStructuredPaths(paths.moved(index, index - 1))
                },
                onMoveDown = {
                    state.setStructuredPaths(paths.moved(index, index + 1))
                },
                onDuplicate = {
                    val copy = path.copy(name = uniquePathName(paths, path.name.ifBlank { "path" }))
                    state.setStructuredPaths(paths.toMutableList().apply { add(index + 1, copy) })
                },
                onDelete = {
                    state.setStructuredPaths(paths.toMutableList().apply { removeAt(index) })
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    state.setStructuredPaths(paths + newPath(paths, state, "tcp"))
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(painterResource(R.drawable.ic_add_24dp), contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.server_mpp_add_tcp_path))
            }
            OutlinedButton(
                onClick = {
                    state.setStructuredPaths(paths + newPath(paths, state, "udp"))
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(painterResource(R.drawable.ic_add_24dp), contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.server_mpp_add_quic_path))
            }
        }

        HelpText(stringResource(R.string.server_mpp_path_options_hint))
        RuntimeTuningSection(
            advanced = config.advanced,
            onAdvancedUpdate = { update ->
                state.updateMpp {
                    copy(advanced = update(advanced ?: MppAdvancedConfig()))
                }
            },
        )
        FormTextField(
            label = stringResource(R.string.server_mpp_credential_id),
            value = config.credentialId,
            onValueChange = { state.updateMpp { copy(credentialId = it) } },
        )
        FormTextField(
            label = stringResource(R.string.server_mpp_principal_id),
            value = config.principalId,
            onValueChange = { state.updateMpp { copy(principalId = it) } },
        )
        FormTextField(
            label = stringResource(R.string.server_mpp_tls_server_name),
            value = config.tlsServerName,
            onValueChange = { state.updateMpp { copy(tlsServerName = it) } },
        )
    }

    @Composable
    private fun RuntimeTuningSection(
        advanced: MppAdvancedConfig?,
        onAdvancedUpdate: ((MppAdvancedConfig) -> MppAdvancedConfig) -> Unit,
    ) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val resolved = advanced ?: MppAdvancedConfig()

        // Keep the user's in-progress text independent of the numeric profile model. Parseable
        // edits are committed immediately; an incomplete or overflowing value stays visible until
        // the user finishes correcting it instead of snapping back to the previous number.
        var probeInterval by rememberSaveable {
            mutableStateOf(resolved.pathProbeIntervalMs.toString())
        }
        var probeTimeout by rememberSaveable {
            mutableStateOf(resolved.pathProbeTimeoutMs.toString())
        }
        var extraTraffic by rememberSaveable {
            mutableStateOf(resolved.extraTrafficHintPercent.toString())
        }
        var authFreshness by rememberSaveable {
            mutableStateOf(resolved.authFreshnessWindowSeconds.toString())
        }
        var sessionRetention by rememberSaveable {
            mutableStateOf(resolved.sessionRetentionTimeoutMs.toString())
        }
        var tcpHeartbeatInterval by rememberSaveable {
            mutableStateOf(resolved.tcpHeartbeatIntervalMs.toString())
        }
        var tcpHeartbeatTimeout by rememberSaveable {
            mutableStateOf(resolved.tcpHeartbeatTimeoutMs.toString())
        }
        var quicKeepAlive by rememberSaveable {
            mutableStateOf(resolved.quicKeepAliveIntervalMs.toString())
        }
        var quicIdleTimeout by rememberSaveable {
            mutableStateOf(resolved.quicIdleTimeoutMs.toString())
        }

        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = stringResource(R.string.server_mpp_runtime_tuning),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            if (advanced == null) {
                                R.string.server_mpp_runtime_tuning_defaults
                            } else {
                                R.string.server_mpp_runtime_tuning_custom
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more_24dp),
                    contentDescription = null,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }

            if (expanded) {
                HelpText(stringResource(R.string.server_mpp_runtime_tuning_hint))
                CompactFieldRow {
                    CompactTextField(
                        label = stringResource(R.string.server_mpp_probe_interval),
                        value = probeInterval,
                        onValueChange = { value ->
                            probeInterval = value
                            value.toLongOrNull()?.let { parsed ->
                                onAdvancedUpdate { it.copy(pathProbeIntervalMs = parsed) }
                            }
                        },
                        isError = !probeInterval.isPositiveLong(),
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    CompactTextField(
                        label = stringResource(R.string.server_mpp_probe_timeout),
                        value = probeTimeout,
                        onValueChange = { value ->
                            probeTimeout = value
                            value.toLongOrNull()?.let { parsed ->
                                onAdvancedUpdate { it.copy(pathProbeTimeoutMs = parsed) }
                            }
                        },
                        isError = !probeTimeout.isPositiveLong(),
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                HelpText(stringResource(R.string.server_mpp_probe_hint))

                CompactFieldRow {
                    CompactTextField(
                        label = stringResource(R.string.server_mpp_extra_traffic),
                        value = extraTraffic,
                        onValueChange = { value ->
                            extraTraffic = value
                            value.toIntOrNull()?.let { parsed ->
                                onAdvancedUpdate { it.copy(extraTrafficHintPercent = parsed) }
                            }
                        },
                        isError = extraTraffic.toIntOrNull()?.let {
                            it !in 0..MppAdvancedConfig.MAX_EXTRA_TRAFFIC_HINT_PERCENT
                        } != false,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    CompactTextField(
                        label = stringResource(R.string.server_mpp_auth_freshness),
                        value = authFreshness,
                        onValueChange = { value ->
                            authFreshness = value
                            value.toLongOrNull()?.let { parsed ->
                                onAdvancedUpdate { it.copy(authFreshnessWindowSeconds = parsed) }
                            }
                        },
                        isError = !authFreshness.isPositiveLong(),
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                HelpText(stringResource(R.string.server_mpp_performance_auth_hint))

                CompactFieldRow {
                    CompactTextField(
                        label = stringResource(R.string.server_mpp_session_retention),
                        value = sessionRetention,
                        onValueChange = { value ->
                            sessionRetention = value
                            value.toLongOrNull()?.let { parsed ->
                                onAdvancedUpdate { it.copy(sessionRetentionTimeoutMs = parsed) }
                            }
                        },
                        isError = !sessionRetention.isPositiveLong(),
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                HelpText(stringResource(R.string.server_mpp_session_retention_hint))

                CompactFieldRow {
                    CompactTextField(
                        label = stringResource(R.string.server_mpp_tcp_heartbeat_interval),
                        value = tcpHeartbeatInterval,
                        onValueChange = { value ->
                            tcpHeartbeatInterval = value
                            value.toLongOrNull()?.let { parsed ->
                                onAdvancedUpdate { it.copy(tcpHeartbeatIntervalMs = parsed) }
                            }
                        },
                        isError = !tcpHeartbeatInterval.isPositiveLong(),
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    CompactTextField(
                        label = stringResource(R.string.server_mpp_tcp_heartbeat_timeout),
                        value = tcpHeartbeatTimeout,
                        onValueChange = { value ->
                            tcpHeartbeatTimeout = value
                            value.toLongOrNull()?.let { parsed ->
                                onAdvancedUpdate { it.copy(tcpHeartbeatTimeoutMs = parsed) }
                            }
                        },
                        isError = !tcpHeartbeatTimeout.isPositiveLong() ||
                                tcpHeartbeatTimeout.toLongOrNull()?.let { timeout ->
                                    tcpHeartbeatInterval.toLongOrNull()?.let { interval ->
                                        timeout < interval
                                    }
                                } != false,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                HelpText(stringResource(R.string.server_mpp_tcp_heartbeat_hint))

                CompactFieldRow {
                    CompactTextField(
                        label = stringResource(R.string.server_mpp_quic_keepalive_interval),
                        value = quicKeepAlive,
                        onValueChange = { value ->
                            quicKeepAlive = value
                            value.toLongOrNull()?.let { parsed ->
                                onAdvancedUpdate { it.copy(quicKeepAliveIntervalMs = parsed) }
                            }
                        },
                        isError = !quicKeepAlive.isPositiveLong(),
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    CompactTextField(
                        label = stringResource(R.string.server_mpp_quic_idle_timeout),
                        value = quicIdleTimeout,
                        onValueChange = { value ->
                            quicIdleTimeout = value
                            value.toLongOrNull()?.let { parsed ->
                                onAdvancedUpdate { it.copy(quicIdleTimeoutMs = parsed) }
                            }
                        },
                        isError = quicIdleTimeout.toLongOrNull()?.let { timeout ->
                            val keepAlive = quicKeepAlive.toLongOrNull()
                            timeout <= 0L ||
                                    timeout > MppAdvancedConfig.MAX_QUIC_IDLE_TIMEOUT_MS ||
                                    keepAlive == null || timeout <= keepAlive
                        } != false,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                HelpText(stringResource(R.string.server_mpp_quic_keepalive_hint))
            }
        }
    }

    @Composable
    private fun MppPathCard(
        index: Int,
        path: MppPathConfig,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onNameChange: (String) -> Unit,
        onEndpointChange: (String) -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onDuplicate: () -> Unit,
        onDelete: () -> Unit,
    ) {
        var optionsExpanded by rememberSaveable { mutableStateOf(false) }
        var endpointExpanded by rememberSaveable { mutableStateOf(false) }
        var guidedDraft by rememberSaveable { mutableStateOf(false) }
        val strictEndpoint = MppEndpointUriEditor.parse(path.endpoint)
        val endpoint = strictEndpoint ?: if (guidedDraft) {
            MppEndpointUriEditor.parseDraft(path.endpoint)
        } else {
            null
        }

        fun applyGuidedEndpoint(rewritten: String?) {
            if (rewritten == null) return
            guidedDraft = MppEndpointUriEditor.parse(rewritten) == null
            onEndpointChange(rewritten)
        }

        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.server_mpp_path_number, index + 1),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(
                                when (endpoint?.underlay) {
                                    MppPathUnderlay.TCP ->
                                        R.string.server_mpp_path_type_tcp
                                    MppPathUnderlay.UDP ->
                                        R.string.server_mpp_path_type_quic
                                    null -> R.string.server_mpp_path_type_invalid
                                }
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_upward_24dp),
                            contentDescription = stringResource(
                                R.string.server_mpp_move_path_up,
                                index + 1,
                            ),
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_downward_24dp),
                            contentDescription = stringResource(
                                R.string.server_mpp_move_path_down,
                                index + 1,
                            ),
                        )
                    }
                    IconButton(onClick = onDuplicate) {
                        Icon(
                            painterResource(R.drawable.ic_copy),
                            contentDescription = stringResource(
                                R.string.server_mpp_duplicate_path,
                                index + 1,
                            ),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            painterResource(R.drawable.ic_delete_24dp),
                            contentDescription = stringResource(
                                R.string.server_mpp_delete_path,
                                index + 1,
                            ),
                        )
                    }
                }

                if (endpoint == null) {
                    CompactFieldRow {
                        CompactTextField(
                            label = stringResource(R.string.server_mpp_path_name),
                            value = path.name,
                            onValueChange = onNameChange,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = stringResource(R.string.server_mpp_guided_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                } else {
                    CompactFieldRow {
                        CompactTextField(
                            label = stringResource(R.string.server_mpp_path_name),
                            value = path.name,
                            onValueChange = onNameChange,
                            modifier = Modifier.weight(1.25f),
                        )
                        CompactDropdownField(
                            label = stringResource(R.string.server_mpp_transport),
                            value = endpoint.underlay,
                            options = listOf(
                                SelectOption(
                                    MppPathUnderlay.TCP,
                                    stringResource(R.string.server_mpp_transport_tcp),
                                ),
                                SelectOption(
                                    MppPathUnderlay.UDP,
                                    stringResource(R.string.server_mpp_transport_quic),
                                ),
                            ),
                            onValueChange = { underlay ->
                                applyGuidedEndpoint(
                                    MppEndpointUriEditor.withUnderlay(
                                        path.endpoint,
                                        underlay,
                                        allowDraftSource = guidedDraft,
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    CompactFieldRow {
                        CompactTextField(
                            label = stringResource(R.string.server_mpp_host),
                            value = endpoint.host,
                            onValueChange = { host ->
                                applyGuidedEndpoint(
                                    MppEndpointUriEditor.withHost(
                                        path.endpoint,
                                        host,
                                        allowDraftSource = guidedDraft,
                                    )
                                )
                            },
                            modifier = Modifier.weight(1.35f),
                        )
                        CompactTextField(
                            label = stringResource(R.string.server_mpp_ports),
                            value = endpoint.ports,
                            onValueChange = { ports ->
                                applyGuidedEndpoint(
                                    MppEndpointUriEditor.withPorts(
                                        path.endpoint,
                                        ports,
                                        allowDraftSource = guidedDraft,
                                    )
                                )
                            },
                            keyboardType = KeyboardType.Ascii,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    CompactFieldRow(verticalPadding = 0.dp) {
                        if (endpoint.underlay == MppPathUnderlay.TCP) {
                            CompactTextField(
                                label = stringResource(R.string.server_mpp_tcp_carrier_max),
                                value = endpoint.tcpCarrierMaxText,
                                onValueChange = { count ->
                                    applyGuidedEndpoint(
                                        MppEndpointUriEditor.withScalarOption(
                                            path.endpoint,
                                            "tcp-carriers",
                                            "1-$count",
                                            allowDraftSource = guidedDraft,
                                        )
                                    )
                                },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(.8f),
                            )
                        }
                        CompactSwitch(
                            title = stringResource(R.string.server_mpp_backup),
                            checked = endpoint.booleanOption("backup"),
                            onCheckedChange = { enabled ->
                                applyGuidedEndpoint(
                                    MppEndpointUriEditor.withBooleanOption(
                                        path.endpoint,
                                        "backup",
                                        enabled,
                                        allowDraftSource = guidedDraft,
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        CompactSwitch(
                            title = stringResource(R.string.server_mpp_expensive),
                            checked = endpoint.booleanOption("expensive"),
                            onCheckedChange = { enabled ->
                                applyGuidedEndpoint(
                                    MppEndpointUriEditor.withBooleanOption(
                                        path.endpoint,
                                        "expensive",
                                        enabled,
                                        allowDraftSource = guidedDraft,
                                    )
                                )
                            },
                            modifier = Modifier.weight(1.15f),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ExpandButton(
                            label = stringResource(R.string.server_mpp_path_options),
                            expanded = optionsExpanded,
                            onClick = { optionsExpanded = !optionsExpanded },
                        )
                        ExpandButton(
                            label = stringResource(R.string.server_mpp_advanced_endpoint),
                            expanded = endpointExpanded,
                            onClick = { endpointExpanded = !endpointExpanded },
                        )
                    }
                    if (optionsExpanded) {
                        PathOptionFields(
                            endpointUri = path.endpoint,
                            endpoint = endpoint,
                            allowDraftSource = guidedDraft,
                            onEndpointChange = ::applyGuidedEndpoint,
                        )
                    }
                    if (strictEndpoint == null) {
                        Text(
                            text = stringResource(R.string.server_mpp_guided_draft),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                }

                if (strictEndpoint == null || endpointExpanded) {
                    FormTextField(
                        label = stringResource(R.string.server_mpp_advanced_endpoint),
                        value = path.endpoint,
                        onValueChange = { value ->
                            guidedDraft = false
                            onEndpointChange(value)
                        },
                        placeholder = stringResource(R.string.server_mpp_path_endpoint_example),
                        minLines = 2,
                        maxLines = 4,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }

    @Composable
    private fun PathOptionFields(
        endpointUri: String,
        endpoint: MppEditableEndpoint,
        allowDraftSource: Boolean,
        onEndpointChange: (String?) -> Unit,
    ) {
        fun setScalar(key: String, value: String) {
            MppEndpointUriEditor.withScalarOption(
                endpointUri,
                key,
                value.takeIf { it.isNotBlank() },
                allowDraftSource = allowDraftSource,
            ).let(onEndpointChange)
        }

        CompactFieldRow {
            CompactTextField(
                label = stringResource(R.string.server_mpp_source_ip),
                value = endpoint.optionValue("source-ip"),
                onValueChange = { setScalar("source-ip", it) },
                modifier = Modifier.weight(1.2f),
            )
            CompactTextField(
                label = stringResource(R.string.server_mpp_port_hop_interval),
                value = endpoint.optionValue("port-hop-interval-ms"),
                onValueChange = { setScalar("port-hop-interval-ms", it) },
                enabled = '-' in endpoint.ports,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
        CompactFieldRow {
            CompactTextField(
                label = stringResource(R.string.server_mpp_srtt),
                value = endpoint.optionValue("srtt-ms"),
                onValueChange = { setScalar("srtt-ms", it) },
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            CompactTextField(
                label = stringResource(R.string.server_mpp_jitter),
                value = endpoint.optionValue("jitter-ms"),
                onValueChange = { setScalar("jitter-ms", it) },
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            CompactTextField(
                label = stringResource(R.string.server_mpp_datagram_limit),
                value = endpoint.optionValue("datagram-payload-limit"),
                onValueChange = { setScalar("datagram-payload-limit", it) },
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1.15f),
            )
        }

        val rateMode = endpoint.rateMode
        CompactFieldRow {
            CompactDropdownField(
                label = stringResource(R.string.server_mpp_startup_rate),
                value = rateMode,
                options = MppRateMode.entries.map { mode ->
                    SelectOption(mode, stringResource(mode.labelResource))
                },
                onValueChange = { mode ->
                    val existingNumeric = endpoint.rateValue.takeIf { rateMode.isNumeric }
                    val (key, value) = when (mode) {
                        MppRateMode.DEFAULT -> null to null
                        MppRateMode.UNKNOWN -> "rate" to "unknown"
                        MppRateMode.UNLIMITED -> "rate" to "unlimited"
                        else -> mode.optionKey to (existingNumeric ?: "1")
                    }
                    onEndpointChange(
                        MppEndpointUriEditor.withRateOption(
                            endpointUri,
                            key,
                            value,
                            allowDraftSource = allowDraftSource,
                        )
                    )
                },
                modifier = Modifier.weight(1.2f),
            )
            if (rateMode.isNumeric) {
                CompactTextField(
                    label = stringResource(R.string.server_mpp_rate_value),
                    value = endpoint.rateValue,
                    onValueChange = { value ->
                        onEndpointChange(
                            MppEndpointUriEditor.withRateOption(
                                endpointUri,
                                rateMode.optionKey,
                                value,
                                allowDraftSource = allowDraftSource,
                            )
                        )
                    },
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        CompactFieldRow(verticalPadding = 0.dp) {
            CompactSwitch(
                title = stringResource(R.string.server_mpp_bulk_allowed),
                checked = endpoint.booleanOption("bulk-allowed"),
                onCheckedChange = { enabled ->
                    MppEndpointUriEditor.withBooleanOption(
                        endpointUri,
                        "bulk-allowed",
                        enabled,
                        allowDraftSource = allowDraftSource,
                    ).let(onEndpointChange)
                },
                modifier = Modifier.weight(1f),
            )
            CompactSwitch(
                title = stringResource(R.string.server_mpp_probe_only),
                checked = endpoint.booleanOption("probe-only"),
                onCheckedChange = { enabled ->
                    MppEndpointUriEditor.withBooleanOption(
                        endpointUri,
                        "probe-only",
                        enabled,
                        allowDraftSource = allowDraftSource,
                    ).let(onEndpointChange)
                },
                modifier = Modifier.weight(1f),
            )
            if (endpoint.underlay == MppPathUnderlay.TCP) {
                CompactSwitch(
                    title = stringResource(R.string.server_mpp_no_udp),
                    checked = endpoint.booleanOption("no-udp"),
                    onCheckedChange = { enabled ->
                        MppEndpointUriEditor.withBooleanOption(
                            endpointUri,
                            "no-udp",
                            enabled,
                            allowDraftSource = allowDraftSource,
                        ).let(onEndpointChange)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    @Composable
    private fun CompactFieldRow(
        verticalPadding: androidx.compose.ui.unit.Dp = 3.dp,
        content: @Composable RowScope.() -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }

    @Composable
    private fun CompactTextField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier,
        enabled: Boolean = true,
        isError: Boolean = false,
        keyboardType: KeyboardType = KeyboardType.Text,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            enabled = enabled,
            isError = isError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = modifier,
        )
    }

    private fun String.isPositiveLong(): Boolean = toLongOrNull()?.let { it > 0L } == true

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun <T> CompactDropdownField(
        label: String,
        value: T,
        options: List<SelectOption<T>>,
        onValueChange: (T) -> Unit,
        modifier: Modifier,
    ) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier,
        ) {
            OutlinedTextField(
                value = options.firstOrNull { it.value == value }?.label.orEmpty(),
                onValueChange = {},
                label = { Text(label) },
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onValueChange(option.value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun CompactSwitch(
        title: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        modifier: Modifier,
    ) {
        Row(
            modifier = modifier
                .heightIn(min = 48.dp)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = null)
        }
    }

    @Composable
    private fun ExpandButton(label: String, expanded: Boolean, onClick: () -> Unit) {
        TextButton(onClick = onClick) {
            Text(label)
            Icon(
                painter = painterResource(R.drawable.ic_expand_more_24dp),
                contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
    }

    private data class SelectOption<T>(val value: T, val label: String)

    private enum class MppRateMode(
        val labelResource: Int,
        val optionKey: String? = null,
    ) {
        DEFAULT(R.string.server_mpp_rate_default),
        UNKNOWN(R.string.server_mpp_rate_unknown, "rate"),
        UNLIMITED(R.string.server_mpp_rate_unlimited, "rate"),
        BPS(R.string.server_mpp_rate_bps, "rate-bps"),
        KBPS(R.string.server_mpp_rate_kbps, "rate-kbps"),
        MBPS(R.string.server_mpp_rate_mbps, "rate-mbps"),
        ;

        val isNumeric: Boolean
            get() = this == BPS || this == KBPS || this == MBPS
    }

    private fun MppEditableEndpoint.optionValue(key: String): String =
        options.lastOrNull { it.key == key }?.value.orEmpty()

    private fun MppEditableEndpoint.booleanOption(key: String): Boolean =
        options.lastOrNull { it.key == key }?.let { it.value == null || it.value == "true" }
            ?: false

    private val MppEditableEndpoint.tcpCarrierMaxText: String
        get() {
            val value = options.lastOrNull { it.key == "tcp-carriers" }?.value
                ?: return MppProfileConfig.DEFAULT_TCP_CARRIER_COUNT.toString()
            return value.substringAfterLast('-')
        }

    private val MppEditableEndpoint.rateMode: MppRateMode
        get() {
            val option = options.firstOrNull { it.key in RATE_OPTION_KEYS }
                ?: return MppRateMode.DEFAULT
            return when (option.key) {
                "rate-bps" -> MppRateMode.BPS
                "rate-kbps" -> MppRateMode.KBPS
                "rate-mbps" -> MppRateMode.MBPS
                "rate" -> if (option.value == "unlimited") {
                    MppRateMode.UNLIMITED
                } else {
                    MppRateMode.UNKNOWN
                }
                else -> MppRateMode.DEFAULT
            }
        }

    private val MppEditableEndpoint.rateValue: String
        get() = options.firstOrNull { it.key in NUMERIC_RATE_OPTION_KEYS }?.value.orEmpty()

    @Composable
    private fun HelpText(value: String) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
    }

    private fun ServerUiState.updateMpp(update: MppProfileConfig.() -> MppProfileConfig) {
        mppConfig = mppConfig.update()
        val paths = mppConfig.paths
        if (paths == null) {
            port = mppConfig.primaryPort().toString()
        } else {
            syncFromPaths(paths)
        }
    }

    private fun ServerUiState.setStructuredPaths(paths: List<MppPathConfig>) {
        mppConfig = mppConfig.copy(paths = paths)
        syncFromPaths(paths)
    }

    /** Keep legacy ProfileItem summary fields valid without making them editable authorities. */
    private fun ServerUiState.syncFromPaths(paths: List<MppPathConfig>) {
        paths.firstNotNullOfOrNull { MppPathParser.parse(it.endpoint) }?.let { endpoint ->
            address = endpoint.host
            port = endpoint.firstPort.toString()
        }
    }

    private fun copyContent(content: String) {
        if (content.isEmpty()) return
        Utils.setClipboard(this, content)
        toastSuccess(R.string.toast_content_copied)
    }

    private fun importTextContent(onImported: (String) -> Unit) {
        importBinaryContent { onImported(it.toString(Charsets.UTF_8)) }
    }

    private fun importBinaryContent(onImported: (ByteArray) -> Unit) {
        pendingContentImport = onImported
        contentImportLauncher.launch(arrayOf("*/*"))
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_IMPORTED_CONTENT_BYTES) throw ContentTooLargeException()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class ContentTooLargeException : Exception()

    companion object {
        private const val MAX_IMPORTED_CONTENT_BYTES = 1024 * 1024
        private const val DEFAULT_NEW_PROFILE_HOST = "server.example.com"
        private val RATE_OPTION_KEYS = setOf("rate", "rate-bps", "rate-kbps", "rate-mbps")
        private val NUMERIC_RATE_OPTION_KEYS = setOf("rate-bps", "rate-kbps", "rate-mbps")

        private fun newPath(
            paths: List<MppPathConfig>,
            state: ServerUiState,
            scheme: String,
        ): MppPathConfig {
            val identity = paths.firstNotNullOfOrNull { MppPathParser.parse(it.endpoint) }
            val host = identity?.host ?: state.address.trim().removeSurrounding("[", "]")
            val endpointHost = if (':' in host) "[$host]" else host
            val port = identity?.firstPort ?: state.port.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: MppProfileConfig.DEFAULT_SERVER_PORT
            val baseName = if (scheme == "tcp") "path-tcp" else "path-udp"
            val options = if (scheme == "tcp") {
                "?tcp-carriers=1-${MppProfileConfig.DEFAULT_TCP_CARRIER_COUNT}"
            } else {
                ""
            }
            return MppPathConfig(
                name = uniquePathName(paths, baseName),
                endpoint = "$scheme://$endpointHost:$port$options",
            )
        }

        private fun uniquePathName(paths: List<MppPathConfig>, requestedBase: String): String {
            val base = requestedBase.takeIf {
                it.length <= 56 && MppPathParser.isCanonicalName(it)
            }
                ?: "path"
            val used = paths.mapTo(HashSet()) { it.name }
            if (base !in used) return base
            var suffix = 2
            while ("$base-$suffix" in used) suffix++
            return "$base-$suffix"
        }

        private fun List<MppPathConfig>.updated(index: Int, value: MppPathConfig) =
            toMutableList().apply { this[index] = value }

        private fun List<MppPathConfig>.moved(from: Int, to: Int): List<MppPathConfig> =
            toMutableList().apply {
                if (from !in indices || to !in indices || from == to) return@apply
                add(to, removeAt(from))
            }
    }
}
