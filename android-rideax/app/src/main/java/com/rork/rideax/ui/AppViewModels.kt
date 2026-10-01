package com.rork.rideax.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rork.rideax.AppContainer
import com.rork.rideax.ui.driver.DriverViewModel
import com.rork.rideax.ui.rider.RideViewModel
import com.rork.rideax.ui.session.SessionViewModel

/** Builds every screen ViewModel from the shared process-wide services. */
fun appViewModelFactory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer { SessionViewModel(container) }
    initializer { RideViewModel(container) }
    initializer { DriverViewModel(container) }
}
