package de.timklge.karooroutegraph.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.timklge.karooroutegraph.R

@Composable
fun CategorySelectionDialog(
    initialCategories: Set<NearbyPoiCategory>,
    recentlyUsedCategories: List<NearbyPoiCategory> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (Set<NearbyPoiCategory>) -> Unit
) {
    val sortedCategories = remember(recentlyUsedCategories) {
        val recentSet = recentlyUsedCategories.toSet()
        recentlyUsedCategories + NearbyPoiCategory.entries.filter { it !in recentSet }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var tempSelectedCategories by remember { mutableStateOf(initialCategories) }

        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sortedCategories) { category ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    tempSelectedCategories =
                                        if (tempSelectedCategories.contains(category)) {
                                            tempSelectedCategories - category
                                        } else {
                                            tempSelectedCategories + category
                                        }
                                }
                        ) {
                            Checkbox(
                                checked = tempSelectedCategories.contains(category),
                                onCheckedChange = {
                                    tempSelectedCategories = if (tempSelectedCategories.contains(category)) {
                                        tempSelectedCategories - category
                                    } else {
                                        tempSelectedCategories + category
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(category.labelRes))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onConfirm(tempSelectedCategories)
                    }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}
