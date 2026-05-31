package com.jossephus.chuchu.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AppShellState(initialRoute: AppRoute = AppRoute.Servers) {
    var route: AppRoute by mutableStateOf(initialRoute)
        private set

    fun navigateTo(route: AppRoute) {
        this.route = route
    }
}
