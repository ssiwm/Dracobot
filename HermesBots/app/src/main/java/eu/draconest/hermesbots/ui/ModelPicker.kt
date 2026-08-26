package eu.draconest.hermesbots.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Picker modelu AI: providerzy + ich modele z model.options.
 * Provider i ID modelu są przekazywane osobno; model pod aggregatorem może
 * zawierać własny '/' (np. openai/gpt-…), więc nie tworzymy provider/model.
 */
@Composable
fun ModelPickerContent(
    currentModel: String,
    currentProvider: String,
    onPick: (provider: String, model: String) -> Unit,
    loadOptions: suspend () -> List<Pair<String, List<String>>>
) {
    var options by remember { mutableStateOf<List<Pair<String, List<String>>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            options = loadOptions()
        } catch (e: Exception) {
            error = e.message ?: "Nie udało się pobrać listy modeli"
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            "Wybierz model",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
        if (currentModel.isNotBlank()) {
            Text(
                "Aktualnie: $currentModel" + if (currentProvider.isNotBlank()) " ($currentProvider)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            error != null -> Text(
                "⚠️ $error",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(20.dp)
            )
            options == null -> Text(
                "Ładowanie…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp)
            )
            else -> LazyColumn {
                options!!.forEach { (providerSlug, models) ->
                    item(key = "hdr-$providerSlug") {
                        Text(
                            providerSlug.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    items(models.size, key = { i -> "$providerSlug-$i" }) { idx ->
                        val model = models[idx]
                        val isCurrent = model == currentModel
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(providerSlug, model) }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                model.substringAfter('/'),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (isCurrent) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "● aktywny",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    item(key = "div-$providerSlug") { HorizontalDivider() }
                }
            }
        }
    }
}
