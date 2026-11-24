package com.rork.rideax.ui.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rork.rideax.AppContainer
import com.rork.rideax.data.Haptics
import com.rork.rideax.model.AppRole
import com.rork.rideax.ui.appViewModelFactory
import com.rork.rideax.ui.driver.DriverAccountScreen
import com.rork.rideax.ui.driver.DriverDocumentsScreen
import com.rork.rideax.ui.driver.DriverEarningsScreen
import com.rork.rideax.ui.driver.DriverHomeScreen
import com.rork.rideax.ui.driver.DriverViewModel
import com.rork.rideax.ui.onboarding.OnboardingScreen
import com.rork.rideax.ui.onboarding.SplashScreen
import com.rork.rideax.ui.payments.PaymentSheetHost
import com.rork.rideax.ui.rider.AccountScreen
import com.rork.rideax.ui.rider.ActivityScreen
import com.rork.rideax.ui.rider.BookingScreen
import com.rork.rideax.ui.rider.RideTrackingScreen
import com.rork.rideax.ui.rider.RideViewModel
import com.rork.rideax.ui.session.SessionViewModel
import com.rork.rideax.ui.theme.RideColors

private enum class RootScreen { SPLASH, ONBOARDING, RIDER, DRIVER }

/** Root: restores the session, then shows onboarding or the rider / driver shell. */
@Composable
fun RideAXApp(container: AppContainer) {
    val factory = remember { appViewModelFactory(container) }
    val session: SessionViewModel = viewModel(factory = factory)
    val ride: RideViewModel = viewModel(factory = factory)
    val driver: DriverViewModel = viewModel(factory = factory)
    val state by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.account?.id) {
        ride.riderId = state.account?.id
        driver.driverId = state.account?.id
    }
    PaymentSheetHost(ride)

    val root = when {
        state.isRestoring -> RootScreen.SPLASH
        !state.isSignedIn || !state.hasCompletedOnboarding -> RootScreen.ONBOARDING
        state.role == AppRole.DRIVER -> RootScreen.DRIVER
        else -> RootScreen.RIDER
    }

    Box(Modifier.fillMaxSize().background(RideColors.Canvas)) {
        Crossfade(targetState = root, label = "root") { screen ->
            when (screen) {
                RootScreen.SPLASH -> SplashScreen()
                RootScreen.ONBOARDING -> OnboardingScreen(
                    state = state,
                    onAuthenticate = { email, password, fullName, isSignUp, role ->
                        session.authenticate(email, password, fullName, isSignUp, role)
                    },
                    onSignIn = { provider, role -> session.signIn(provider, role, context) },
                    onClearError = session::clearAuthError,
                )
                RootScreen.RIDER -> RiderShell(container, session, ride)
                RootScreen.DRIVER -> DriverShell(container, session, driver)
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val RiderTabs = listOf(
    Tab("ride", "Ride", Icons.Rounded.DirectionsCar),
    Tab("activity", "Activity", Icons.AutoMirrored.Rounded.ReceiptLong),
    Tab("account", "Account", Icons.Rounded.AccountCircle),
)

@Composable
private fun RiderShell(container: AppContainer, session: SessionViewModel, ride: RideViewModel) {
    val navController = rememberNavController()
    val sessionState by session.state.collectAsStateWithLifecycle()
    val rideState by ride.state.collectAsStateWithLifecycle()
    val trips by container.trips.riderTrips.collectAsStateWithLifecycle()
    val hasPermission by container.location.hasPermission.collectAsStateWithLifecycle()
    val point by container.location.point.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        container.location.start()
    }
    LaunchedEffect(Unit) {
        if (container.location.checkPermission()) container.location.start()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    DisposableEffect(Unit) { onDispose { container.location.stop() } }
    LaunchedEffect(point) { point?.let(ride::syncPickupWithLocation) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = RideColors.Canvas,
            bottomBar = {
                NavigationBar(containerColor = RideColors.Surface, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                    RiderTabs.forEach { tab ->
                        val selected = backStack?.destination?.route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (selected) return@NavigationBarItem
                                Haptics.tap()
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = RideColors.Accent,
                                selectedTextColor = RideColors.Accent,
                                indicatorColor = RideColors.Accent.copy(alpha = 0.14f),
                                unselectedIconColor = RideColors.TextSecondary,
                                unselectedTextColor = RideColors.TextSecondary,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(navController, startDestination = "ride", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
                composable("ride") {
                    BookingScreen(
                        state = rideState,
                        session = sessionState,
                        viewModel = ride,
                        hasLocationPermission = hasPermission,
                        onRequestLocation = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        },
                    )
                }
                composable("activity") {
                    ActivityScreen(trips = trips, onRate = { id, stars -> container.trips.rate(id, stars) })
                }
                composable("account") {
                    AccountScreen(
                        state = sessionState,
                        onMakeDefault = session::makeDefault,
                        onNotifications = session::setNotifications,
                        onSwitchToDriver = { session.switchRole(AppRole.DRIVER) },
                        onSignOut = session::signOut,
                        onDeleteAccount = session::deleteAccount,
                        deleteError = sessionState.authError,
                        onClearError = session::clearAuthError,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = rideState.ride != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            RideTrackingScreen(rideState, sessionState, ride)
        }
    }
}

@Composable
private fun DriverShell(container: AppContainer, session: SessionViewModel, driver: DriverViewModel) {
    val navController = rememberNavController()
    val sessionState by session.state.collectAsStateWithLifecycle()
    val driverState by driver.state.collectAsStateWithLifecycle()
    val trips by container.trips.driverTrips.collectAsStateWithLifecycle()
    val todays = remember(trips) { container.trips.todaysTrips(trips) }
    val rating = remember(trips) { container.trips.averageRating(trips) }

    NavHost(navController, startDestination = "home", modifier = Modifier.fillMaxSize().background(RideColors.Canvas)) {
        composable("home") {
            Box(Modifier.fillMaxSize()) {
                DriverHomeScreen(
                    state = driverState,
                    account = sessionState.account,
                    todaysTrips = todays,
                    averageRating = rating,
                    viewModel = driver,
                    onOpenEarnings = { navController.navigate("earnings") },
                    onOpenDocuments = { navController.navigate("documents") },
                    onOpenAccount = { navController.navigate("account") },
                )
            }
        }
        composable("earnings") {
            DriverEarningsScreen(
                week = remember(trips) { container.trips.weeklyEarnings(trips) },
                trips = trips,
                averageRating = rating,
                onBack = { navController.popBackStack() },
            )
        }
        composable("documents") {
            DriverDocumentsScreen(driverState, driver, onBack = { navController.popBackStack() })
        }
        composable("account") {
            DriverAccountScreen(
                account = sessionState.account,
                state = driverState,
                onOpenDocuments = { navController.navigate("documents") },
                onOpenEarnings = { navController.navigate("earnings") },
                onSwitchToRider = {
                    driver.setOnline(false)
                    session.switchRole(AppRole.RIDER)
                },
                onSignOut = {
                    driver.setOnline(false)
                    session.signOut()
                },
                onDeleteAccount = {
                    driver.setOnline(false)
                    session.deleteAccount()
                },
                deleteError = sessionState.authError,
                onClearError = session::clearAuthError,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
