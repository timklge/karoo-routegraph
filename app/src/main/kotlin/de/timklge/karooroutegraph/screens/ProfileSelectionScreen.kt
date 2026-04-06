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
import de.timklge.karooroutegraph.streamUserProfile
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
    var currentProfileHash by remember { mutableStateOf<String?>(null) }
    var currentProfileName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val profile = karooSystemServiceProvider.karooSystemService.streamUserProfile().first()
        val hash = karooSystemServiceProvider.computeProfileHash(profile)
        currentProfileHash = hash

        val knownName = karooSystemServiceProvider.getProfileNameForHash(hash).first()
        currentProfileName = knownName
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
                            if (currentProfileName != null) {
                                Column {
                                    Text(
                                        text = stringResource(R.string.current_profile, currentProfileName!!),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "POI settings for this profile will be used automatically",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Column {
                                    Text(
                                        text = "New ride profile detected",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "Give this Karoo ride profile a name so the app can remember your POI settings for it.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        item {
                            Button(
                                onClick = {
                                    if (currentProfileName == null) {
                                        showNameDialog = true
                                    } else {
                                        // Allow renaming existing profile
                                        profileNameInput = currentProfileName ?: ""
                                        showNameDialog = true
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(if (currentProfileName == null) "Name this profile" else "Rename profile")
                            }
                        }

                        item {
                            Text(
                                text = "Switch ride profiles on your Karoo device to change which POI settings are active.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
            title = { Text("Profile Name") },
            text = {
                OutlinedTextField(
                    value = profileNameInput,
                    onValueChange = { profileNameInput = it },
                    label = { Text("Enter name (e.g. Road, Gravel, MTB)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = profileNameInput.isNotBlank(),
                    onClick = {
                        val name = profileNameInput.trim()
                        val hash = currentProfileHash ?: return@TextButton
                        karooSystemServiceProvider.setProfileNameForHash(hash, name)
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
