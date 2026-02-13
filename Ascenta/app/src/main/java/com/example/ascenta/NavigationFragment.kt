package com.example.ascenta

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.Locale
import java.util.concurrent.Executors

class NavigationFragment : Fragment(R.layout.fragment_navigation) {

    private lateinit var tvResult: TextView
    private lateinit var btnLocate: Button
    private lateinit var locationManager: LocationManager

    private var lastLocation: Location? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvResult = view.findViewById(R.id.tv_location_result)
        btnLocate = view.findViewById(R.id.btn_get_location)

        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        btnLocate.setOnClickListener {
            getCurrentLocation()
        }
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocation() {
        if (!hasLocationPermission()) {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
            return
        }

        val provider = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            LocationManager.NETWORK_PROVIDER
        } else if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            tvResult.text = "Error: Location Disabled"
            speak("Please enable Location Services")
            return
        }

        tvResult.text = "Locating..."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(
                provider,
                null,
                requireContext().mainExecutor
            ) { location ->
                if (location != null) {
                    processLocation(location)
                } else {
                    val lastKnown = locationManager.getLastKnownLocation(provider)
                    if (lastKnown != null) processLocation(lastKnown)
                    else {
                        tvResult.text = "Signal weak. Try outdoors."
                        speak("Location signal weak")
                    }
                }
            }
        } else {
            val location = locationManager.getLastKnownLocation(provider)
            if (location != null) processLocation(location)
            else {
                tvResult.text = "Searching..."
                speak("Searching for signal")
            }
        }
    }

    private fun processLocation(location: Location) {
        lastLocation = location
        executor.execute {
            try {
                val geocoder = Geocoder(requireContext(), Locale.getDefault())

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(location.latitude, location.longitude, 1) { list ->
                        handleAddressList(list)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    handleAddressList(addresses ?: emptyList())
                }
            } catch (e: Exception) {
                handler.post {
                    tvResult.text = "Lat: ${location.latitude}\nLon: ${location.longitude}"
                    speak("Coordinates found, but address unavailable")
                }
            }
        }
    }

    private fun handleAddressList(addresses: List<Address>) {
        handler.post {
            if (addresses.isNotEmpty()) {
                val address = addresses[0]
                val sb = StringBuilder()

                if (address.thoroughfare != null) sb.append(address.thoroughfare)
                if (address.subThoroughfare != null) sb.append(" ").append(address.subThoroughfare)

                if (sb.isNotEmpty()) sb.append(", ")

                if (address.locality != null) sb.append(address.locality)

                val resultText = sb.toString()

                if (resultText.isNotBlank()) {
                    tvResult.text = resultText
                    speak("You are at $resultText")
                } else {
                    tvResult.text = "Address details not found."
                    speak("Address details not found")
                }
            } else {
                tvResult.text = "Unknown Location"
                speak("Address not found")
            }
        }
    }

    private fun speak(text: String) {
        (activity as? MainActivity)?.speakText(text)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}