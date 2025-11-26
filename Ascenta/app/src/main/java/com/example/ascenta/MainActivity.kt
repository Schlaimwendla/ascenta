package com.example.ascenta

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private val PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Animated Background
        val constraintLayout = findViewById<RelativeLayout>(R.id.main_container)
        val animationDrawable = constraintLayout.background as AnimationDrawable
        animationDrawable.setEnterFadeDuration(2000)
        animationDrawable.setExitFadeDuration(4000)
        animationDrawable.start()

        // 2. Check Permissions
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, 1)
        }

        // 3. Navigation Setup
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val fab = findViewById<FloatingActionButton>(R.id.fab_home)

        // Load default fragment
        loadFragment(ImageFragment())

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_image -> loadFragment(ImageFragment())
                R.id.nav_audio -> loadFragment(AudioFragment())
                else -> false
            }
            true
        }

        // FAB acts as "Home" or "Info" button
        fab.setOnClickListener {
            loadFragment(InfoFragment())
            // Uncheck bottom nav items to show we are in "Home" state
            bottomNav.menu.findItem(R.id.nav_image).isChecked = false
            bottomNav.menu.findItem(R.id.nav_audio).isChecked = false
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun hasPermissions(): Boolean {
        return PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}