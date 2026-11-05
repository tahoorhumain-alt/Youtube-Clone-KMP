package org.company.app.presentation.ui.screens.shorts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.screen.Screen
import org.company.app.data.remote.Reel
import org.company.app.data.remote.ReelsApi
import org.company.app.presentation.ui.components.error.ErrorScreen
import org.company.app.presentation.ui.components.shimmer.ShimmerEffectShorts
import org.company.app.presentation.ui.components.shorts.ReelList
import org.koin.compose.koinInject

class ShortScreen() : Screen {
    @Composable
    override fun Content() {
        ShortContent()
    }
}

@Composable
fun ShortContent(api: ReelsApi = koinInject<ReelsApi>()) {
    var reels by remember { mutableStateOf<List<Reel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        reels = null
        try {
            reels = api.reels()
        } catch (e: Exception) {
            error = e.message ?: "Could not load reels"
        }
    }

    when {
        error != null -> ErrorScreen(error!!, onRetry = { attempt++ })
        reels == null -> ShimmerEffectShorts()
        else -> ReelList(reels!!)
    }
}
