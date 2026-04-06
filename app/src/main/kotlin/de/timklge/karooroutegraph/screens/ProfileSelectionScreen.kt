package de.timklge.karooroutegraph.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.timklge.karooroutegraph.KarooRouteGraphExtension
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import de.timklge.karooroutegraph.streamActiveRideProfile
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSelectionScreen(
    onBack: () -> Unit
) {
    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    var showNameDialog by remember { mutableStateOf(false) }
    var profileNameInput by remember { mutableStateOf("") }
    var currentProfileId by remember { mutableStateOf<String?>(null) }
    var currentProfileName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val activeProfile = karooSystemServiceProvider.karooSystemService.streamActiveRideProfile().first()
        currentProfileId = activeProfile.profile.id
        currentProfileName = activeProfile.profile.name

        // Check for custom name
        karooSystemServiceProvider.getProfileDisplayName(activeProfile.profile.id).first()?.let { customName ->
            currentProfileName = customName
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.ride_profile)) }) },
        content = { padding ->
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            if (currentProfileId != null) {
                                Column {
                                    Text(
                                        text = "Active Karoo profile: $currentProfileName",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "POI settings for this profile are used automatically when you switch profiles on your Karoo device.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    text = "No active ride profile. Start a ride or switch to a ride profile on your Karoo device.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        item {
                            Button(
                                enabled = currentProfileId != null,
                                onClick = {
                                    profileNameInput = currentProfileName ?: ""
                                    showNameDialog = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text("Set custom name for this profile")
                            }
                        }

                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "How it works",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "• Switch ride profiles on your Karoo device\n• The app detects the change automatically\n• Each profile gets its own POI settings\n• Set a custom name to distinguish profiles",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, start = 28.dp)
                                )
                            }
                        }
                    }
                }

                FixedBackButton(
                    modifier = Modifier.align(Alignment.BottomStart),
                    onBack = onBack
                )
            }
        }
    )

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Custom Profile Name") },
            text = {
                OutlinedTextField(
                    value = profileNameInput,
                    onValueChange = { profileNameInput = it },
                    label = { Text("Enter name (e.g. Road Race, Gravel Adventure)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = profileNameInput.isNotBlank(),
                    onClick = {
                        val name = profileNameInput.trim()
                        val id = currentProfileId ?: return@TextButton
                        karooSystemServiceProvider.setProfileDisplayName(id, name)
                        currentProfileName = name
                        showNameDialog = false
                        profileNameInput = ""
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false; profileNameInput = "" }) {
                    Text("Cancel")
                }
            }
        )
    }
}
