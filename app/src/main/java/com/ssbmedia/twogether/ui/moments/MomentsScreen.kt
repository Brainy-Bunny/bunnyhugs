package com.ssbmedia.twogether.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ssbmedia.twogether.ServiceLocator
import com.ssbmedia.twogether.data.db.Moment
import com.ssbmedia.twogether.ui.components.EmptyState
import com.ssbmedia.twogether.ui.components.SimpleViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MomentsViewModel : ViewModel() {
    val moments = ServiceLocator.momentRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun MomentsScreen(onBack: () -> Unit) {
    val vm: MomentsViewModel = viewModel(factory = SimpleViewModelFactory { MomentsViewModel() })
    val moments by vm.moments.collectAsState()
    var selected by remember { mutableStateOf<Moment?>(null) }

    val zone = remember { ZoneId.systemDefault() }
    val grouped = remember(moments) {
        moments.groupBy { Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Our Moments") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        if (moments.isEmpty()) {
            EmptyState(
                emoji = "📸",
                title = "No moments yet",
                subtitle = "Photos you take together will show up here, grouped by day.",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                grouped.forEach { (day, dayMoments) ->
                    item(key = day.toString()) {
                        Column {
                            Text(
                                text = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                dayMoments.forEach { moment ->
                                    AsyncImage(
                                        model = moment.photoUri,
                                        contentDescription = "Moment",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(100.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { selected = moment }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        selected?.let { moment ->
            MomentFullScreen(moment = moment, onDismiss = { selected = null })
        }
    }
}

@Composable
private fun MomentFullScreen(moment: Moment, onDismiss: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val dateLabel = remember(moment.takenAt) {
        Instant.ofEpochMilli(moment.takenAt).atZone(zone).toLocalDateTime()
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = onDismiss)
    ) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AsyncImage(
                model = moment.photoUri,
                contentDescription = "Moment",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            )
            Text(
                text = dateLabel,
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = if (moment.sessionId != null) "Taken while together 💕" else "Taken apart",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
