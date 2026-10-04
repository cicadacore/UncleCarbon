package com.hamoon.uncleted

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.navigation.NavigationView
import com.hamoon.uncleted.data.SecurityPreferences
import com.hamoon.uncleted.databinding.ActivityMainBinding
import com.hamoon.uncleted.fragments.*
import com.hamoon.uncleted.services.MonitoringService
import com.hamoon.uncleted.util.AppLockActivity

class MainActivity : AppLockActivity(), NavigationView.OnNavigationItemSelectedListener {
    override val protectedFragmentContainerId: Int = R.id.nav_host_fragment

    private lateinit var binding: ActivityMainBinding
    private lateinit var toggle: ActionBarDrawerToggle

    private val dashboardFragment by lazy { DashboardFragment() }
    private val hardwareSentinelsFragment by lazy { HardwareSentinelsFragment() }
    private val authenticationFragment by lazy { AuthenticationFragment() }
    private val proximityTripwireFragment by lazy { ProximityTripwireFragment() }
    private val remoteSignalingFragment by lazy { RemoteSignalingFragment() }
    private val surveillanceFragment by lazy { SurveillanceFragment() }
    private val destructionProtocolsFragment by lazy { DestructionProtocolsFragment() }
    private val permissionsFragment by lazy { PermissionsFragment() }
    private val diagnosticsFragment by lazy { DiagnosticsFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private val aboutFragment by lazy { AboutFragment() }

    private var activeFragment: Fragment? = null

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startMonitoringServiceIfNeeded()
    }

    override fun onProtectedCreate() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.initialLoadingIndicator.visibility = View.GONE
        binding.drawerLayout.visibility = View.VISIBLE
        initializeUi()
    }

    private fun initializeUi() {
        setSupportActionBar(binding.toolbar)

        toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener(this)

        showFragment(dashboardFragment, getString(R.string.menu_dashboard))
        binding.navView.setCheckedItem(R.id.nav_dashboard)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isAccessAllowed) return

                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else if (activeFragment !is DashboardFragment) {
                    showFragment(dashboardFragment, getString(R.string.menu_dashboard))
                    binding.navView.setCheckedItem(R.id.nav_dashboard)
                } else {
                    finish()
                }
            }
        })
    }

    private fun startMonitoringServiceIfNeeded() {
        val isPrimaryUser = (Process.myUid() / 100000) == 0
        if (isPrimaryUser && SecurityPreferences.isProtectionEnabled(this)) {
            val serviceIntent = Intent(this, MonitoringService::class.java)
            try {
                ContextCompat.startForegroundService(this, serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting MonitoringService", e)
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (!isAccessAllowed) return false
        val (fragment, title) = when (item.itemId) {
            R.id.nav_dashboard -> dashboardFragment to getString(R.string.menu_dashboard)
            R.id.nav_hardware_sentinels -> hardwareSentinelsFragment to getString(R.string.menu_hardware_sentinels)
            R.id.nav_authentication -> authenticationFragment to getString(R.string.menu_authentication)
            R.id.nav_proximity_tripwire -> proximityTripwireFragment to getString(R.string.menu_proximity_tripwire)
            R.id.nav_remote_signaling -> remoteSignalingFragment to getString(R.string.menu_remote_signaling)
            R.id.nav_surveillance -> surveillanceFragment to getString(R.string.menu_surveillance)
            R.id.nav_destruction -> destructionProtocolsFragment to getString(R.string.menu_destruction)
            R.id.nav_permissions -> permissionsFragment to getString(R.string.menu_permissions)
            R.id.nav_diagnostics -> diagnosticsFragment to getString(R.string.menu_diagnostics)
            R.id.nav_settings -> settingsFragment to getString(R.string.menu_settings)
            R.id.nav_about -> aboutFragment to getString(R.string.menu_about)
            else -> return false
        }

        showFragment(fragment, title)
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun showFragment(fragment: Fragment, title: String) {
        if (!isAccessAllowed) return
        if (fragment == activeFragment && fragment.isAdded && fragment.isVisible) return
        if (isFinishing || isDestroyed) return

        supportFragmentManager.commit(allowStateLoss = true) {
            val current = activeFragment
            if (current != null && current.isAdded) {
                hide(current)
            }
            if (!fragment.isAdded) {
                add(R.id.nav_host_fragment, fragment, title)
            } else {
                show(fragment)
            }
        }
        activeFragment = fragment
        binding.toolbar.title = title
    }
}
