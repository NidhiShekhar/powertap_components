package com.drivool.iot.powertap

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class ProfileActivity : AppCompatActivity() {

    private val database = FirebaseDatabase.getInstance()
    private val uid = AuthManager.userId ?: ""
    private val userRef = database.getReference("Users/$uid")

    private lateinit var nameInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var addressInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (uid.isEmpty()) {
            finish()
            return
        }
        setContentView(buildUi())
        loadUserData()
    }

    private fun buildUi(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(ThemeColors.surface(this@ProfileActivity))

            addView(TextView(this@ProfileActivity).apply {
                text = "Personal Details"
                textSize = 24f
                setTextColor(ThemeColors.onSurface(this@ProfileActivity))
                setPadding(0, 0, 0, 48)
            })

            addView(TextView(this@ProfileActivity).apply {
                text = "Email: ${AuthManager.userEmail}"
                textSize = 16f
                setTextColor(ThemeColors.onSurfaceVariant(this@ProfileActivity))
                setPadding(0, 0, 0, 32)
            })

            nameInput = EditText(this@ProfileActivity).apply {
                hint = "Full Name"
                setTextColor(ThemeColors.onSurface(this@ProfileActivity))
                setHintTextColor(ThemeColors.onSurfaceVariant(this@ProfileActivity))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 }
            }
            phoneInput = EditText(this@ProfileActivity).apply {
                hint = "Phone Number"
                inputType = android.text.InputType.TYPE_CLASS_PHONE
                setTextColor(ThemeColors.onSurface(this@ProfileActivity))
                setHintTextColor(ThemeColors.onSurfaceVariant(this@ProfileActivity))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 }
            }
            addressInput = EditText(this@ProfileActivity).apply {
                hint = "Address"
                setTextColor(ThemeColors.onSurface(this@ProfileActivity))
                setHintTextColor(ThemeColors.onSurfaceVariant(this@ProfileActivity))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 32 }
            }

            addView(nameInput)
            addView(phoneInput)
            addView(addressInput)

            addView(Button(this@ProfileActivity).apply {
                text = "Save Details"
                setBackgroundColor(ThemeColors.primary(this@ProfileActivity))
                setTextColor(ThemeColors.onPrimary(this@ProfileActivity))
                setOnClickListener {
                    saveUserData()
                }
            })

            addView(Button(this@ProfileActivity).apply {
                text = "Back"
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 }
            })
        }
    }

    private fun loadUserData() {
        userRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = snapshot.value as? Map<*, *> ?: return
                nameInput.setText(data["name"] as? String ?: "")
                phoneInput.setText(data["phone"] as? String ?: "")
                addressInput.setText(data["address"] as? String ?: "")
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@ProfileActivity, "Failed to load details", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun saveUserData() {
        val data = mapOf(
            "name" to nameInput.text.toString(),
            "phone" to phoneInput.text.toString(),
            "address" to addressInput.text.toString(),
            "email" to (AuthManager.userEmail ?: "")
        )

        userRef.setValue(data).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Toast.makeText(this, "Details updated successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Update failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
