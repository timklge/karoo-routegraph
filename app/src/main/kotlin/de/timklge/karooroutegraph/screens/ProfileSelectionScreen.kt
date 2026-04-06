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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.R
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSelectionScreen(
    onBack: () -> Unit
) {
    val karooSystemServiceProvider = koinInject<KarooSystemServiceProvider>()
    var showAddDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    val profiles = remember { mutableStateListOf<String>() }
    var selectedProfileName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Load saved profiles from DataStore
        val stored = karooSystemServiceProvider.getAvailableProfileNames().first()
        profiles.clear()
        profiles.addAll(stored)

        karooSystemServiceProvider.getSelectedProfileName().first()?.let {
            selectedProfileName = it
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.select_ride_profile)) }) },
        content = { padding ->
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (profiles.isEmpty()) {
                        Text(
                            text = "No ride profiles configured. Add one to manage POI visibility per profile.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            items(profiles) { name ->
                                ProfileRow(
                                    name = name,
                                    isSelected = name == selectedProfileName,
                                    onClick = {
                                        selectedProfileName = name
                                        karooSystemServiceProvider.setSelectedProfileName(name)
                                    },
                                    onDelete = {
                                        profiles.remove(name)
                                        if (selectedProfileName == name) {
                                            selectedProfileName = null
                                            karooSystemServiceProvider.clearSelectedProfileName()
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }

                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Text("Add Profile")
                    }
                }

                FixedBackButton(
                    modifier = Modifier.align(Alignment.BottomStart),
                    onBack = onBack
                )
            }
        }
    )

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Ride Profile") },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Profile name (e.g. Road, Gravel, MTB)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newProfileName.isNotBlank(),
                    onClick = {
                        val name = newProfileName.trim()
                        if (name !in profiles) {
                            profiles.add(name)
                            karooSystemServiceProvider.addProfileName(name)
                        }
                        selectedProfileName = name
                        karooSystemServiceProvider.setSelectedProfileName(name)
                        showAddDialog = false
                        newProfileName = ""
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newProfileName = "" }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ProfileRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
