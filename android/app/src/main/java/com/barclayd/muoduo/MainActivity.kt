@file:OptIn(ExperimentalAnimationApi::class)

package com.barclayd.muoduo

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val app get() = application as MuoDuoApp

    private val askForNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.model.notificationsAllowed = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model = app.model

        // The Worker does the checking; the app only watches while it's on screen.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.poll() } }

        if (savedInstanceState == null) {
            if (Build.VERSION.SDK_INT >= 33 && !Permissions.notifications(this)) {
                askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                model.notificationsAllowed = Permissions.notifications(this)
            }
            app.scope.launch { Push.register(model) }
        }

        setContent {
            val finish = app.finish.value
            LaunchedEffect(finish) {
                val bars = if (finish.dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            MuoDuoTheme(finish) { App(model, finish, app.finish::select) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Notifications may have been turned on or off in Settings while the app was away.
        val model = app.model
        if (model.notificationsAllowed != null) model.notificationsAllowed = Permissions.notifications(this)
    }
}

private object Routes {
    @Serializable data object Home
    @Serializable data object Settings
    @Serializable data object Log
    @Serializable data object Activity
    @Serializable data class Map(val area: String? = null)
}

/** iOS's push: the page slides in from the right while the one under it shifts a third of the way left. */
private val push = spring<IntOffset>(dampingRatio = 1f, stiffness = 300f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushed(entry: NavBackStackEntry) =
    entry.destination.hasRoute<Routes.Settings>() || entry.destination.hasRoute<Routes.Log>()

@Composable
private fun App(model: StatusModel, finish: Finish, select: (Finish) -> Unit) {
    val nav = rememberNavController()
    fun go(route: Any) = nav.navigate(route) { launchSingleTop = true }
    val back: () -> Unit = { nav.popBackStack(Routes.Home, inclusive = false) }

    NavHost(nav, startDestination = Routes.Home) {
        composable<Routes.Home>(
            exitTransition = { if (pushed(targetState)) slideOutHorizontally(push) { -it / 3 } else ExitTransition.KeepUntilTransitionsFinished },
            popEnterTransition = { if (pushed(initialState)) slideInHorizontally(push) { -it / 3 } else EnterTransition.None },
        ) {
            HomePage(
                model,
                finish,
                select,
                openSettings = { go(Routes.Settings) },
                openLog = { go(Routes.Log) },
                openMap = { go(Routes.Map(it)) },
                openActivity = { go(Routes.Activity) },
            )
        }
        composable<Routes.Settings>(
            enterTransition = { slideInHorizontally(push) { it } },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) { SettingsPage(model, finish, back) }
        composable<Routes.Log>(
            enterTransition = { slideInHorizontally(push) { it } },
            popExitTransition = { slideOutHorizontally(push) { it } },
        ) { StockLogPage(model, finish, back) }
        // A full-screen cover, rising from the bottom.
        composable<Routes.Map>(
            enterTransition = { slideInVertically(spring(dampingRatio = 1f, stiffness = 300f)) { it } },
            popExitTransition = { slideOutVertically(spring(dampingRatio = 1f, stiffness = 300f)) { it } },
        ) { entry -> StoreMap(model, finish, entry.toRoute<Routes.Map>().area, back) }
        // Grows out of the ticker along the bottom, as iOS's zoom transition does.
        composable<Routes.Activity>(
            enterTransition = { scaleIn(smooth(), initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeIn(smooth()) },
            popExitTransition = { scaleOut(smooth(), targetScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeOut(smooth()) },
        ) { ActivityPage(model, finish, back) }
    }
}
