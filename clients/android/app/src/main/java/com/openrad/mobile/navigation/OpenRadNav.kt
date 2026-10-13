package com.openrad.mobile.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destination(val route: String, val icon: ImageVector) {
    data object Networks : Destination("networks", Icons.Filled.Groups)
    data object Discover : Destination("discover", Icons.Filled.Search)
    data object AutoJoin : Destination("autojoin", Icons.AutoMirrored.Filled.PlaylistAdd)
    data object Settings : Destination("settings", Icons.Filled.Settings)

    companion object {
        val all = listOf(Networks, Discover, AutoJoin, Settings)
    }
}
