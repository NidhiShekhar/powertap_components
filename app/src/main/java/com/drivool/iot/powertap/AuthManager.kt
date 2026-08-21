package com.drivool.iot.powertap

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

object AuthManager {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isLoggedIn: Boolean
        get() = currentUser != null

    val userId: String?
        get() = currentUser?.uid

    val userEmail: String?
        get() = currentUser?.email

    fun logout() {
        auth.signOut()
    }
}
