package com.example.drivepoc

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.drivepoc.objects.KeyPairManager
import com.example.drivepoc.objects.KeyStoreObjects
import com.example.drivepoc.ui.theme.DrivePOCTheme
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var firebaseAuth: FirebaseAuth
    private var driveService: Drive? = null

    // Register Google Sign-In Activity Result
    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            val account = task.result
            account?.idToken?.let { idToken ->
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                firebaseAuth.signInWithCredential(credential)
                    .addOnCompleteListener { authTask ->
                        if (authTask.isSuccessful) {
                            val user = firebaseAuth.currentUser
                            user?.let {
                                Toast.makeText(this, "Signed in as: ${it.email}", Toast.LENGTH_SHORT).show()
                                initializeDriveService(account)
                            }
                        } else {
                            Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        } else {
            Toast.makeText(this, "Google sign-in failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firebaseAuth = FirebaseAuth.getInstance()

        KeyStoreObjects.getKeyPair()
        val encryptedText = KeyPairManager.encryptTextWithECC(this, "Hello World")
        Log.d("Encrypted Text","$encryptedText")

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            DrivePOCTheme {
                val syncInfo = getSyncInfo()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        onGoogleSignInClick = { launchGoogleSignIn() },
                        onSignOutClick = { signOut() },
                        onNavigateToFileOperations = { navigateToFileOperations() },
                        onCreateKeysClick = { navigateToKeyActivity() },
                        onClearSharedPreferencesClick = { clearSharedPreferences() },
                        syncInfo = syncInfo,
                        onSyncClick = { fetchKeyInfoFromDrive() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun fetchKeyInfoFromDrive() {
        if (driveService == null) {
            Toast.makeText(this, "Drive service not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            try {
                // Query for the file(s) representing the keys
                val result = driveService!!.files().list()
                    .setQ("name = 'ECC_Key_Pair' and mimeType = 'text/plain'") // Adjust query to match your file naming convention
                    .setFields("files(id, name, size, modifiedTime)")
                    .execute()

                val files = result.files
                if (files != null && files.isNotEmpty()) {
                    val file = files[0] // Assume the first file is the relevant one
                    val fetchedFileSize = file.size
                    val modifiedTime = file.modifiedTime?.value ?: 0L

                    // Track and save the download time
                    val currentDownloadTime = System.currentTimeMillis()
                    saveDownloadTime(currentDownloadTime)

                    val lastModifiedTime = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault()
                    ).format(modifiedTime)

                    // Update sync info
                    updateSyncInfo(lastModifiedTime, fetchedFileSize.toLong())

                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Keys fetched successfully\nSize: $fetchedFileSize bytes",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "No keys found in Drive", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to fetch key info: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveDownloadTime(downloadTime: Long) {
        val sharedPreferences = getSharedPreferences("SyncInfo", MODE_PRIVATE)
        sharedPreferences.edit().putLong("lastDownloadTime", downloadTime).apply()
    }


    private fun updateSyncInfo(lastFetchedDate: String, fileSize: Long) {
        val sharedPreferences = getSharedPreferences("sync_info", MODE_PRIVATE)
        sharedPreferences.edit().apply {
            putString("last_fetched_date", lastFetchedDate)
            putLong("file_size", fileSize)
            apply()
        }
    }

    private fun getSyncInfo(): Pair<String, Long> {
        val sharedPreferences = getSharedPreferences("sync_info", MODE_PRIVATE)
        val lastFetchedDate = sharedPreferences.getString("last_fetched_date", "Never") ?: "Never"
        val fileSize = sharedPreferences.getLong("file_size", 0L)
        return Pair(lastFetchedDate, fileSize)
    }


    private fun navigateToKeyActivity() {
        if (driveService != null) {
            val intent = Intent(this, ShowKeyActivity::class.java)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Please sign in to initialize Drive service", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchGoogleSignIn() {
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    private fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            firebaseAuth.signOut()
            Toast.makeText(this, "Signed out successfully", Toast.LENGTH_SHORT).show()
        }
        googleSignInClient.revokeAccess().addOnCompleteListener{
            firebaseAuth.signOut()
        }
    }

    private fun initializeDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            this, listOf(DriveScopes.DRIVE)
        ).apply {
            selectedAccount = account.account
        }

        driveService = Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            JacksonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("DrivePoc")
            .build()
    }

    private fun navigateToFileOperations() {
        val currentUser = firebaseAuth.currentUser
        if (currentUser != null) {
            // User is signed in, navigate to File Operations activity
            val intent = Intent(this, FileOperationsActivity::class.java)
            startActivity(intent)
        } else {
            // User is not signed in, show a Toast to ask for login
            Toast.makeText(this, "Please log in first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearSharedPreferences() {
        val sharedPreferences = getSharedPreferences("key_storage", MODE_PRIVATE)
        sharedPreferences.edit().clear().apply()
        Toast.makeText(this, "SharedPreferences cleared", Toast.LENGTH_SHORT).show()
    }


}

@Composable
fun MainScreen(
    onGoogleSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onNavigateToFileOperations: () -> Unit,
    onCreateKeysClick: () -> Unit,
    onClearSharedPreferencesClick: () -> Unit,
    syncInfo: Pair<String, Long>,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
            .background(Color.White),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onGoogleSignInClick) {
            Text("Sign in with Google")
        }

        Button(onClick = onSignOutClick, modifier = Modifier.padding(top = 16.dp)) {
            Text("Sign out")
        }

        Button(onClick = onNavigateToFileOperations, modifier = Modifier.padding(top = 16.dp)) {
            Text("Go to File Operations")
        }

        Button(onClick = onCreateKeysClick, modifier = Modifier.padding(top = 16.dp)) { // New Button
            Text("Create & Manage Keys")
        }
        Button(onClick = onClearSharedPreferencesClick, modifier = Modifier.padding(top = 16.dp)) { // New Button
            Text("Clear SharedPreferences")
        }

        Button(onClick = onSyncClick, modifier = Modifier.padding(top = 16.dp)) { // Sync Button
            Text("Sync Now")
        }

        Text(
            text = "Last Sync: ${syncInfo.first}\nFile Size: ${syncInfo.second} bytes",
            modifier = Modifier.padding(top = 16.dp),
            color = Color.Black
        )

    }
}
