package com.hamoon.uncleted.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hamoon.uncleted.util.showProtected
import com.hamoon.uncleted.R
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.databinding.FragmentProximityTripwireBinding
import com.hamoon.uncleted.proximity.BleProximitySentinel
import com.hamoon.uncleted.services.ZoneWipeService
import com.hamoon.uncleted.util.GeoZoneLocationLogic
import com.hamoon.uncleted.util.PermissionUtils
import com.hamoon.uncleted.util.PolygonUtils
import com.hamoon.uncleted.util.TripwireManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProximityTripwireFragment : Fragment() {

    private var _binding: FragmentProximityTripwireBinding? = null
    private val binding get() = _binding!!

    // Interactive foreground location acquisition state. These exist only to turn
    // a single "Add Current Location" tap into one coordinate for zone creation;
    // nothing here ever triggers a wipe.
    private var locationRequestInFlight = false
    private var locationProgressDialog: androidx.appcompat.app.AlertDialog? = null
    private var locationCancellationSignal: CancellationSignal? = null
    private val activeOneShotListeners = mutableListOf<LocationListener>()
    private val locationHandler = Handler(Looper.getMainLooper())
    private var locationTimeoutRunnable: Runnable? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                promptAddCurrentLocationAsWipeZone()
            } else {
                Toast.makeText(requireContext(), "Location permission is required for geofence arming.", Toast.LENGTH_LONG).show()
            }
        }

    companion object {
        private const val TAG = "ProximityTripwire"
        private const val LOCATION_FIX_TIMEOUT_MS = 20_000L
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProximityTripwireBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupListeners()
        observeSentinelStatus()
    }

    private fun loadSettings() {
        val context = requireContext()

        // 1. BLE Proximity Settings
        binding.switchProximitySharding.isChecked = SecurityPreferences.isProximityShardingEnabled(context)
        binding.etProximityTargetMac.setText(SecurityPreferences.getProximityBleTargetAddress(context) ?: "")
        binding.etProximityRssiThreshold.setText(SecurityPreferences.getProximityRssiThreshold(context).toString())
        binding.etProximityBreachLimit.setText(SecurityPreferences.getProximityMissedHeartbeatThreshold(context).toString())

        // 2. Dead-Man Tripwire Settings
        binding.switchDeadmanTripwire.isChecked = SecurityPreferences.isTripwireEnabled(context)
        setupDurationDropdown(SecurityPreferences.getTripwireDuration(context))

        // 3. Geographic Suicide Settings
        binding.switchGeofenceSuicide.isChecked = SecurityPreferences.isGeofenceSuicideEnabled(context)
    }

    private fun setupDurationDropdown(currentHours: Int) {
        val entries = resources.getStringArray(R.array.tripwire_duration_entries)
        val values = resources.getStringArray(R.array.tripwire_duration_values)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, entries)
        binding.autoTripwireDuration.setAdapter(adapter)

        val idx = values.indexOf(currentHours.toString())
        if (idx != -1) {
            binding.autoTripwireDuration.setText(entries[idx], false)
        } else {
            val days = currentHours / 24
            binding.autoTripwireDuration.setText("Custom: $currentHours Hours ($days Days)", false)
        }
    }

    private fun setupListeners() {
        val context = requireContext()

        binding.switchProximitySharding.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setProximityShardingEnabled(context, isChecked)
        }

        binding.switchDeadmanTripwire.setOnCheckedChangeListener { _, isChecked ->
            SecurityPreferences.setTripwireEnabled(context, isChecked)
            TripwireManager.scheduleOrCancelTripwire(context)
        }

        binding.autoTripwireDuration.setOnItemClickListener { _, _, position, _ ->
            val values = resources.getStringArray(R.array.tripwire_duration_values)
            val selectedHours = values[position].toInt()
            SecurityPreferences.setTripwireDuration(context, selectedHours)
            TripwireManager.scheduleOrCancelTripwire(context)
        }

        // Custom Arbitrary Tripwire Duration Dialog
        binding.btnCustomTripwireDuration.setOnClickListener {
            showCustomTripwireDurationDialog()
        }

        binding.btnDeadmanCheckin.setOnClickListener {
            TripwireManager.checkIn(context)
            Toast.makeText(context, "Check-in recorded. Tripwire hardware alarm reset.", Toast.LENGTH_SHORT).show()
        }

        binding.switchGeofenceSuicide.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                MaterialAlertDialogBuilder(context)
                    .setTitle("ACTIVATE GEOGRAPHIC SUICIDE?")
                    .setMessage("Entering pre-configured destruction perimeters (such as Evin Prison) or custom zones will trigger instant silicon-level key revocation and zero partitions.")
                    .setPositiveButton("ARM SYSTEM") { _, _ ->
                        SecurityPreferences.setGeofenceSuicideEnabled(context, true)
                        val zoneIntent = Intent(context, ZoneWipeService::class.java)
                        ContextCompat.startForegroundService(context, zoneIntent)
                        Toast.makeText(context, "Geographic Destruction Armed.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        binding.switchGeofenceSuicide.isChecked = false
                        SecurityPreferences.setGeofenceSuicideEnabled(context, false)
                    }
                    .setCancelable(false)
                    .showProtected(requireActivity())
            } else {
                SecurityPreferences.setGeofenceSuicideEnabled(context, false)
                context.stopService(Intent(context, ZoneWipeService::class.java))
                Toast.makeText(context, "Geographic Destruction Disarmed.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnManageWipeZones.setOnClickListener {
            showManageWipeZonesDialog()
        }

        binding.btnSaveProximitySettings.setOnClickListener {
            saveProximityParameters()
            Toast.makeText(context, "Proximity & dead-man sentinel parameters saved.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCustomTripwireDurationDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etDuration = EditText(context).apply {
            hint = "Duration value (e.g. 14)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val etUnit = TextView(context).apply {
            text = "Specify duration in Days (e.g., 7, 30, 90, 180, 365)"
            setPadding(0, 12, 0, 0)
        }

        layout.addView(etDuration)
        layout.addView(etUnit)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.tripwire_custom_dialog_title)
            .setMessage("Set arbitrary offline inactivity timer before autonomous BFU wipe triggers:")
            .setView(layout)
            .setPositiveButton("Set Duration") { _, _ ->
                val daysInput = etDuration.text.toString().trim().toIntOrNull()
                if (daysInput != null && daysInput > 0) {
                    val customHours = daysInput * 24
                    SecurityPreferences.setTripwireDuration(context, customHours)
                    setupDurationDropdown(customHours)
                    TripwireManager.scheduleOrCancelTripwire(context)
                    Toast.makeText(context, "Dead-man tripwire set to $daysInput days ($customHours hours).", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Invalid duration entered.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .showProtected(requireActivity())
    }

    private fun saveProximityParameters() {
        val context = requireContext()
        val mac = binding.etProximityTargetMac.text?.toString()?.trim()
        val rssi = binding.etProximityRssiThreshold.text?.toString()?.toIntOrNull() ?: -85
        val breachLimit = binding.etProximityBreachLimit.text?.toString()?.toIntOrNull() ?: 3

        SecurityPreferences.setProximityBleTargetAddress(context, if (mac.isNullOrEmpty()) null else mac)
        SecurityPreferences.setProximityRssiThreshold(context, rssi)
        SecurityPreferences.setProximityMissedHeartbeatThreshold(context, breachLimit)
    }

    private fun observeSentinelStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            BleProximitySentinel.statusFlow.collectLatest { status ->
                if (_binding != null) {
                    binding.tvProximityConnectionStatus.text = "GATT State: ${status.connectionState}"
                    binding.tvProximityLiveRssi.text = "Token RSSI: ${status.lastRssi} dBm (Breaches: ${status.consecutiveBreaches})"

                    if (status.isShardBLoaded) {
                        binding.tvProximityShardStatus.text = "BLE Key Status: Active"
                        binding.tvProximityShardStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_green))
                    } else {
                        binding.tvProximityShardStatus.text = "BLE Key Status: Idle"
                        binding.tvProximityShardStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_yellow))
                    }
                }
            }
        }
    }

    private fun showManageWipeZonesDialog() {
        val context = requireContext()
        val zones = SecurityPreferences.getCustomWipeZones(context)
        val zoneNames = zones.map { "${it.name} (${if (it.radiusMeters > 0) "${it.radiusMeters.toInt()}m radius" else "${it.polygon.size} pts"})" }.toMutableList()
        zoneNames.add(0, "[+] Add Current Location as Wipe Zone")
        zoneNames.add(1, "[+] Add Custom Coordinates (Lat, Lon, Radius)")

        MaterialAlertDialogBuilder(context)
            .setTitle("Destruction No-Go Zones")
            .setItems(zoneNames.toTypedArray()) { _, which ->
                when (which) {
                    0 -> promptAddCurrentLocationAsWipeZone()
                    1 -> promptAddManualWipeZone()
                    else -> {
                        val selectedZone = zones[which - 2]
                        promptZoneActions(selectedZone)
                    }
                }
            }
            .setNegativeButton("Close", null)
            .showProtected(requireActivity())
    }

    /**
     * Entry point for "Add Current Location as Wipe Zone". Obtains a single
     * foreground coordinate and hands it to [promptZoneRadiusAndName]. This flow
     * NEVER enables a zone, arms geographic suicide, or triggers any wipe — the
     * user still explicitly creates the zone through the existing dialog.
     */
    private fun promptAddCurrentLocationAsWipeZone() {
        val context = requireContext()

        // Only the foreground fine/coarse permission is needed to capture a
        // coordinate while the app is visible; background location is NOT required
        // here (that is only for the armed ZoneWipeService monitor).
        if (!PermissionUtils.hasForegroundLocationPermission(context)) {
            Log.i(TAG, "Current location requested but foreground location permission missing.")
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        if (locationRequestInFlight) {
            Toast.makeText(context, getString(R.string.geo_zone_location_in_progress), Toast.LENGTH_SHORT).show()
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            showLocationError(GeoZoneLocationLogic.LocationAcquisitionError.UNAVAILABLE)
            return
        }

        Log.i(TAG, "Current location requested.")

        // Fast path: use a sufficiently recent and accurate cached fix if present.
        val cached = bestUsableCachedFix(lm)
        if (cached != null) {
            Log.i(TAG, "Recent cached fix available; using it for zone creation.")
            promptZoneRadiusAndName(cached.latitude, cached.longitude)
            return
        }
        Log.i(TAG, "No cached fix available; requesting fresh location.")

        val providers = enabledOrderedProviders(lm)
        if (providers.isEmpty()) {
            val anyPresent = try {
                lm.allProviders.any {
                    it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
                }
            } catch (_: Exception) {
                false
            }
            if (anyPresent) {
                Log.w(TAG, "Location providers present but disabled.")
                showLocationError(GeoZoneLocationLogic.LocationAcquisitionError.SERVICES_DISABLED)
            } else {
                Log.w(TAG, "No usable location provider available.")
                showLocationError(GeoZoneLocationLogic.LocationAcquisitionError.NO_PROVIDER)
            }
            return
        }

        requestFreshLocation(lm, providers)
    }

    /** Returns the freshest/most-accurate usable cached fix, or null. */
    @SuppressLint("MissingPermission")
    private fun bestUsableCachedFix(lm: LocationManager): Location? {
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val present = try { lm.allProviders.contains(provider) } catch (_: Exception) { false }
            if (!present) continue
            val loc = try {
                lm.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                Log.w(TAG, "Location permission missing while reading cached fix.")
                return null
            } catch (e: Exception) {
                null
            } ?: continue

            val ageMs = System.currentTimeMillis() - loc.time
            val usable = GeoZoneLocationLogic.isCachedFixUsable(
                ageMs = ageMs,
                hasAccuracy = loc.hasAccuracy(),
                accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else -1f
            )
            if (!usable) continue

            if (best == null ||
                (loc.hasAccuracy() && (!best!!.hasAccuracy() || loc.accuracy < best!!.accuracy))
            ) {
                best = loc
            }
        }
        return best
    }

    /** Providers present AND enabled, GPS preferred then network. */
    private fun enabledOrderedProviders(lm: LocationManager): List<String> {
        val all = try { lm.allProviders } catch (_: Exception) { emptyList<String>() }
        val gpsEnabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val networkEnabled = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        return GeoZoneLocationLogic.selectProviders(
            listOf(
                GeoZoneLocationLogic.ProviderAvailability(
                    LocationManager.GPS_PROVIDER, all.contains(LocationManager.GPS_PROVIDER), gpsEnabled
                ),
                GeoZoneLocationLogic.ProviderAvailability(
                    LocationManager.NETWORK_PROVIDER, all.contains(LocationManager.NETWORK_PROVIDER), networkEnabled
                )
            )
        )
    }

    /**
     * Actively requests a current fix from the given providers. Uses
     * [LocationManager.getCurrentLocation] on API 30+ and a one-shot
     * [LocationManager.requestLocationUpdates] fallback on older versions. First
     * acceptable fix wins; a timeout surfaces an accurate error. No coordinates
     * are logged.
     */
    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(lm: LocationManager, providers: List<String>) {
        locationRequestInFlight = true
        showLocationProgress()

        providers.forEach { provider ->
            if (provider == LocationManager.GPS_PROVIDER) Log.i(TAG, "GPS provider available.")
            if (provider == LocationManager.NETWORK_PROVIDER) Log.i(TAG, "Network provider available.")
        }

        // Shared timeout guards against waiting indefinitely.
        val timeout = Runnable {
            if (!locationRequestInFlight) return@Runnable
            Log.w(TAG, "Location acquisition timed out.")
            finishLocationAcquisition()
            showLocationError(GeoZoneLocationLogic.LocationAcquisitionError.TIMEOUT)
        }
        locationTimeoutRunnable = timeout
        locationHandler.postDelayed(timeout, LOCATION_FIX_TIMEOUT_MS)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                locationCancellationSignal = signal
                val executor = ContextCompat.getMainExecutor(requireContext())
                providers.forEach { provider ->
                    lm.getCurrentLocation(provider, signal, executor) { location ->
                        onFreshFix(location)
                    }
                }
            } else {
                providers.forEach { provider ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) = onFreshFix(location)

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                    }
                    activeOneShotListeners.add(listener)
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing while requesting fresh fix.")
            finishLocationAcquisition()
            showLocationError(GeoZoneLocationLogic.LocationAcquisitionError.PERMISSION_DENIED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed requesting fresh location: ${e.javaClass.simpleName}")
            finishLocationAcquisition()
            showLocationError(GeoZoneLocationLogic.LocationAcquisitionError.UNAVAILABLE)
        }
    }

    /** First-fix-wins handler; idempotent via [locationRequestInFlight]. */
    private fun onFreshFix(location: Location?) {
        if (!locationRequestInFlight) return
        if (location == null) return
        if (_binding == null) {
            // View gone; just clean up and never proceed.
            finishLocationAcquisition()
            return
        }
        Log.i(TAG, "Fresh location fix acquired.")
        finishLocationAcquisition()
        promptZoneRadiusAndName(location.latitude, location.longitude)
    }

    /** Cancels and releases all in-flight location resources. Safe to call twice. */
    private fun finishLocationAcquisition() {
        locationRequestInFlight = false

        locationTimeoutRunnable?.let { locationHandler.removeCallbacks(it) }
        locationTimeoutRunnable = null

        locationCancellationSignal?.let { try { it.cancel() } catch (_: Exception) {} }
        locationCancellationSignal = null

        if (activeOneShotListeners.isNotEmpty()) {
            val lm = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            activeOneShotListeners.forEach { listener ->
                try { lm?.removeUpdates(listener) } catch (_: Exception) {}
            }
            activeOneShotListeners.clear()
        }

        locationProgressDialog?.let { try { it.dismiss() } catch (_: Exception) {} }
        locationProgressDialog = null
    }

    private fun showLocationProgress() {
        if (_binding == null) return
        locationProgressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.geo_zone_location_in_progress)
            .setMessage(R.string.geo_zone_location_in_progress_message)
            .setCancelable(true)
            .setOnCancelListener { finishLocationAcquisition() }
            .showProtected(requireActivity())
    }

    private fun showLocationError(error: GeoZoneLocationLogic.LocationAcquisitionError) {
        if (_binding == null) return
        val msgRes = when (error) {
            GeoZoneLocationLogic.LocationAcquisitionError.PERMISSION_DENIED -> R.string.geo_zone_err_permission
            GeoZoneLocationLogic.LocationAcquisitionError.SERVICES_DISABLED -> R.string.geo_zone_err_services_disabled
            GeoZoneLocationLogic.LocationAcquisitionError.NO_PROVIDER -> R.string.geo_zone_err_no_provider
            GeoZoneLocationLogic.LocationAcquisitionError.TIMEOUT -> R.string.geo_zone_err_timeout
            GeoZoneLocationLogic.LocationAcquisitionError.UNAVAILABLE -> R.string.geo_zone_err_unavailable
        }
        Toast.makeText(requireContext(), getString(msgRes), Toast.LENGTH_LONG).show()
    }

    private fun promptAddManualWipeZone() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val etName = EditText(requireContext()).apply { hint = "Zone Name (e.g. Hostile Facility)" }
        val etLat = EditText(requireContext()).apply { hint = "Latitude (e.g. 35.7921)" }
        val etLon = EditText(requireContext()).apply { hint = "Longitude (e.g. 51.3814)" }
        val etRadius = EditText(requireContext()).apply { hint = "Radius in meters (e.g. 150)" }

        layout.addView(etName)
        layout.addView(etLat)
        layout.addView(etLon)
        layout.addView(etRadius)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Create Custom Destruction Zone")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = etName.text.toString().ifEmpty { "Custom Zone" }
                val lat = etLat.text.toString().toDoubleOrNull()
                val lon = etLon.text.toString().toDoubleOrNull()
                val radius = etRadius.text.toString().toFloatOrNull() ?: 100f

                if (lat != null && lon != null) {
                    val zone = PolygonUtils.WipeZone(
                        name = name,
                        centerLat = lat,
                        centerLon = lon,
                        radiusMeters = radius,
                        isEnabled = true
                    )
                    SecurityPreferences.addCustomWipeZone(requireContext(), zone)
                    Toast.makeText(requireContext(), "Destruction Zone '$name' created.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Invalid coordinates.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .showProtected(requireActivity())
    }

    private fun promptZoneRadiusAndName(lat: Double, lon: Double) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val etName = EditText(requireContext()).apply { hint = "Zone Name" }
        val etRadius = EditText(requireContext()).apply { hint = "Radius (meters, default 100)" }

        layout.addView(etName)
        layout.addView(etRadius)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Arm Current Location as Wipe Zone")
            .setMessage("Coordinates: $lat, $lon")
            .setView(layout)
            .setPositiveButton("Arm Zone") { _, _ ->
                val name = etName.text.toString().ifEmpty { "Location Zone" }
                val radius = etRadius.text.toString().toFloatOrNull() ?: 100f

                val zone = PolygonUtils.WipeZone(
                    name = name,
                    centerLat = lat,
                    centerLon = lon,
                    radiusMeters = radius,
                    isEnabled = true
                )
                SecurityPreferences.addCustomWipeZone(requireContext(), zone)
                Toast.makeText(requireContext(), "Destruction Zone '$name' armed ($radius m).", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .showProtected(requireActivity())
    }

    private fun promptZoneActions(zone: PolygonUtils.WipeZone) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(zone.name)
            .setMessage("Center: ${zone.centerLat}, ${zone.centerLon}\nRadius: ${zone.radiusMeters}m")
            .setNeutralButton("Delete Zone") { _, _ ->
                SecurityPreferences.removeCustomWipeZone(requireContext(), zone.id)
                Toast.makeText(requireContext(), "Zone '${zone.name}' deleted.", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Close", null)
            .showProtected(requireActivity())
    }

    override fun onDestroyView() {
        // Cancel any in-flight location request so callbacks never fire against a
        // destroyed view, and release framework listeners/cancellation signals.
        finishLocationAcquisition()
        super.onDestroyView()
        _binding = null
    }
}
